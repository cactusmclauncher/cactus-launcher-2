package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.function.Consumer;

public final class HorizonInstaller {
    public static final String LAUNCH_LIMITATION = "Horizon поддерживает офлайн-игру без сессии, но upstream требует от лаунчера аккаунт владельца Minecraft: Java Edition или ранее сохранённое подтверждение владения при отсутствии сети. В Cactus Microsoft-вход и такое подтверждение ещё не подключены; локальные профили и файлы не заменяют проверку владения.";
    private static final String REPO = "https://github.com/horizon-rust/horizon/releases/download/";
    private static final String KEY = "MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw=";
    private static final long MAX_DOWNLOAD = 256L * 1024 * 1024;
    private final Path directory;
    private final Transport transport;
    private final String signingKey;
    private final HorizonRuntime runtime;

    public boolean running() { return runtime.active(); }

    private void requireStopped() throws IOException {
        if (runtime.active()) throw new IOException("Закройте Horizon перед установкой, подготовкой или обновлением.");
    }

    public HorizonRuntime.Result launch(ProfileStore profiles, Consumer<String> onRunning) throws IOException {
        Path root;
        Path resources;
        ProfileStore.Profile profile;
        synchronized (this) {
            requireStopped();
            Installation installation = installed();
            if (installation == null) throw new IOException("Сначала установите Horizon.");
            root = Path.of(installation.path());
            resources = HorizonPreparation.resolvedResources(root);
            if (resources == null) throw new IOException("Сначала подготовьте ресурсы Horizon.");
            ProfileStore.Snapshot snapshot = profiles.load();
            profile = snapshot.profiles().stream().filter(it -> it.id().equals(snapshot.activeId())).findFirst()
                    .orElseThrow(() -> new IOException("Выберите локальный профиль Cactus."));
            runtime.reserve();
        }
        try { return runtime.run(root, resources, directory.resolve("profiles"), profile, onRunning); }
        finally { runtime.release(); }
    }
    interface Transport { byte[] fetch(String url, long limit, Path destination, Consumer<Float> progress) throws IOException; }
    public record Release(String tag, String name, String asset, String url, String sumsUrl, String signatureUrl, long size) {}
    public record Installation(String release, String minecraft, int javaMajor, String path, String minecraftPath) {}
    public record Progress(String message, float fraction) {}
    public record Update(Installation installation, String latest, boolean available, String status) {}

    public synchronized Update checkUpdate(boolean automatic, Consumer<Progress> progress) throws IOException {
        requireStopped();
        Installation current = installed();
        try {
            Release release = latest();
            byte[] sums = transport.fetch(release.sumsUrl(), 65536, null, null);
            String signatures = new String(transport.fetch(release.signatureUrl(), 4096, null, null), StandardCharsets.UTF_8);
            String hash = verifiedChecksum(release.tag(), sums, signatures, release.asset(), signingKey);
            boolean available = current == null || !Path.of(current.path()).getFileName().toString().equals("build-" + hash);
            if (automatic && available && current != null) {
                Installation updated = install(progress);
                return new Update(updated, updated.release(), false, "Nightly обновлён. Подготовленная установка обновляется вместе с ресурсами; предыдущая версия сохранена.");
            }
            return new Update(current, release.name(), available, available ? "Доступен новый Nightly." : "Установлен актуальный Nightly.");
        } catch (InterruptedIOException failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            if (current == null) throw failure;
            return new Update(current, "", false, "Сеть недоступна: сохранена локальная установка. " + failure.getMessage());
        } catch (IOException failure) {
            if (current == null) throw failure;
            return new Update(current, "", false, "Проверка или обновление не выполнены: локальная установка сохранена. " + failure.getMessage());
        }
    }

    public synchronized boolean prepared() throws IOException {
        Installation installation = installed();
        return installation != null && HorizonPreparation.prepared(Path.of(installation.path()));
    }

