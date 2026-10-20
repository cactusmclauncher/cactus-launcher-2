package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Installs official Fabric profiles and Forge's official client installer into one instance. */
public final class LoaderInstaller {
    public record Progress(String stage) {}
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String FORGE_PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final long MAX_ARTIFACT = 512L * 1024 * 1024;
    private final Path root;
    private final Consumer progress;

    @FunctionalInterface public interface Consumer { void accept(Progress progress); }

    public LoaderInstaller(Path root, Consumer progress) {
        this.root = root.toAbsolutePath().normalize();
        this.progress = progress;
    }

    public String install(String loader, String gameVersion) throws IOException {
        if (loader.equalsIgnoreCase("Fabric")) return installFabric(gameVersion);
        if (loader.equalsIgnoreCase("Forge")) return installForge(gameVersion);
        if (loader.equalsIgnoreCase("Vanilla")) return gameVersion;
        throw new IOException("Этот загрузчик пока не поддерживается: " + loader);
    }

    public static boolean installed(Path root, String loader, String gameVersion) {
        if (loader.equalsIgnoreCase("Vanilla")) return VersionInstaller.installed(root, gameVersion);
        try {
            if (!VersionInstaller.installed(root, gameVersion)) return false;
            Properties marker = readMarker(root);
            if (!gameVersion.equals(marker.getProperty("minecraft")) || !loader.equalsIgnoreCase(marker.getProperty("loader"))) return false;
            String id = marker.getProperty("profile", "");
            if (!safeId(id)) return false;
            Path json = root.resolve("versions").resolve(id).resolve(id + ".json");
            if (Files.isSymbolicLink(root.resolve("versions")) || Files.isSymbolicLink(json.getParent())
                    || Files.isSymbolicLink(json) || !Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) return false;
            try (var reader = Files.newBufferedReader(json)) {
                JsonObject profile = JsonParser.parseReader(reader).getAsJsonObject();
                return gameVersion.equals(profile.has("inheritsFrom") ? profile.get("inheritsFrom").getAsString() : profile.get("id").getAsString());
            }
        } catch (Exception e) { return false; }
    }

    public static String profileId(Path root, String loader, String gameVersion) throws IOException {
        if (loader.equalsIgnoreCase("Vanilla")) return gameVersion;
        if (!installed(root, loader, gameVersion)) throw new IOException("Сначала установите загрузчик для этой сборки.");
        return readMarker(root).getProperty("profile");
    }

    private String installFabric(String gameVersion) throws IOException {
        progress.accept(new Progress("Получаем совместимые версии Fabric"));
        JsonArray versions = getJson(FABRIC_META + "/versions/loader/" + pathSegment(gameVersion)).getAsJsonArray();
        JsonObject selected = null;
        for (JsonElement value : versions) {
            JsonObject item = value.getAsJsonObject();
            if (item.has("loader") && item.getAsJsonObject("loader").has("version")
                    && item.getAsJsonObject("loader").get("version").isJsonPrimitive()
                    && item.has("loader") && (!item.has("stable") || item.get("stable").getAsBoolean())) { selected = item; break; }
        }
        if (selected == null) throw new IOException("У Fabric нет стабильного загрузчика для Minecraft " + gameVersion + ".");
        String loaderVersion = selected.getAsJsonObject("loader").get("version").getAsString();
        String url = FABRIC_META + "/versions/loader/" + pathSegment(gameVersion) + "/" + pathSegment(loaderVersion) + "/profile/json";
        JsonObject profile = getJson(url).getAsJsonObject();
        String id = required(profile, "id");
        if (!safeId(id) || !gameVersion.equals(required(profile, "inheritsFrom"))) throw new IOException("Fabric вернул профиль для другой версии Minecraft.");
        progress.accept(new Progress("Загружаем библиотеки Fabric"));
        Path staging = Files.createTempDirectory(root, ".fabric-stage-");
        try {
            downloadProfileLibraries(profile, staging);
            checkCancelled();
            Path versionDir = root.resolve("versions").resolve(id);
            ensureNoSymlinks(versionDir);
            Files.createDirectories(versionDir);
            atomicWrite(versionDir.resolve(id + ".json"), profile.toString());
            writeMarker("Fabric", gameVersion, id, loaderVersion);
            return id;
        } finally { deleteTree(staging); }
    }

