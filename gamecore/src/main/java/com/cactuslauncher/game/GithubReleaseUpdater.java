package com.cactuslauncher.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public final class GithubReleaseUpdater {
    public record Release(String currentVersion, String latestVersion, String releaseUrl,
                          String assetName, String downloadUrl, String sha256, boolean available) {}

    private static final long MAX_ASSET_BYTES = 1_500L * 1024L * 1024L;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Release check() throws IOException, InterruptedException {
        Properties build = buildInfo();
        String current = build.getProperty("version", "").trim();
        String repository = build.getProperty("repository", "").trim();
        if (!current.matches("\\d+\\.\\d+\\.\\d+") || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IOException("Автообновления доступны в сборках, опубликованных через GitHub Releases.");

        JsonObject release = JsonParser.parseString(get("https://api.github.com/repos/" + repository + "/releases/latest", "application/vnd.github+json"))
                .getAsJsonObject();
        String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        String page = release.has("html_url") ? release.get("html_url").getAsString() : "";
        if (!validReleaseUrl(page, repository)) throw new IOException("GitHub вернул некорректную ссылку на релиз.");
        if (compareVersions(latest, current) <= 0) return new Release(current, latest, page, "", "", "", false);

        JsonObject selected = null;
        JsonObject checksumAsset = null;
        for (var element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.equals("SHA256SUMS.txt")) checksumAsset = asset;
            if (selected == null && matchesPlatform(name)) selected = asset;
        }
        if (selected == null) throw new IOException("Для этой системы в релизе нет установщика.");
        String assetName = selected.get("name").getAsString();
        String url = selected.get("browser_download_url").getAsString();
        if (!validAssetUrl(url, repository, tag)) throw new IOException("GitHub вернул некорректную ссылку на установщик.");
        String hash = selected.has("digest") && !selected.get("digest").isJsonNull()
                ? selected.get("digest").getAsString().replaceFirst("(?i)^sha256:", "") : "";
        if (hash.isBlank() && checksumAsset != null) {
            String checksumUrl = checksumAsset.get("browser_download_url").getAsString();
            if (!validAssetUrl(checksumUrl, repository, tag)) throw new IOException("Некорректная ссылка на контрольные суммы.");
            hash = checksum(get(checksumUrl, "application/octet-stream"), assetName);
        }
        if (!hash.matches("(?i)[a-f0-9]{64}")) throw new IOException("В релизе нет корректной SHA-256 контрольной суммы установщика.");
        return new Release(current, latest, page, assetName, url, hash.toLowerCase(Locale.ROOT), true);
    }

    public Path download(Release release, Path updateDirectory) throws IOException, InterruptedException {
        if (release == null || !release.available() || !validFileName(release.assetName())
                || !release.sha256().matches("(?i)[a-f0-9]{64}")) throw new IOException("Данные обновления некорректны.");
        Properties build = buildInfo();
        String repository = build.getProperty("repository", "").trim();
        String tag = release.latestVersion();
        if (!validAssetUrl(release.downloadUrl(), repository, tag)) throw new IOException("Ссылка на установщик не принадлежит GitHub Releases.");
        Path directory = updateDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(directory)) throw new IOException("Папка обновлений не должна быть символической ссылкой.");
        Files.createDirectories(directory);
        Path target = directory.resolve(release.assetName()).normalize();
        if (!target.getParent().equals(directory) || Files.isSymbolicLink(target)) throw new IOException("Некорректный путь установщика.");
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) <= MAX_ASSET_BYTES
                && fileSha256(target).equalsIgnoreCase(release.sha256())) return target;
        Path temporary = Files.createTempFile(directory, ".cactus-update-", ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(release.downloadUrl())).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "CactusLauncher")
                    .header("Accept", "application/octet-stream").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("GitHub не отдал установщик (HTTP " + response.statusCode() + ").");
            }
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream input = response.body(); var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ASSET_BYTES) throw new IOException("Установщик превышает допустимый размер.");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (total == 0) throw new IOException("GitHub вернул пустой установщик.");
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(release.sha256())) throw new IOException("SHA-256 установщика не совпала. Файл удалён.");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String get(String address, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CactusLauncher")
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String message = response.body() == null ? "" : response.body();
            throw new IOException("Проверка GitHub Releases завершилась с HTTP " + response.statusCode()
                    + (message.length() > 240 ? ": " + message.substring(0, 240) : message.isBlank() ? "." : ": " + message));
        }
        return response.body();
    }

    private static Properties buildInfo() throws IOException {
        Properties result = new Properties();
        try (InputStream stream = GithubReleaseUpdater.class.getClassLoader().getResourceAsStream("cactus-build.properties")) {
            if (stream == null) throw new IOException("В эту сборку не включены данные автообновления.");
            result.load(stream);
        }
        return result;
    }

    private static boolean matchesPlatform(String name) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = architecture.equals("aarch64") || architecture.equals("arm64");
        if (os.contains("win")) return !arm && name.toLowerCase(Locale.ROOT).endsWith("-windows-x64.msi");
        if (os.contains("linux")) return !arm && name.toLowerCase(Locale.ROOT).endsWith("-linux-x64.deb");
        if (os.contains("mac") || os.contains("darwin")) {
            String archName = arm ? "arm64" : "x64";
            return name.toLowerCase(Locale.ROOT).endsWith("-macos-" + archName + ".dmg");
        }
        return false;
    }

    private static boolean validReleaseUrl(String value, String repository) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath().startsWith("/" + repository + "/releases/tag/");
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean validAssetUrl(String value, String repository, String tag) {
        try {
            URI uri = URI.create(value);
            String prefix = "/" + repository + "/releases/download/";
            String path = uri.getPath();
            return "https".equals(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getHost())
                    && (path.startsWith(prefix + tag + "/") || path.startsWith(prefix + "v" + tag + "/"));
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean validFileName(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,180}");
    }

    static String checksum(String body, String assetName) throws IOException {
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            String[] fields = trimmed.split("\\s+", 2);
            if (fields.length == 2 && fields[0].matches("(?i)[a-f0-9]{64}")
                    && fields[1].replaceFirst("^\\*", "").equals(assetName)) return fields[0];
        }
        throw new IOException("В SHA256SUMS.txt нет контрольной суммы для " + assetName + ".");
    }

    /** Compares numeric versions with 1–4 parts; missing parts count as 0, so tag "2" equals 2.0.0. */
    static int compareVersions(String left, String right) throws IOException {
        if (!left.matches("\\d+(\\.\\d+){0,3}")) throw new IOException("У релиза некорректный номер версии: " + (left.isBlank() ? "пусто" : left) + ".");
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int compare;
            try { compare = Integer.compare(index < a.length ? Integer.parseInt(a[index]) : 0, index < b.length ? Integer.parseInt(b[index]) : 0); }
            catch (NumberFormatException error) { throw new IOException("Номер версии слишком большой.", error); }
            if (compare != 0) return compare;
        }
        return 0;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IOException("SHA-256 недоступна в этой Java.", error); }
    }

    private static String fileSha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
