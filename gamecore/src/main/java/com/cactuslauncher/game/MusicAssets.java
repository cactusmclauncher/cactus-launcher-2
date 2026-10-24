package com.cactuslauncher.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Finds C418 tracks among Minecraft's own assets. Objects the builds already downloaded are reused;
 * a missing track is fetched from Mojang (resources.download.minecraft.net) into the launcher's
 * {@code cache/assets/objects}, in the same hash layout as a game's assets folder, and checked by SHA-1.
 */
public final class MusicAssets {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final long MAX_TRACK_BYTES = 32L * 1024 * 1024;
    private static final long MAX_INDEX_BYTES = 16L * 1024 * 1024;

    private final Path cache;
    private final Supplier<List<Path>> buildAssetRoots;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /**
     * Title -> object hashes, newest index first. Mojang re-encoded some tracks (Moog City 2 in 1.20.1 and in
     * 26.x are different files), so every hash a local index knows is accepted.
     */
    private Map<String, List<String>> hashes;

    /** [cache] is the launcher's assets cache (with indexes/ and objects/); [buildAssetRoots] lists each build's assets folder. */
    public MusicAssets(Path cache, Supplier<List<Path>> buildAssetRoots) {
        this.cache = cache.toAbsolutePath().normalize();
        this.buildAssetRoots = buildAssetRoots;
    }

    public Path cacheFolder() { return cache; }

    /** Forgets the indexes read so far, so builds installed since then are seen. */
    public synchronized void refresh() { hashes = null; }

