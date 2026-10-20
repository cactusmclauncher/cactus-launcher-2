package com.cactuslauncher.game;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Downloads and caches the authlib-injector agent used for third-party Yggdrasil servers. */
public final class AuthlibInjector {
    public record Artifact(String version, String url, String sha256) {}
    private final Path root;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public AuthlibInjector(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Artifact resolve() throws IOException {
        try {
            var request = HttpRequest.newBuilder(URI.create("https://authlib-injector.yushi.moe/artifact/latest.json")).timeout(Duration.ofSeconds(20)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Каталог authlib-injector недоступен.");
            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            String version = json.get("version").getAsString();
            String url = json.get("download_url").getAsString();
            String sha = json.getAsJsonObject("checksums").get("sha256").getAsString();
            URI artifactUri = URI.create(url);
            if (!version.matches("[A-Za-z0-9._+-]{1,64}") || !sha.matches("[a-fA-F0-9]{64}")
                    || !"https".equalsIgnoreCase(artifactUri.getScheme())
                    || !"authlib-injector.yushi.moe".equalsIgnoreCase(artifactUri.getHost())
                    || artifactUri.getRawQuery() != null || artifactUri.getRawFragment() != null)
                throw new IOException("Некорректные метаданные authlib-injector.");
            return new Artifact(version, url, sha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка authlib-injector отменена.", e);
        }
    }

    public synchronized Path ensure() throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Папка authlib-injector не должна быть символической ссылкой.");
        var artifact = resolve();
        Files.createDirectories(root);
        Path target = root.resolve("authlib-injector-" + artifact.version() + ".jar");
        if (Files.isSymbolicLink(target)) throw new IOException("Файл authlib-injector не должен быть символической ссылкой.");
        if (Files.isRegularFile(target) && hash(target).equalsIgnoreCase(artifact.sha256())) return target;
        Path temp = Files.createTempFile(root, "injector-", ".part");
        try {
            var request = HttpRequest.newBuilder(URI.create(artifact.url())).timeout(Duration.ofMinutes(2)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200) throw new IOException("Не удалось скачать authlib-injector.");
            if (!hash(temp).equalsIgnoreCase(artifact.sha256())) throw new IOException("Контрольная сумма authlib-injector не совпала.");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка authlib-injector отменена.", e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String hash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
