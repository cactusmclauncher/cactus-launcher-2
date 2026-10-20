package com.cactuslauncher.game;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

public final class JavaRuntimeManager {
    private final Path root;
    private final DownloadControl control;
    public JavaRuntimeManager(Path root) { this(root, new DownloadControl()); }
    public JavaRuntimeManager(Path root, DownloadControl control) { this.root = root.toAbsolutePath().normalize(); this.control = control; }

    /** One lock per runtime folder, shared by every manager instance, so parallel installs and launches never race on it. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public Path ensure(int major, Consumer<String> progress) throws IOException {
        return ensure(major, progress, null);
    }

    public Path ensure(int major, Consumer<String> progress, String architectureOverride) throws IOException {
        String architecture = architectureOverride != null ? architectureOverride : System.getProperty("os.arch", "");
        Object lock = LOCKS.computeIfAbsent(root.resolve(major + "-" + architecture), key -> new Object());
        synchronized (lock) {
            try { return ensureProvider(major, progress, false, architectureOverride); }
            catch (InterruptedIOException e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                return fallback(major, progress, e, architectureOverride);
            } catch (IOException e) { return fallback(major, progress, e, architectureOverride); }
        }
    }

    private Path fallback(int major, Consumer<String> progress, IOException original, String architectureOverride) throws IOException {
        cancelled();
        progress.accept("Adoptium недоступен. Поиск Java " + major + " в Zulu");
        try { return ensureProvider(major, progress, true, architectureOverride); }
        catch (IOException e) { e.addSuppressed(original); throw e; }
    }

    private Path ensureProvider(int major, Consumer<String> progress, boolean zulu, String architectureOverride) throws IOException {
        control.await();
        if (major < 8 || major > 30) throw new IOException("Неподдерживаемая версия Java: " + major);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "mac" : osName.contains("win") ? "windows" : "linux";
        String architecture = System.getProperty("os.arch", "");
        String arch = architectureOverride != null ? architectureOverride : architecture.equals("aarch64") || architecture.equals("arm64") ? "aarch64" : architecture.equals("amd64") || architecture.equals("x86_64") ? "x64" : "";
        if (!arch.equals("aarch64") && !arch.equals("x64"))
            throw new IOException("Неподдерживаемая архитектура Java: " + (arch.isEmpty() ? architecture : arch));
        Path destination = root.resolve(major + "-" + os + "-" + arch);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(destination)) throw new IOException("Папка Java не должна быть символической ссылкой.");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Path existing = existingRuntime(destination, major);
            if (existing != null) return existing;
            progress.accept("Портативная Java " + major + " повреждена, устанавливаем заново");
        }
        Files.createDirectories(root);
        progress.accept("Поиск портативной Java " + major);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        String url = "https://api.adoptium.net/v3/assets/latest/" + major + "/hotspot?architecture=" + arch + "&image_type=jre&os=" + os + "&vendor=eclipse";
        Path staging = Files.createTempDirectory(root, "java-staging-");
        try {
            var assets = new com.google.gson.JsonArray();
            if (!zulu) {
                var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("Каталог OpenJDK недоступен.");
                assets = JsonParser.parseString(response.body()).getAsJsonArray();
                if (assets.isEmpty()) throw new IOException("Adoptium не публикует Java " + major + " для " + os + "/" + arch + ".");
            }
            String checksum;
            String link;
            if (zulu) {
                String azulOs = os.equals("mac") ? "macos" : os;
                String azulArch = arch.equals("x64") ? "x86_64" : arch;
                String extension = os.equals("windows") ? "zip" : "tar.gz";
                String azulUrl = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=" + major + "&os=" + azulOs + "&arch=" + azulArch + "&archive_type=" + extension + "&java_package_type=jdk&release_status=ga&availability_types=CA&latest=true";
                var packagesResponse = client.send(HttpRequest.newBuilder(URI.create(azulUrl)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (packagesResponse.statusCode() != 200) throw new IOException("Каталог Zulu недоступен.");
                var packages = JsonParser.parseString(packagesResponse.body()).getAsJsonArray();
                if (packages.isEmpty()) throw new IOException("Java " + major + " недоступна для " + os + "/" + arch + ".");
                var selected = packages.get(0).getAsJsonObject();
                for (var item : packages) {
                    if (!item.getAsJsonObject().get("name").getAsString().contains("-fx-")) { selected = item.getAsJsonObject(); break; }
                }
                var detailsResponse = client.send(HttpRequest.newBuilder(URI.create("https://api.azul.com/metadata/v1/zulu/packages/" + selected.get("package_uuid").getAsString())).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (detailsResponse.statusCode() != 200) throw new IOException("Не удалось проверить пакет Zulu.");
                var details = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                checksum = details.get("sha256_hash").getAsString();
                link = details.get("download_url").getAsString();
            } else {
                var archiveInfo = assets.get(0).getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
                checksum = archiveInfo.get("checksum").getAsString();
                link = archiveInfo.get("link").getAsString();
            }
            if (!checksum.matches("[a-fA-F0-9]{64}") || !link.startsWith("https://")) throw new IOException("Некорректные метаданные OpenJDK.");
            Path archive = staging.resolve(os.equals("windows") ? "java.zip" : "java.tar.gz");
            progress.accept("Скачивание портативной Java " + major);
            var download = client.send(HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (download.statusCode() != 200) { download.body().close(); throw new IOException("Не удалось скачать Java."); }
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            long lastProgress = 0;
            try (var input = download.body(); var output = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    control.await();
                    cancelled();
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); size += count;
                    if (System.nanoTime() - lastProgress > 200_000_000) {
                        lastProgress = System.nanoTime();
                        progress.accept("Скачивание Java " + major + ": " + size / (1024 * 1024) + " МБ");
                    }
                    if (size > 512L * 1024 * 1024) throw new IOException("Архив Java слишком большой.");
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(checksum)) throw new IOException("Контрольная сумма Java не совпала.");
            progress.accept("Проверка и распаковка Java " + major);
            control.await();
            Path unpacked = Files.createDirectory(staging.resolve("unpacked"));
            if (os.equals("windows")) {
                try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
                    java.util.zip.ZipEntry entry;
                    long expanded = 0;
                    while ((entry = zip.getNextEntry()) != null) {
                        cancelled();
                        Path path = safe(unpacked, entry.getName());
                        if (entry.isDirectory()) Files.createDirectories(path);
                        else {
                            Files.createDirectories(path.getParent());
                            try (var output = Files.newOutputStream(path)) {
                                byte[] buffer = new byte[65536]; int count;
                                while ((count = zip.read(buffer)) != -1) {
                                    cancelled(); expanded += count;
                                    if (expanded > 2L * 1024 * 1024 * 1024) throw new IOException("Распакованная Java слишком большая.");
                                    output.write(buffer, 0, count);
                                }
                            }
                        }
                    }
                }
            } else {
                Path listing = staging.resolve("entries.txt");
                run(new ProcessBuilder("/usr/bin/tar", "-tzf", archive.toString()).redirectOutput(listing.toFile()), staging);
                try (var entries = Files.lines(listing)) {
                    for (String entry : entries.toList()) safe(unpacked, entry);
                }
                run(new ProcessBuilder("/usr/bin/tar", "-xzf", archive.toString(), "-C", unpacked.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD), staging);
            }
            executable(unpacked, major);
            cancelled();
            try { Files.move(unpacked, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(unpacked, destination); }
            return executable(destination, major);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Установка Java отменена.");
        } catch (NoSuchAlgorithmException | RuntimeException e) { throw new IOException("Не удалось подготовить Java " + major + ".", e); }
        finally {
            try (var paths = Files.walk(staging)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Returns the java executable of an installed runtime, or null if the folder is broken
     * (no executable, wrong version, unreadable release file). A broken folder is moved aside and deleted.
     */
    static Path existingRuntime(Path destination, int major) throws IOException {
        try { return executable(destination, major); }
        catch (IOException | UncheckedIOException broken) {
            Path aside = destination.resolveSibling(destination.getFileName() + ".broken-" + UUID.randomUUID().toString().substring(0, 8));
            Files.move(destination, aside);
            try { deleteTree(aside); }
            catch (IOException ignored) { /* The renamed folder no longer blocks a fresh install. */ }
            return null;
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        try (var paths = Files.walk(folder)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Path executable(Path folder, int major) throws IOException {
        try (var paths = Files.walk(folder, 6)) {
            var candidates = paths.filter(p -> p.getFileName().toString().matches("java(\\.exe)?") && p.getParent().getFileName().toString().equals("bin") && Files.isRegularFile(p)).toList();
            for (Path candidate : candidates) {
                if (!candidate.toRealPath().startsWith(folder.toRealPath())) continue;
                Path release = candidate.getParent().getParent().resolve("release");
                if (!Files.isRegularFile(release) && candidate.getParent().getParent().getFileName().toString().equals("jre")) {
                    release = candidate.getParent().getParent().getParent().resolve("release");
                }
                if (!Files.isRegularFile(release)) continue;
                var properties = new Properties();
                try (var input = Files.newInputStream(release)) { properties.load(input); }
                String version = properties.getProperty("JAVA_VERSION", "").replace("\"", "");
                if (javaMajor(version) == major && Files.isExecutable(candidate)) return candidate;
            }
        }
        throw new IOException("Установленная Java повреждена или имеет другую версию.");
    }

    /** Major version from JAVA_VERSION: "1.8.0_402" → 8, "21" → 21, "21.0.12.1" → 21; -1 if unreadable. */
    static int javaMajor(String version) {
        String[] parts = version.trim().split("[.+_-]");
        try {
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException e) { return -1; }
    }

    private static Path safe(Path root, String name) throws IOException {
        Path result = root.resolve(name).normalize();
        if (Path.of(name).isAbsolute() || !result.startsWith(root)) throw new IOException("Некорректный путь в архиве Java.");
        return result;
    }

    private static void run(ProcessBuilder builder, Path staging) throws IOException, InterruptedException {
        Path errors = staging.resolve("tar-errors.txt");
        Process process = builder.redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Распаковка Java превысила время ожидания.");
            if (process.exitValue() != 0) {
                String details = Files.readString(errors).strip();
                if (details.length() > 1200) details = details.substring(0, 1200);
                throw new IOException("Не удалось распаковать Java (код " + process.exitValue() + "): " + details);
            }
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Установка Java отменена.");
    }
}