    /** A verified local copy of [track], or null when no index or object for it is on disk. */
    public synchronized Path local(C418Music.Track track) {
        for (String hash : knownHashes(track)) for (Path root : roots()) {
            Path file = objectFile(root, hash);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && sha1Matches(file, hash)) return file;
        }
        return null;
    }

    /** [track] from disk, otherwise downloaded into the cache. Throws when offline or Mojang refuses. */
    public synchronized Path fetch(C418Music.Track track) throws IOException, InterruptedException {
        Path existing = local(track);
        if (existing != null) return existing;
        // The newest local index decides; with none on disk, the latest release's index is fetched first.
        List<String> known = knownHashes(track);
        if (known.isEmpty()) {
            downloadLatestIndex();
            known = knownHashes(track);
            if (known.isEmpty()) throw new IOException("Трека " + track.title() + " нет в каталоге ресурсов Minecraft.");
        }
        String hash = known.getFirst();
        Path target = objectFile(cache, hash);
        download("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, hash, MAX_TRACK_BYTES, target);
        return target;
    }

    /** Object hashes for the tracks, read from the newest asset index of the latest release (downloads it). Used by checks. */
    public synchronized Map<String, String> latestReleaseHashes() throws IOException, InterruptedException {
        Path index = downloadLatestIndex();
        var result = new HashMap<String, String>();
        JsonObject objects = readIndex(index);
        if (objects != null) for (var track : C418Music.TRACKS) {
            String hash = hashIn(objects, track);
            if (hash != null) result.put(track.title(), hash);
        }
        return result;
    }

    private List<String> knownHashes(C418Music.Track track) {
        if (hashes == null) {
            hashes = new HashMap<>();
            var seen = new java.util.HashSet<String>();
            for (Path index : indexFiles()) {
                // Builds of one version share an index: read each id once.
                if (!seen.add(index.getFileName().toString())) continue;
                JsonObject objects = readIndex(index);
                if (objects == null) continue;
                for (var other : C418Music.TRACKS) {
                    String hash = hashIn(objects, other);
                    if (hash == null) continue;
                    var list = hashes.computeIfAbsent(other.title(), key -> new ArrayList<>());
                    if (!list.contains(hash)) list.add(hash);
                }
            }
        }
        return hashes.getOrDefault(track.title(), List.of());
    }

    private static String hashIn(JsonObject objects, C418Music.Track track) {
        for (String key : List.of(track.assetKey(), track.legacyAssetKey())) {
            if (!objects.has(key) || !objects.get(key).isJsonObject()) continue;
            var entry = objects.getAsJsonObject(key);
            if (!entry.has("hash")) continue;
            String hash = entry.get("hash").getAsString();
            if (SHA1.matcher(hash).matches()) return hash;
        }
        return null;
    }

    private static JsonObject readIndex(Path index) {
        try {
            if (Files.isSymbolicLink(index) || Files.size(index) > MAX_INDEX_BYTES) return null;
            try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
                var root = JsonParser.parseReader(reader);
                return root.isJsonObject() && root.getAsJsonObject().has("objects") ? root.getAsJsonObject().getAsJsonObject("objects") : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Cache first, then builds; newest indexes first inside each root. */
    private List<Path> indexFiles() {
        var result = new ArrayList<Path>();
        for (Path root : roots()) {
            Path folder = root.resolve("indexes");
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) continue;
            var found = new ArrayList<Path>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : files) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) found.add(file);
            } catch (IOException ignored) { continue; }
            found.sort((a, b) -> Long.compare(modified(b), modified(a)));
            result.addAll(found);
        }
        return result;
    }

    private static long modified(Path file) {
        try { return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(); } catch (IOException e) { return 0; }
    }

    private List<Path> roots() {
        var result = new ArrayList<Path>();
        result.add(cache);
        try {
            for (Path root : buildAssetRoots.get()) if (root != null && !result.contains(root)) result.add(root);
        } catch (RuntimeException ignored) { }
        return result;
    }

    private static Path objectFile(Path root, String hash) {
        return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }

    private Path downloadLatestIndex() throws IOException, InterruptedException {
        JsonObject manifest = json(MANIFEST);
        String release = manifest.getAsJsonObject("latest").get("release").getAsString();
        JsonObject entry = null;
        for (var item : manifest.getAsJsonArray("versions"))
            if (item.getAsJsonObject().get("id").getAsString().equals(release)) { entry = item.getAsJsonObject(); break; }
        if (entry == null) throw new IOException("Версия " + release + " отсутствует в каталоге Minecraft.");
        JsonObject version = json(entry.get("url").getAsString());
        if (!version.has("assetIndex")) throw new IOException("У версии " + release + " нет каталога ресурсов.");
        JsonObject indexInfo = version.getAsJsonObject("assetIndex");
        String id = indexInfo.get("id").getAsString();
        String sha1 = indexInfo.get("sha1").getAsString();
        if (!id.matches("[A-Za-z0-9._-]{1,64}") || !SHA1.matcher(sha1).matches()) throw new IOException("Некорректный каталог ресурсов.");
        Path target = cache.resolve("indexes").resolve(id + ".json");
        if (!(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && sha1Matches(target, sha1)))
            download(indexInfo.get("url").getAsString(), sha1, MAX_INDEX_BYTES, target);
        hashes = null;
        return target;
    }

    private JsonObject json(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(mojang(url)).timeout(Duration.ofSeconds(20)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Mojang ответил " + response.statusCode() + ".");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void download(String url, String sha1, long limit, Path target) throws IOException, InterruptedException {
        Path folder = target.getParent();
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(target)) throw new IOException("Папка кэша не должна быть символической ссылкой.");
        Files.createDirectories(folder);
        var request = HttpRequest.newBuilder(mojang(url)).timeout(Duration.ofSeconds(60)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Path temp = folder.resolve(".download-" + UUID.randomUUID());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Mojang ответил " + response.statusCode() + ".");
            MessageDigest digest = sha1();
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (var output = Files.newOutputStream(temp)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                    if (total > limit) throw new IOException("Файл ресурса слишком большой.");
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(sha1)) throw new IOException("Контрольная сумма ресурса не совпала.");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Only Mojang hosts over HTTPS: URLs come from downloaded JSON. */
    private static URI mojang(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!"https".equals(uri.getScheme()) || !(host.equals("resources.download.minecraft.net") || host.endsWith(".mojang.com")))
            throw new IOException("Недопустимый адрес загрузки ресурса.");
        return uri;
    }

    static boolean sha1Matches(Path file, String expected) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = sha1();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest()).equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest sha1() {
        try { return MessageDigest.getInstance("SHA-1"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