    private String installForge(String gameVersion) throws IOException {
        progress.accept(new Progress("Получаем рекомендуемую версию Forge"));
        JsonObject promotions = getJson(FORGE_PROMOTIONS).getAsJsonObject().getAsJsonObject("promos");
        String forgeVersion = string(promotions, gameVersion + "-recommended");
        if (forgeVersion == null) forgeVersion = string(promotions, gameVersion + "-latest");
        if (forgeVersion == null) throw new IOException("Forge не публикует установщик для Minecraft " + gameVersion + ".");
        String id = gameVersion + "-forge-" + forgeVersion;
        if (!safeId(id)) throw new IOException("Forge вернул некорректный номер версии.");
        Path installDir = root.resolve(".forge-install");
        ensureNoSymlinks(installDir);
        ensureNoSymlinks(root.resolve("versions"));
        ensureNoSymlinks(root.resolve("libraries"));
        Files.createDirectories(installDir);
        Path installer = installDir.resolve("forge-installer.jar");
        String installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + gameVersion + "-" + forgeVersion
                + "/forge-" + gameVersion + "-" + forgeVersion + "-installer.jar";
        progress.accept(new Progress("Скачиваем официальный установщик Forge " + forgeVersion));
        downloadHashed(installerUrl, installer, sha1(installerUrl + ".sha1"));
        try {
            int javaVersion = 8;
            Path vanillaJson = root.resolve("versions").resolve(gameVersion).resolve(gameVersion + ".json");
            try (var reader = Files.newBufferedReader(vanillaJson)) {
                JsonObject vanilla = JsonParser.parseReader(reader).getAsJsonObject();
                if (vanilla.has("javaVersion")) javaVersion = vanilla.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
            }
            Path javaExec = new JavaRuntimeManager(root.getParent().getParent().resolve("runtimes"))
                    .ensure(javaVersion, message -> progress.accept(new Progress(message)));
            progress.accept(new Progress("Устанавливаем Forge " + forgeVersion));
            Path installLog = root.resolve("logs/forge-install.log");
            Files.createDirectories(installLog.getParent());
            Process process = new ProcessBuilder(javaExec.toString(), "-Djava.awt.headless=true", "-jar", installer.toString(), "--installClient", root.toString())
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(installLog.toFile()).start();
            while (!process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new InterruptedIOException("Установка Forge отменена.");
                }
            }
            if (process.exitValue() != 0) throw new IOException("Установщик Forge завершился с ошибкой. Подробности: logs/forge-install.log.");
            Path profilePath = root.resolve("versions").resolve(id).resolve(id + ".json");
            if (!Files.isRegularFile(profilePath, LinkOption.NOFOLLOW_LINKS)) profilePath = findForgeProfile(gameVersion, forgeVersion);
            if (profilePath == null || Files.isSymbolicLink(profilePath) || !Files.isRegularFile(profilePath, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Установщик Forge завершился без профиля запуска.");
            JsonObject profile;
            try (var reader = Files.newBufferedReader(profilePath)) { profile = JsonParser.parseReader(reader).getAsJsonObject(); }
            if (!gameVersion.equals(string(profile, "inheritsFrom"))) throw new IOException("Forge создал профиль для другой версии Minecraft.");
            String actualId = profilePath.getParent().getFileName().toString();
            writeMarker("Forge", gameVersion, actualId, forgeVersion);
            return actualId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Установка Forge отменена.");
        } finally { deleteTree(installDir); }
    }

    private Path findForgeProfile(String gameVersion, String forgeVersion) throws IOException {
        Path versions = root.resolve("versions");
        if (!Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(versions)) return null;
        try (var dirs = Files.list(versions)) {
            for (Path dir : dirs.toList()) {
                String id = dir.getFileName().toString();
                if (!id.contains("forge") || !id.contains(forgeVersion) || Files.isSymbolicLink(dir)) continue;
                Path json = dir.resolve(id + ".json");
                if (Files.isSymbolicLink(json) || !Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var reader = Files.newBufferedReader(json)) {
                    JsonObject profile = JsonParser.parseReader(reader).getAsJsonObject();
                    if (gameVersion.equals(string(profile, "inheritsFrom"))) return json;
                } catch (RuntimeException ignored) { }
            }
        }
        return null;
    }

    private void downloadProfileLibraries(JsonObject profile, Path staging) throws IOException {
        JsonArray libraries = profile.getAsJsonArray("libraries");
        if (libraries == null) throw new IOException("Профиль Fabric не содержит библиотек.");
        Set<Path> done = new HashSet<>();
        for (JsonElement entry : libraries) {
            checkCancelled();
            JsonObject library = entry.getAsJsonObject();
            if (!VersionInstaller.allowed(library) || library.has("downloads")) continue;
            String coordinate = required(library, "name");
            String base = library.has("url") ? library.get("url").getAsString() : "https://libraries.minecraft.net/";
            String relative = mavenPath(coordinate);
            URI baseUri = URI.create(base.endsWith("/") ? base : base + "/");
            if (!allowedMaven(baseUri.getHost())) throw new IOException("Библиотека Fabric ссылается на неизвестный Maven-репозиторий.");
            Path target = root.resolve("libraries").resolve(relative).normalize();
            if (!target.startsWith(root.resolve("libraries")) || !done.add(target)) continue;
            ensureNoSymlinks(target);
            String artifactUrl = baseUri.resolve(relative).toString();
            Path staged = staging.resolve(relative).normalize();
            if (!staged.startsWith(staging)) throw new IOException("Некорректный путь библиотеки Fabric.");
            Files.createDirectories(staged.getParent());
            downloadHashed(artifactUrl, staged, sha1(artifactUrl + ".sha1"));
            Files.createDirectories(target.getParent());
            move(staged, target);
        }
    }

    private String sha1(String url) throws IOException {
        String value = getText(url).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!value.matches("[a-f0-9]{40}")) throw new IOException("Maven не вернул корректную SHA-1 контрольную сумму.");
        return value;
    }

