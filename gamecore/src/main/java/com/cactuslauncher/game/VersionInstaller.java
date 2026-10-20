package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

public final class VersionInstaller {
    public record Progress(double fraction, String stage, String lastFile, int completed, int total, double bytesPerSecond, long remainingSeconds, int phase) {}
    private record Download(String url, String sha1, long size, Path target) {}
    private final Path root;
    private final Consumer<Progress> listener;
    private long lastUpdate;
    private String lastFile = "";
    private int completed;
    private int total;
    private long bytes;
    private long totalBytes;
    private final DownloadControl control;
    private long started;
    private int phase;
    private final int parallelism;
    private final Map<Path, Long> inFlight = new HashMap<>();

    public VersionInstaller(Path root, Consumer<Progress> listener) {
        this(root, listener, new DownloadControl());
    }

    public VersionInstaller(Path root, Consumer<Progress> listener, DownloadControl control) {
        this(root, listener, control, 4);
    }

    public VersionInstaller(Path root, Consumer<Progress> listener, DownloadControl control, int parallelism) {
        this.root = root.toAbsolutePath().normalize();
        this.listener = listener;
        this.control = control;
        if (parallelism < 1 || parallelism > 16) throw new IllegalArgumentException("Число загрузок: от 1 до 16.");
        this.parallelism = parallelism;
    }

