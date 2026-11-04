package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class HorizonResources {
    interface Downloader { void download(String url, Path file, long limit) throws IOException; }
    record Inputs(Path minecraft, Path server) {}
    private static final long MAX_TOTAL = 6L * 1024 * 1024 * 1024;
    private static final long MAX_FILE = 512L * 1024 * 1024;
    private static final Set<String> HOSTS = Set.of("piston-meta.mojang.com", "piston-data.mojang.com", "launcher.mojang.com", "libraries.minecraft.net", "resources.download.minecraft.net");

    static Inputs acquire(Path source, Path cache, JsonObject pins, Consumer<HorizonInstaller.Progress> progress) throws IOException {
        return acquire(source, cache, pins, progress, HorizonResources::download);
    }

    static Inputs acquire(Path source, Path cache, JsonObject pins, Consumer<HorizonInstaller.Progress> progress, Downloader downloader) throws IOException {
        try {
            String version = pins.get("version").getAsString();
            if (!version.matches("[A-Za-z0-9._-]{1,64}") || version.equals(".") || version.equals("..")) throw new IOException("Небезопасная версия Minecraft.");
            String clientHash = hash(pins.get("client_sha1").getAsString());
            boolean automatic = source == null;
            if (automatic) source = cache.resolve("no-local-source");
            Path client = source.resolve("versions").resolve(version).resolve(version + ".jar");
            if (!automatic && !HorizonInstaller.digest(client, "SHA-1").equals(clientHash))
                throw new IOException("Локальный клиентский JAR Minecraft " + version + " не совпадает с Horizon.");
            Path root = cache.resolve(clientHash);
            HorizonInstaller.secureDirectory(root);
            Path versionDir = root.resolve("versions").resolve(version);
            Path manifestFile = manifest(source, versionDir, version, pins, downloader);
            JsonObject manifest = json(manifestFile, 4 * 1024 * 1024);
            if (!manifest.get("id").getAsString().equals(version)) throw new IOException("Неверная версия Minecraft.");
            List<Item> items = new ArrayList<>();
            if (automatic) {
                JsonObject clientInfo = manifest.getAsJsonObject("downloads").getAsJsonObject("client");
                if (!hash(clientInfo.get("sha1").getAsString()).equals(clientHash)) throw new IOException("Клиент Minecraft не совпадает с research-lock.json.");
                items.add(item(versionDir.resolve(version + ".jar"), clientInfo, null));
            } else store(client, versionDir.resolve(version + ".jar"), clientHash);
            JsonObject server = manifest.getAsJsonObject("downloads").getAsJsonObject("server");
            if (!hash(server.get("sha1").getAsString()).equals(hash(pins.get("server_sha1").getAsString())))
                throw new IOException("Серверный JAR не совпадает с research-lock.json.");
            items.add(item(root.resolve("server.jar"), server, null));
            int libraries = 0;
            for (JsonElement element : manifest.getAsJsonArray("libraries")) {
                if (++libraries > 2000) throw new IOException("Слишком много библиотек Minecraft.");
                JsonObject library = element.getAsJsonObject();
                for (JsonObject artifact : libraryArtifacts(library)) {
                    String relative = artifact.get("path").getAsString();
                    items.add(item(HorizonArchive.safe(root.resolve("libraries"), relative), artifact, HorizonArchive.safe(source.resolve("libraries"), relative)));
                }
            }
            JsonObject indexInfo = manifest.getAsJsonObject("assetIndex");
            String indexId = indexInfo.get("id").getAsString();
            if (!indexId.matches("[A-Za-z0-9._-]{1,64}")) throw new IOException("Небезопасный индекс assets.");
            Path index = root.resolve("assets/indexes").resolve(indexId + ".json");
            Item indexItem = item(index, indexInfo, source.resolve("assets/indexes").resolve(indexId + ".json"));
            if (indexItem.size() > 32L * 1024 * 1024) throw new IOException("Индекс assets слишком большой.");
            progress.accept(new HorizonInstaller.Progress("Загружаем проверенный индекс assets…", 0.02f));
            obtain(indexItem, downloader);
            JsonObject objects = json(index, 32 * 1024 * 1024).getAsJsonObject("objects");
            if (objects.size() > 100000) throw new IOException("Слишком много assets.");
            Set<String> seen = new HashSet<>();
            for (var entry : objects.entrySet()) {
                JsonObject object = entry.getValue().getAsJsonObject();
                String sha1 = hash(object.get("hash").getAsString());
                if (!seen.add(sha1)) continue;
                String relative = "assets/objects/" + sha1.substring(0, 2) + "/" + sha1;
                items.add(new Item(root.resolve(relative), "https://resources.download.minecraft.net/" + sha1.substring(0, 2) + "/" + sha1, sha1, size(object.get("size").getAsLong()), source.resolve(relative)));
            }
            long total = indexItem.size();
            for (Item item : items) if ((total += item.size()) > MAX_TOTAL) throw new IOException("Ресурсы превышают лимит 6 ГБ.");
            long completed = indexItem.size();
            for (Item item : items) {
                HorizonInstaller.cancelled();
                progress.accept(new HorizonInstaller.Progress("Ресурсы Minecraft: " + (completed / 1048576) + " / " + (total / 1048576) + " МБ", 0.05f + 0.5f * completed / Math.max(1, total)));
                obtain(item, downloader);
                completed += item.size();
            }
            HorizonInputs.validate(root, pins);
            return new Inputs(root, root.resolve("server.jar"));
        } catch (RuntimeException failure) { throw new IOException("Некорректные метаданные ресурсов Minecraft.", failure); }
    }

    record Item(Path file, String url, String sha1, long size, Path local) {}
    static List<JsonObject> libraryArtifacts(JsonObject library) {
        return libraryArtifacts(library, VersionInstaller.os(), System.getProperty("os.arch", ""), System.getProperty("os.version", ""));
    }
    static List<JsonObject> libraryArtifacts(JsonObject library, String os, String architecture, String osVersion) {
        if (!VersionInstaller.allowed(library, os, architecture, osVersion) || !matchesArchitecture(library, architecture)) return List.of();
        JsonObject downloads = library.getAsJsonObject("downloads");
        if (downloads == null) return List.of();
        Map<String, JsonObject> artifacts = new LinkedHashMap<>();
        if (downloads.has("artifact")) {
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            artifacts.put(artifact.get("path").getAsString(), artifact);
        }
        if (library.has("natives") && downloads.has("classifiers")) {
            JsonObject natives = library.getAsJsonObject("natives");
            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            if (natives.has(os)) {
                String classifier = VersionInstaller.nativeClassifier(natives.get(os).getAsString(), classifiers, false, architecture);
                if (classifiers.has(classifier)) {
                    JsonObject artifact = classifiers.getAsJsonObject(classifier);
                    artifacts.put(artifact.get("path").getAsString(), artifact);
                }
            }
        }
        return List.copyOf(artifacts.values());
    }
    private static boolean matchesArchitecture(JsonObject library, String architecture) {
        String[] coordinate = library.get("name").getAsString().split(":");
        if (coordinate.length < 4) return true;
        String classifier = coordinate[coordinate.length - 1].toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("arm64") || arch.equals("aarch64") || arch.startsWith("armv");
        if (classifier.contains("arm64") || classifier.contains("aarch64") || classifier.contains("aarch_64")) return arm;
        if (classifier.equals("natives-macos")) return !arm;
        if (classifier.contains("x86_64") || classifier.contains("amd64")) return !arm;
        return true;
    }
    private static Item item(Path file, JsonObject object, Path local) throws IOException {
        String url = object.get("url").getAsString();
        trusted(url);
        return new Item(file, url, hash(object.get("sha1").getAsString()), size(object.get("size").getAsLong()), local);
    }
    private static long size(long value) throws IOException {
        if (value < 0 || value > MAX_FILE) throw new IOException("Файл ресурсов превышает лимит.");
        return value;
    }
    private static String hash(String value) throws IOException {
        if (!value.matches("[a-f0-9]{40}")) throw new IOException("Некорректная контрольная сумма Minecraft.");
        return value;
    }
    private static boolean valid(Path file, String hash, long size) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Ссылки в ресурсах запрещены.");
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == size && HorizonInstaller.digest(file, "SHA-1").equals(hash);
    }
    private static void obtain(Item item, Downloader downloader) throws IOException {
        if (valid(item.file(), item.sha1(), item.size())) return;
        if (item.local() != null && valid(item.local(), item.sha1(), item.size())) {
            store(item.local(), item.file(), item.sha1());
            return;
        }
        HorizonInstaller.secureDirectory(item.file().getParent());
        Path temp = Files.createTempFile(item.file().getParent(), ".download-", ".tmp");
        try {
            downloader.download(item.url(), temp, item.size());
            if (!valid(temp, item.sha1(), item.size())) throw new IOException("Контрольная сумма ресурса не совпадает.");
            HorizonInstaller.cancelled();
            Files.move(temp, item.file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static Path manifest(Path source, Path versionDir, String version, JsonObject pins, Downloader downloader) throws IOException {
        Path target = versionDir.resolve(version + ".json");
        Path local = source.resolve("versions").resolve(version).resolve(version + ".json");
        for (Path candidate : List.of(target, local)) {
            if (Files.isSymbolicLink(candidate)) throw new IOException("Ссылки в ресурсах запрещены.");
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            json(candidate, 4 * 1024 * 1024);
            String checksum = HorizonInstaller.digest(candidate, "SHA-1");
            if (HorizonInputs.accepted(pins, "manifest_sha1", "accepted_manifest_sha1", checksum)) {
                if (!candidate.equals(target)) store(candidate, target, checksum);
                return target;
            }
        }
        String checksum = hash(pins.get("manifest_sha1").getAsString());
        String url = "https://piston-meta.mojang.com/v1/packages/" + checksum + "/" + version + ".json";
        trusted(url);
        HorizonInstaller.secureDirectory(versionDir);
        Path temp = Files.createTempFile(versionDir, ".manifest-", ".tmp");
        try {
            downloader.download(url, temp, 4 * 1024 * 1024);
            json(temp, 4 * 1024 * 1024);
            if (!HorizonInstaller.digest(temp, "SHA-1").equals(checksum)) throw new IOException("Контрольная сумма JSON версии не совпадает.");
            HorizonInstaller.cancelled();
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { Files.deleteIfExists(temp); }
    }
    private static void store(Path source, Path target, String sha1) throws IOException {
        if (valid(target, sha1, Files.size(source))) return;
        HorizonInstaller.secureDirectory(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".copy-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            if (!HorizonInstaller.digest(temp, "SHA-1").equals(sha1)) throw new IOException("Файл Minecraft изменился при копировании.");
            HorizonInstaller.cancelled();
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static JsonObject json(Path file, long limit) throws IOException {
        HorizonInstaller.regular(file);
        if (Files.size(file) > limit) throw new IOException("JSON ресурсов слишком большой.");
        try { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
        catch (RuntimeException failure) { throw new IOException("Повреждён JSON ресурсов.", failure); }
    }
    static URI trusted(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 || uri.getQuery() != null)
                throw new IOException("Недоверенная ссылка ресурсов Minecraft.");
            return uri;
        } catch (IllegalArgumentException failure) { throw new IOException("Некорректная ссылка ресурсов.", failure); }
    }
    private static void download(String url, Path file, long limit) throws IOException {
        URI current = trusted(url);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        for (int redirects = 0; redirects < 6; redirects++) {
            HorizonInstaller.cancelled();
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Cactus-Horizon-Beta");
            try {
                int status = connection.getResponseCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Пустое перенаправление ресурсов.");
                    current = trusted(current.resolve(location).toString());
                    continue;
                }
                if (status == 401 || status == 403) throw new IOException("Ресурс требует доступа владельца: HTTP " + status + ". Авторизация Microsoft в Cactus не подключена.");
                if (status != 200) throw new IOException("Ресурсы Minecraft: HTTP " + status + ".");
                if (connection.getContentLengthLong() > limit) throw new IOException("Ресурс превышает заявленный размер.");
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(file, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        HorizonInstaller.cancelled();
                        if (System.nanoTime() > deadline || (total += read) > limit) throw new IOException("Загрузка ресурса превысила лимит времени или размера.");
                        output.write(buffer, 0, read);
                    }
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Слишком много перенаправлений ресурсов.");
    }
}