    private void downloadHashed(String url, Path target, String expectedHash) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Загружаемый файл не должен быть символической ссылкой.");
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !allowedMaven(uri.getHost())) throw new IOException("Загрузка с неизвестного сервера заблокирована.");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CactusLauncher/2.0");
        Path temp = Files.createTempFile(target.getParent(), "loader-", ".part");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Сервер загрузки вернул HTTP " + connection.getResponseCode() + ".");
            long size = 0;
            MessageDigest digest = digest();
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(); size += count;
                    if (size > MAX_ARTIFACT) throw new IOException("Библиотека загрузчика превышает допустимый размер.");
                    out.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            if (size == 0 || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) throw new IOException("Контрольная сумма библиотеки загрузчика не совпала.");
            Files.createDirectories(target.getParent()); move(temp, target);
        } finally { connection.disconnect(); Files.deleteIfExists(temp); }
    }

    private JsonElement getJson(String url) throws IOException { return JsonParser.parseString(getText(url)); }
    private String getText(String url) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !allowedMaven(uri.getHost())) throw new IOException("Запрос к неизвестному серверу заблокирован.");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CactusLauncher/2.0");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Сервер загрузчика вернул HTTP " + status + ".");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                if (bytes.length > 16 * 1024 * 1024) throw new IOException("Ответ сервера загрузчика слишком большой.");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }

    private void writeMarker(String loader, String game, String profile, String loaderVersion) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("loader", loader); properties.setProperty("minecraft", game);
        properties.setProperty("profile", profile); properties.setProperty("loaderVersion", loaderVersion);
        Path marker = root.resolve(".cactus-loader.properties");
        if (Files.isSymbolicLink(marker)) throw new IOException("Маркер загрузчика не должен быть символической ссылкой.");
        Path temp = Files.createTempFile(root, "loader-marker-", ".tmp");
        try (var out = Files.newOutputStream(temp)) { properties.store(out, "Cactus Launcher loader"); }
        try { move(temp, marker); } finally { Files.deleteIfExists(temp); }
    }

    private static Properties readMarker(Path root) throws IOException {
        Path marker = root.resolve(".cactus-loader.properties");
        if (Files.isSymbolicLink(marker)) throw new IOException("Некорректный маркер загрузчика.");
        Properties p = new Properties();
        try (var in = Files.newInputStream(marker)) { p.load(in); }
        return p;
    }

    private static String mavenPath(String coordinate) throws IOException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || parts.length > 4) throw new IOException("Некорректная Maven-координата загрузчика.");
        for (String part : parts) if (!part.matches("[A-Za-z0-9._+-]+")) throw new IOException("Некорректная Maven-координата загрузчика.");
        String group = parts[0].replace('.', '/'); String artifact = parts[1]; String version = parts[2];
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private static String pathSegment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static boolean safeId(String id) { return id != null && id.matches("[A-Za-z0-9._+-]{1,128}"); }
    private static String required(JsonObject o, String key) throws IOException {
        String value = string(o, key); if (value == null || value.isBlank()) throw new IOException("Профиль загрузчика не содержит " + key + "."); return value;
    }
    private static String string(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null; }
        catch (RuntimeException e) { return null; }
    }
    private static boolean allowedMaven(String host) {
        if (host == null) return false;
        return host.equals("meta.fabricmc.net") || host.equals("maven.fabricmc.net") || host.equals("maven.minecraftforge.net")
                || host.equals("libraries.minecraft.net") || host.equals("repo1.maven.org") || host.equals("repo.maven.apache.org")
                || host.equals("files.minecraftforge.net");
    }
    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static void atomicWrite(Path path, String content) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "profile-", ".tmp");
        try { Files.writeString(temp, content, StandardCharsets.UTF_8); move(temp, path); }
        finally { Files.deleteIfExists(temp); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) { for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item); }
    }
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Установка загрузчика отменена.");
    }
    private void ensureNoSymlinks(Path path) throws IOException {
        for (Path current = path; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Путь установки загрузчика содержит символическую ссылку.");
    }
}