    public synchronized String prepare(String minecraftPath, String javaHome, Consumer<Progress> progress) throws IOException {
        requireStopped();
        Installation installation = installed();
        if (installation == null) throw new IOException("Сначала установите Horizon.");
        Path root = Path.of(installation.path());
        HorizonPreparation.prepare(root, directory.resolve("inputs"), minecraftPath.isBlank() ? null : Path.of(minecraftPath).toAbsolutePath().normalize(), javaHome, progress);
        saveSource(minecraftPath);
        Path temporary = Files.createTempFile(directory, ".java-", ".tmp");
        try {
            Files.writeString(temporary, javaHome);
            Files.move(temporary, directory.resolve("java-home"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return "Ресурсы подготовлены и сохранены локально. Доступен автономный запуск без сессии.";
    }

    public synchronized String javaHome() throws IOException {
        Path file = directory.resolve("java-home");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "";
        regular(file);
        if (Files.size(file) > 8192) throw new IOException("Повреждён путь Java Horizon.");
        return Files.readString(file);
    }

    public synchronized String suggestedMinecraft() throws IOException {
        Installation installation = installed();
        if (installation == null) return "";
        if (!installation.minecraftPath().isBlank()) return installation.minecraftPath();
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path path;
        if (os.contains("mac")) path = home.resolve("Library/Application Support/minecraft");
        else if (os.contains("windows")) {
            String appdata = System.getenv("APPDATA");
            if (appdata == null || appdata.isBlank()) return "";
            path = Path.of(appdata).resolve(".minecraft");
        } else path = home.resolve(".minecraft");
        Path version = path.resolve("versions").resolve(installation.minecraft());
        if (!Files.isRegularFile(version.resolve(installation.minecraft() + ".jar"), LinkOption.NOFOLLOW_LINKS)) return "";
        secureExisting(path);
        return path.toString();
    }

    public synchronized Installation rollback() throws IOException {
        requireStopped();
        Path previous = directory.resolve("previous");
        regular(previous);
        if (Files.size(previous) > 128) throw new IOException("Повреждён указатель предыдущей установки.");
        String name = Files.readString(previous);
        if (!name.matches("build-[a-f0-9]{64}")) throw new IOException("Повреждён указатель предыдущей установки.");
        Installation installation = readInstallation(directory.resolve(name));
        Path pointer = Files.createTempFile(directory, ".rollback-", ".tmp");
        try {
            Files.writeString(pointer, name);
            cancelled();
            Files.move(pointer, directory.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pointer); }
        return installation;
    }

    private void saveSource(String path) throws IOException {
        Path temporary = Files.createTempFile(directory, ".source-", ".tmp");
        try {
            Files.writeString(temporary, path.isBlank() ? "" : Path.of(path).toAbsolutePath().normalize().toString());
            cancelled();
            Files.move(temporary, directory.resolve("minecraft-path"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public HorizonInstaller(Path directory) { this(directory, HorizonInstaller::fetch, KEY); }
    HorizonInstaller(Path directory, Transport transport, String signingKey) { this(directory, transport, signingKey, new HorizonRuntime()); }
    HorizonInstaller(Path directory, Transport transport, String signingKey, HorizonRuntime runtime) {
        this.directory = directory.toAbsolutePath().normalize();
        this.transport = transport;
        this.signingKey = signingKey;
        this.runtime = runtime;
    }

    public Release latest() throws IOException {
        String asset = platformAsset(System.getProperty("os.name"), System.getProperty("os.arch"));
        byte[] response = transport.fetch("https://api.github.com/repos/horizon-rust/horizon/releases/tags/nightly", 2 * 1024 * 1024, null, null);
        try { return select(JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject(), asset); }
        catch (RuntimeException e) { throw new IOException("GitHub вернул некорректный релиз Horizon.", e); }
    }

    static String platformAsset(String os, String arch) throws IOException {
        os = os.toLowerCase(Locale.ROOT);
        arch = arch.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (os.contains("mac") && (arm || x64)) return "horizon-macos-" + (arm ? "arm64" : "x64") + ".zip";
        if (os.contains("windows") && x64) return "horizon-windows-x64.zip";
        if (os.contains("linux") && x64) return "horizon-linux-x86_64.tar.gz";
        throw new IOException("Для этой ОС и архитектуры Horizon не публикует игру.");
    }

    static Release select(JsonObject release, String assetName) throws IOException {
        String tag = release.get("tag_name").getAsString();
        if (!tag.equals("nightly") || release.get("draft").getAsBoolean()) throw new IOException("Релиз Horizon не опубликован.");
        Map<String, JsonObject> assets = new HashMap<>();
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (assets.put(asset.get("name").getAsString(), asset) != null) throw new IOException("Повторяющийся файл релиза Horizon.");
        }
        JsonObject game = assets.get(assetName);
        if (game == null) throw new IOException("В последнем Nightly нет игры для этой системы.");
        long size = game.get("size").getAsLong();
        if (size <= 0 || size > MAX_DOWNLOAD) throw new IOException("Размер архива Horizon недопустим.");
        return new Release(tag, release.get("name").getAsString(), assetName, assetUrl(game, tag),
                assetUrl(assets.get("SHA256SUMS"), tag), assetUrl(assets.get("SHA256SUMS.sig"), tag), size);
    }

    private static String assetUrl(JsonObject asset, String tag) throws IOException {
        if (asset == null || !asset.get("state").getAsString().equals("uploaded")) throw new IOException("Не все файлы Horizon опубликованы.");
        String url = asset.get("browser_download_url").getAsString();
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("Некорректная ссылка Horizon.", e); }
        if (!url.startsWith(REPO + tag + "/") || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null
                || !uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1).equals(asset.get("name").getAsString()))
            throw new IOException("Недоверенная ссылка Horizon.");
        return url;
    }

    public synchronized Installation install(Consumer<Progress> progress) throws IOException {
        requireStopped();
        progress.accept(new Progress("Ищем последний Nightly…", 0f));
        Release release = latest();
        progress.accept(new Progress("Проверяем подпись релиза…", 0.02f));
        byte[] sums = transport.fetch(release.sumsUrl(), 65536, null, null);
        String signatures = new String(transport.fetch(release.signatureUrl(), 4096, null, null), StandardCharsets.UTF_8);
        String hash = verifiedChecksum(release.tag(), sums, signatures, release.asset(), signingKey);
        Installation previous = installed();
        if (previous != null && Path.of(previous.path()).getFileName().toString().equals("build-" + hash)) {
            progress.accept(new Progress("Установлен актуальный Nightly.", 1f));
            return previous;
        }
        boolean keepPrepared = previous != null && HorizonPreparation.prepared(Path.of(previous.path()));
        secureDirectory(directory);
        Path staging = Files.createTempDirectory(directory, ".staging-");
        try {
            Path archive = staging.resolve("download");
            transport.fetch(release.url(), release.size(), archive, fraction -> progress.accept(new Progress("Скачиваем " + release.asset(), 0.05f + fraction * 0.75f)));
            if (Files.size(archive) != release.size() || !digest(archive, "SHA-256").equals(hash)) throw new IOException("Архив Horizon не совпадает с подписанной контрольной суммой. Повторите установку: Nightly мог обновиться.");
            cancelled();
            progress.accept(new Progress("Распаковываем игровые файлы…", 0.82f));
            Path unpacked = staging.resolve("unpacked");
            HorizonArchive.extract(archive, unpacked, release.asset().endsWith(".tar.gz"));
            Path root = bundleRoot(unpacked);
            JsonObject pins = pins(root);
            String version = pins.get("version").getAsString();
            int javaMajor = pins.get("java_major").getAsInt();
            Path target = directory.resolve("build-" + hash);
            Properties metadata = new Properties();
            metadata.setProperty("format", "1");
            metadata.setProperty("release", release.name());
            metadata.setProperty("sha256", hash);
            metadata.setProperty("asset", release.asset());
            metadata.setProperty("minecraft", version);
            metadata.setProperty("java", Integer.toString(javaMajor));
            metadata.setProperty("prepared", "false");
            metadata.setProperty("clientSha256", digest(root.resolve(binary("horizon-client")), "SHA-256"));
            metadata.setProperty("prepareSha256", digest(root.resolve(binary("horizon-prepare")), "SHA-256"));
            metadata.setProperty("lockSha256", digest(root.resolve("research-lock.json"), "SHA-256"));
            try (var files = Files.walk(root)) {
                List<Path> entries = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
                metadata.setProperty("files.count", Integer.toString(entries.size()));
                for (int index = 0; index < entries.size(); index++) {
                    Path file = entries.get(index);
                    metadata.setProperty("file." + index + ".path", root.relativize(file).toString().replace('\\', '/'));
                    metadata.setProperty("file." + index + ".sha256", digest(file, "SHA-256"));
                }
            }
            try (OutputStream output = Files.newOutputStream(root.resolve("cactus-install.properties"), StandardOpenOption.CREATE_NEW)) { metadata.store(output, null); }
            cancelled();
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.move(root, target, StandardCopyOption.ATOMIC_MOVE);
            else readInstallation(target);
            if (keepPrepared && !HorizonPreparation.prepared(target)) {
                HorizonPreparation.prepare(target, directory.resolve("inputs"), previous.minecraftPath().isBlank() ? null : Path.of(previous.minecraftPath()), javaHome(), update ->
                        progress.accept(new Progress(update.message(), 0.85f + 0.14f * update.fraction())));
            }
            Installation installation = readInstallation(target);
            cancelled();
            if (previous != null) {
                Path backup = staging.resolve("previous");
                Files.writeString(backup, Path.of(previous.path()).getFileName().toString(), StandardOpenOption.CREATE_NEW);
                Files.move(backup, directory.resolve("previous"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            Path pointer = staging.resolve("current");
            Files.writeString(pointer, target.getFileName().toString(), StandardOpenOption.CREATE_NEW);
            Files.move(pointer, directory.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(new Progress(keepPrepared ? "Horizon и ресурсы обновлены." : "Игровые файлы установлены. Ресурсы ещё не подготовлены.", 1f));
            return installation;
        } finally { deleteTree(staging); }
    }

    public synchronized Installation installed() throws IOException {
        Path pointer = directory.resolve("current");
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) return null;
        regular(pointer);
        if (Files.size(pointer) > 128) throw new IOException("Повреждён указатель установки Horizon.");
        String name = Files.readString(pointer);
        if (!name.matches("build-[a-f0-9]{64}")) throw new IOException("Повреждён указатель установки Horizon.");
        return readInstallation(directory.resolve(name));
    }

    private Installation readInstallation(Path root) throws IOException {
        secureExisting(root);
        Path file = root.resolve("cactus-install.properties");
        regular(file);
        if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("Повреждены данные установки Horizon.");
        Properties data = new Properties();
        try (InputStream input = Files.newInputStream(file)) { data.load(input); }
        try {
            if (!data.getProperty("format", "").equals("1") || !data.getProperty("prepared", "").equals("false")
                    || !root.getFileName().toString().equals("build-" + data.getProperty("sha256"))) throw new IOException("Повреждены данные установки Horizon.");
            for (String[] pair : new String[][] {{binary("horizon-client"), "clientSha256"}, {binary("horizon-prepare"), "prepareSha256"}, {"research-lock.json", "lockSha256"}}) {
                if (!digest(root.resolve(pair[0]), "SHA-256").equals(data.getProperty(pair[1]))) throw new IOException("Игровые файлы Horizon повреждены. Переустановка не должна считаться успешной.");
            }
            int count = Integer.parseInt(data.getProperty("files.count"));
            if (count < 4 || count > 20000) throw new IOException("Повреждён список файлов Horizon.");
            Set<String> files = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String relative = data.getProperty("file." + index + ".path");
                if (!files.add(relative) || relative.equals("cactus-install.properties") || !digest(HorizonArchive.safe(root, relative), "SHA-256").equals(data.getProperty("file." + index + ".sha256")))
                    throw new IOException("Файлы установки Horizon повреждены.");
            }
            if (!files.containsAll(List.of(binary("horizon-client"), binary("horizon-prepare"), "research-lock.json", "exporters/manifest.json"))) throw new IOException("Установка Horizon неполная.");
            JsonObject pins = pins(root);
            if (!pins.get("version").getAsString().equals(data.getProperty("minecraft")) || pins.get("java_major").getAsInt() != Integer.parseInt(data.getProperty("java")))
                throw new IOException("Данные установки Horizon не совпадают с research-lock.json.");
            String source = "";
            Path sourceFile = directory.resolve("minecraft-path");
            if (Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
                regular(sourceFile);
                if (Files.size(sourceFile) > 8192) throw new IOException("Повреждён путь Minecraft.");
                source = Files.readString(sourceFile);
            }
            return new Installation(data.getProperty("release"), data.getProperty("minecraft"), Integer.parseInt(data.getProperty("java")), root.toString(), source);
        } catch (RuntimeException e) { throw new IOException("Повреждены данные установки Horizon.", e); }
    }

    public synchronized String validateMinecraft(String path) throws IOException {
        Installation installation = installed();
        if (installation == null) throw new IOException("Сначала установите игровые файлы Horizon.");
        Path source;
        try { source = Path.of(path).toAbsolutePath().normalize(); } catch (RuntimeException e) { throw new IOException("Некорректный путь Minecraft.", e); }
        HorizonInputs.validate(source, pins(Path.of(installation.path())));
        Path temporary = Files.createTempFile(directory, ".source-", ".tmp");
        try {
            Files.writeString(temporary, source.toString());
            cancelled();
            Files.move(temporary, directory.resolve("minecraft-path"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return "Локальные JSON, клиент, библиотеки и assets проверены. Это не подтверждение владения и не подготовка ресурсов. Для подготовки также нужны серверный JAR и Java " + installation.javaMajor() + ".";
    }

    static Path bundleRoot(Path unpacked) throws IOException {
        try (var paths = Files.list(unpacked)) {
            List<Path> entries = paths.toList();
            if (entries.size() != 1 || !Files.isDirectory(entries.getFirst(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Неизвестная структура архива Horizon.");
            Path root = entries.getFirst();
            for (String name : List.of(binary("horizon-client"), binary("horizon-prepare"), "research-lock.json", "exporters/manifest.json")) regular(root.resolve(name));
            return root;
        }
    }

    static JsonObject pins(Path root) throws IOException {
        Path lock = root.resolve("research-lock.json");
        regular(lock);
        if (Files.size(lock) > 1024 * 1024) throw new IOException("Слишком большой research-lock.json.");
        try {
            JsonObject pins = JsonParser.parseString(Files.readString(lock)).getAsJsonObject().getAsJsonObject("minecraft");
            if (!pins.get("version").getAsString().matches("[A-Za-z0-9._-]{1,64}") || pins.get("java_major").getAsInt() < 8 || pins.get("java_major").getAsInt() > 99)
                throw new IOException("Некорректные требования Minecraft в Horizon.");
            return pins;
        } catch (RuntimeException e) { throw new IOException("Некорректный research-lock.json.", e); }
    }

    static String verifiedChecksum(String tag, byte[] sums, String signatures, String asset, String key) throws IOException {
        try {
            byte[] raw = Base64.getDecoder().decode(key);
            byte[] encoded = new byte[44];
            System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, encoded, 0, 12);
            if (raw.length != 32) throw new IOException("Некорректный ключ Horizon.");
            System.arraycopy(raw, 0, encoded, 12, 32);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            boolean verified = false;
            for (String line : signatures.lines().toList()) {
                if (line.isBlank()) continue;
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(publicKey);
                verifier.update(("horizon release " + tag + "\n").getBytes(StandardCharsets.UTF_8));
                verifier.update(sums);
                try { if (verifier.verify(Base64.getDecoder().decode(line.trim()))) verified = true; }
                catch (IllegalArgumentException | SignatureException ignored) { }
            }
            if (!verified) throw new IOException("Подпись релиза Horizon не подтверждена.");
            String selected = null;
            for (String line : new String(sums, StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank()) continue;
                if (!line.matches("[a-fA-F0-9]{64} [ *].+")) throw new IOException("Повреждён список контрольных сумм Horizon.");
                if (line.substring(66).equals(asset)) {
                    if (selected != null) throw new IOException("Повторяющаяся контрольная сумма Horizon.");
                    selected = line.substring(0, 64).toLowerCase(Locale.ROOT);
                }
            }
            if (selected == null) throw new IOException("Архив отсутствует в подписанном списке Horizon.");
            return selected;
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Не удалось проверить подпись Horizon.", e); }
    }

    private static byte[] fetch(String url, long limit, Path destination, Consumer<Float> progress) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        URL current = URI.create(url).toURL();
        for (int redirects = 0; redirects < 6; redirects++) {
            cancelled();
            String host = current.getHost();
            if (!current.getProtocol().equals("https") || !(host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")) || current.getUserInfo() != null)
                throw new IOException("Недоверенное перенаправление Horizon.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Cactus-Horizon-Beta");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Пустое перенаправление Horizon.");
                    current = new URL(current, location);
                    continue;
                }
                if (status != 200) throw new IOException("GitHub Horizon: HTTP " + status + (status == 403 || status == 429 ? ". Повторите позже: возможен лимит запросов." : "."));
                if (connection.getContentLengthLong() > limit) throw new IOException("Загрузка Horizon превышает лимит.");
                ByteArrayOutputStream memory = destination == null ? new ByteArrayOutputStream() : null;
                try (InputStream input = connection.getInputStream(); OutputStream output = destination == null ? memory : Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        cancelled();
                        if (System.nanoTime() > deadline) throw new IOException("Время загрузки Horizon истекло.");
                        total += read;
                        if (total > limit) throw new IOException("Загрузка Horizon превышает лимит.");
                        output.write(buffer, 0, read);
                        if (progress != null) progress.accept((float) total / limit);
                    }
                }
                return memory == null ? new byte[0] : memory.toByteArray();
            } finally { connection.disconnect(); }
        }
        throw new IOException("Слишком много перенаправлений Horizon.");
    }

    static String digest(Path file, String algorithm) throws IOException {
        regular(file);
        if (Files.size(file) > 1024L * 1024 * 1024) throw new IOException("Файл Horizon/Minecraft превышает лимит.");
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) { cancelled(); digest.update(buffer, 0, read); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static String binary(String name) { return name + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? ".exe" : ""); }
    static void regular(Path path) throws IOException {
        secureExisting(path.getParent());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) throw new IOException("Отсутствует обычный читаемый файл: " + path.getFileName());
    }
    static void secureDirectory(Path path) throws IOException { secureExisting(path); Files.createDirectories(path); }
    static void secureExisting(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Символические ссылки в пути Horizon/Minecraft не поддерживаются.");
    }
    static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Операция Horizon отменена.");
    }
    static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