    public void install(String version) throws IOException {
        report(0, "Загрузка описания версии", true);
        var manifest = json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        JsonObject entry = null;
        for (var item : manifest.getAsJsonArray("versions")) {
            if (item.getAsJsonObject().get("id").getAsString().equals(version)) { entry = item.getAsJsonObject(); break; }
        }
        if (entry == null) throw new IOException("Версия отсутствует в каталоге Minecraft.");
        var metadata = download(entry, safe("versions/" + version + "/" + version + ".json"));
        fetch(metadata, "Описание версии");
        lastFile = root.relativize(metadata.target()).toString();
        JsonObject info;
        try (var reader = Files.newBufferedReader(metadata.target())) { info = JsonParser.parseReader(reader).getAsJsonObject(); }
        if (info.has("inheritsFrom")) throw new IOException("Установка наследуемых версий пока не поддерживается.");
        int javaVersion = info.has("javaVersion") ? info.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
        boolean useRosetta = MinecraftLauncher.requiresRosetta(info);
        String runtimeArchitecture = useRosetta ? "x64" : null;
        new JavaRuntimeManager(root.getParent().getParent().resolve("runtimes"), control).ensure(javaVersion,
                message -> report(0, message, true), runtimeArchitecture);
        var downloads = new ArrayList<Download>();
        if (!info.has("downloads") || !info.getAsJsonObject("downloads").has("client"))
            throw new IOException("Для этой старой версии отсутствует официальный клиент.");
        downloads.add(download(info.getAsJsonObject("downloads").getAsJsonObject("client"), safe("versions/" + version + "/" + version + ".jar")));
        if (info.has("libraries")) for (var item : info.getAsJsonArray("libraries")) {
            var library = item.getAsJsonObject();
            if (!allowed(library) || !library.has("downloads")) continue;
            var files = library.getAsJsonObject("downloads");
            if (files.has("artifact")) addArtifact(downloads, files.getAsJsonObject("artifact"));
            if (library.has("natives") && files.has("classifiers")) {
                var natives = library.getAsJsonObject("natives");
                if (natives.has(os())) {
                    var classifiers = files.getAsJsonObject("classifiers");
                    String classifier = nativeClassifier(natives.get(os()).getAsString(), classifiers, useRosetta);
                    if (classifiers.has(classifier)) addArtifact(downloads, classifiers.getAsJsonObject(classifier));
                }
            }
        }
        if (info.has("assetIndex")) {
            var indexInfo = info.getAsJsonObject("assetIndex");
            var index = download(indexInfo, safe("assets/indexes/" + indexInfo.get("id").getAsString() + ".json"));
            fetch(index, "Каталог ресурсов");
            lastFile = root.relativize(index.target()).toString();
            JsonObject assets;
            try (var reader = Files.newBufferedReader(index.target())) { assets = JsonParser.parseReader(reader).getAsJsonObject(); }
            var hashes = new HashSet<String>();
            for (var value : assets.getAsJsonObject("objects").entrySet()) {
                var asset = value.getValue().getAsJsonObject();
                String hash = asset.get("hash").getAsString();
                if (!hash.matches("[a-f0-9]{40}")) throw new IOException("Некорректный хеш ресурса.");
                if (hashes.add(hash)) downloads.add(new Download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, hash, asset.get("size").getAsLong(), safe("assets/objects/" + hash.substring(0, 2) + "/" + hash)));
                if (assets.has("virtual") && assets.get("virtual").getAsBoolean())
                    downloads.add(new Download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, hash, asset.get("size").getAsLong(), safe("assets/virtual/" + indexInfo.get("id").getAsString() + "/" + value.getKey())));
                if (assets.has("map_to_resources") && assets.get("map_to_resources").getAsBoolean())
                    downloads.add(new Download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, hash, asset.get("size").getAsLong(), safe("resources/" + value.getKey())));
            }
        }
        if (info.has("logging") && info.getAsJsonObject("logging").has("client")) {
            var logging = info.getAsJsonObject("logging").getAsJsonObject("client").getAsJsonObject("file");
            downloads.add(download(logging, safe("assets/log_configs/" + logging.get("id").getAsString())));
        }
        downloads = new ArrayList<>(downloads.stream().collect(java.util.stream.Collectors.toMap(Download::target, file -> file, (a, b) -> a, LinkedHashMap::new)).values());
        total = downloads.size();
        bytes = 0;
        totalBytes = downloads.stream().mapToLong(Download::size).sum();
        started = System.nanoTime();
        for (int step = 0; step < 3; step++) {
            phase = step;
            final int current = step;
            var batch = downloads.stream().filter(file -> filePhase(file) == current).toList();
            downloadBatch(batch, step == 0 ? "Клиент Minecraft" : step == 1 ? "Библиотеки" : "Ресурсы и звуки");
        }
        checkCancelled();
        Path marker = safe(".cactus-installed");
        Path temp = Files.createTempFile(root, "installed-", ".tmp");
        try {
            Files.writeString(temp, version);
            move(temp, marker);
        } finally { Files.deleteIfExists(temp); }
        report(1, "Версия скачана", true);
    }

    private int filePhase(Download file) {
        String relative = root.relativize(file.target()).toString().replace('\\', '/');
        return relative.startsWith("versions/") ? 0 : relative.startsWith("libraries/") ? 1 : 2;
    }

    private void downloadBatch(List<Download> files, String stage) throws IOException {
        if (files.isEmpty()) return;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(parallelism, files.size()));
        var completion = new java.util.concurrent.ExecutorCompletionService<Void>(pool);
        var index = new java.util.concurrent.atomic.AtomicInteger();
        int workers = Math.min(parallelism, files.size());
        try {
            for (int i = 0; i < workers; i++) completion.submit(() -> {
                int next;
                while ((next = index.getAndIncrement()) < files.size()) {
                    control.await();
                    var file = files.get(next);
                    fetch(file, stage);
                    synchronized (this) {
                        completed++;
                        lastFile = root.relativize(file.target()).toString();
                        report(overallFraction(), stage, completed == total);
                    }
                }
                return null;
            });
            for (int i = 0; i < workers; i++) completion.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Скачивание отменено.");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Не удалось скачать файлы Minecraft.", e.getCause());
        } finally {
            pool.shutdownNow();
            boolean interrupted = Thread.interrupted();
            while (!pool.isTerminated()) {
                try { pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private synchronized double overallFraction() {
        long active = inFlight.values().stream().mapToLong(Long::longValue).sum();
        return totalBytes > 0 ? (double) (bytes + active) / totalBytes : 0;
    }

    private void addArtifact(List<Download> result, JsonObject artifact) throws IOException {
        result.add(download(artifact, safe("libraries/" + artifact.get("path").getAsString())));
    }

    private Download download(JsonObject info, Path target) throws IOException {
        String sha = info.has("sha1") ? info.get("sha1").getAsString() : "";
        if (!sha.matches("[a-f0-9]{40}")) throw new IOException("В каталоге отсутствует контрольная сумма файла.");
        return new Download(info.get("url").getAsString(), sha, info.has("size") ? info.get("size").getAsLong() : 0, target);
    }

    private JsonObject json(String url) throws IOException {
        var connection = connect(url);
        try (var input = connection.getInputStream(); var reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally { connection.disconnect(); }
    }

    private void fetch(Download file, String stage) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try { fetchOnce(file, stage); return; }
            catch (InterruptedIOException e) { if (Thread.currentThread().isInterrupted()) throw e; if (attempt >= 2) throw e; }
            catch (IOException e) { if (attempt >= 2) throw e; }
            try { Thread.sleep(500L * (attempt + 1)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Скачивание отменено."); }
        }
    }

    private void fetchOnce(Download file, String stage) throws IOException {
        control.await();
        checkCancelled();
        Files.createDirectories(file.target().getParent());
        if (Files.isRegularFile(file.target()) && hash(file.target()).equals(file.sha1())) {
            synchronized (this) { bytes += Math.max(0, file.size()); }
            return;
        }
        var connection = connect(file.url());
        Path temp = Files.createTempFile(file.target().getParent(), "download-", ".part");
        try {
            long received = 0;
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    control.await();
                    checkCancelled();
                    output.write(buffer, 0, count);
                    received += count;
                    synchronized (this) {
                        inFlight.put(file.target(), Math.min(received, file.size()));
                        if (totalBytes > 0) report(overallFraction(), stage, false);
                    }
                }
            }
            if ((file.size() > 0 && received != file.size()) || !hash(temp).equals(file.sha1())) throw new IOException("Контрольная сумма не совпала: " + file.target().getFileName());
            checkCancelled();
            move(temp, file.target());
            synchronized (this) {
                inFlight.remove(file.target());
                bytes += Math.max(0, file.size());
            }
        } finally {
            synchronized (this) { inFlight.remove(file.target()); }
            connection.disconnect(); Files.deleteIfExists(temp);
        }
    }

    private static HttpURLConnection connect(String url) throws IOException {
        checkCancelled();
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !(host.endsWith(".mojang.com") || host.endsWith(".minecraft.net") || host.equals("libraries.minecraft.net"))) throw new IOException("Неизвестный сервер загрузки Minecraft.");
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CactusLauncher/2.0");
        if (connection.getResponseCode() != 200) { connection.disconnect(); throw new IOException("Сервер Minecraft вернул ошибку загрузки."); }
        return connection;
    }

    Path safe(String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Некорректный путь файла версии.");
        for (Path current = path; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Путь установки содержит символическую ссылку.");
        }
        return path;
    }

    private static String hash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, count); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private synchronized void report(double fraction, String stage, boolean force) {
        long now = System.nanoTime();
        if (force || now - lastUpdate > 100_000_000) {
            lastUpdate = now;
            double elapsed = started == 0 ? 0 : (now - started) / 1_000_000_000.0;
            double done = Math.max(bytes, fraction * totalBytes);
            double speed = elapsed > 0 ? done / elapsed : 0;
            long remaining = speed > 0 ? (long) Math.ceil(Math.max(0, totalBytes - done) / speed) : -1;
            listener.accept(new Progress(Math.max(0, Math.min(1, fraction)), stage, lastFile, completed, total, speed, remaining, phase));
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Скачивание отменено.");
    }

    private static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "osx" : os.contains("win") ? "windows" : "linux";
    }

    static String nativeClassifier(String template, JsonObject classifiers, boolean preferX64) {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = architecture.equals("aarch64") || architecture.equals("arm64");
        String preferredArch = arm ? (preferX64 ? "64" : "arm64") : architecture.contains("64") ? "64" : "32";
        String preferred = template.replace("${arch}", preferredArch);
        if (classifiers.has(preferred)) return preferred;
        if (arm) {
            String fallback = template.replace("${arch}", preferredArch.equals("arm64") ? "64" : "arm64");
            if (classifiers.has(fallback)) return fallback;
        }
        return preferred;
    }

    static boolean allowed(JsonObject library) {
        if (!library.has("rules")) return true;
        boolean allowed = false;
        for (var item : library.getAsJsonArray("rules")) {
            var rule = item.getAsJsonObject();
            boolean matches = !rule.has("features");
            if (rule.has("os")) {
                var platform = rule.getAsJsonObject("os");
                if (platform.has("name")) matches &= platform.get("name").getAsString().equals(os());
                if (platform.has("arch")) matches &= System.getProperty("os.arch").matches(platform.get("arch").getAsString());
                if (platform.has("version")) matches &= System.getProperty("os.version").matches(platform.get("version").getAsString());
            }
            if (matches) allowed = rule.get("action").getAsString().equals("allow");
        }
        return allowed;
    }

    public static boolean installed(Path root, String version) {
        try { return Files.readString(root.resolve(".cactus-installed")).equals(version) && Files.isRegularFile(root.resolve("versions/" + version + "/" + version + ".jar")); }
        catch (IOException e) { return false; }
    }
}
