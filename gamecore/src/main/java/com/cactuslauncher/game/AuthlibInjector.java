package com.cactuslauncher.game;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Downloads and caches the authlib-injector agent used for third-party Yggdrasil servers.
 * Each verified jar gets a {@code .sha256} sidecar; when the catalog or download is unreachable,
 * the newest cached jar whose content still matches its sidecar is used, so launches work offline.
 */
public final class AuthlibInjector {
    public record Artifact(String version, String url, String sha256) {}

    interface Resolver { Artifact resolve() throws IOException; }
    interface Downloader { void download(String url, Path target) throws IOException; }

    private final Path root;
    private final Resolver resolver;
    private final Downloader downloader;

    public AuthlibInjector(Path root) {
        this.root = root.toAbsolutePath().normalize();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.resolver = () -> resolve(client);
        this.downloader = (url, target) -> download(client, url, target);
    }

    AuthlibInjector(Path root, Resolver resolver, Downloader downloader) {
        this.root = root.toAbsolutePath().normalize();
        this.resolver = resolver;
        this.downloader = downloader;
    }

    public Artifact resolve() throws IOException { return resolver.resolve(); }

    private static Artifact resolve(HttpClient client) throws IOException {
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
        } catch (RuntimeException e) {
            throw new IOException("Некорректные метаданные authlib-injector.", e);
        }
    }

    private static void download(HttpClient client, String url, Path target) throws IOException {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) throw new IOException("Не удалось скачать authlib-injector.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка authlib-injector отменена.", e);
        }
    }

    public synchronized Path ensure() throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Папка authlib-injector не должна быть символической ссылкой.");
        Artifact artifact;
        try {
            artifact = resolver.resolve();
        } catch (IOException offline) {
            return cachedOr(offline);
        }
        Files.createDirectories(root);
        Path target = root.resolve("authlib-injector-" + artifact.version() + ".jar");
        if (Files.isSymbolicLink(target)) throw new IOException("Файл authlib-injector не должен быть символической ссылкой.");
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && hash(target).equalsIgnoreCase(artifact.sha256())) {
            remember(target, artifact.sha256());
            return target;
        }
        Path temp = Files.createTempFile(root, "injector-", ".part");
        try {
            downloader.download(artifact.url(), temp);
            if (!hash(temp).equalsIgnoreCase(artifact.sha256())) throw new IOException("Контрольная сумма authlib-injector не совпала.");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            remember(target, artifact.sha256());
            return target;
        } catch (IOException failed) {
            return cachedOr(failed);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The newest cached jar whose bytes still match the checksum recorded when it was verified. */
    Optional<Path> cached() throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var files = Files.list(root)) {
            var sidecars = files.filter(path -> path.getFileName().toString().matches("authlib-injector-[A-Za-z0-9._+-]{1,64}\\.jar\\.sha256"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(AuthlibInjector::modified).reversed())
                    .toList();
            for (Path sidecar : sidecars) {
                String name = sidecar.getFileName().toString();
                Path jar = sidecar.resolveSibling(name.substring(0, name.length() - ".sha256".length()));
                if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) continue;
                String expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
                if (expected.matches("[a-fA-F0-9]{64}") && hash(jar).equalsIgnoreCase(expected)) return Optional.of(jar);
            }
        }
        return Optional.empty();
    }

    private Path cachedOr(IOException failure) throws IOException {
        Optional<Path> cached = cached();
        if (cached.isPresent()) return cached.get();
        throw failure;
    }

    private static void remember(Path jar, String sha256) throws IOException {
        Path sidecar = jar.resolveSibling(jar.getFileName() + ".sha256");
        if (Files.isSymbolicLink(sidecar)) throw new IOException("Файл контрольной суммы не должен быть символической ссылкой.");
        String value = sha256.toLowerCase(java.util.Locale.ROOT);
        if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS) && Files.readString(sidecar).trim().equals(value)) {
            Files.setLastModifiedTime(sidecar, FileTime.fromMillis(System.currentTimeMillis()));
            return;
        }
        Files.writeString(sidecar, value + "\n", StandardCharsets.UTF_8);
    }

    private static FileTime modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS); }
        catch (IOException e) { return FileTime.fromMillis(0); }
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
