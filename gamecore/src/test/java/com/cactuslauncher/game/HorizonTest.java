package com.cactuslauncher.game;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class HorizonTest {
    @TempDir Path temporary;
    Path temp;
    @BeforeEach void realPath() throws IOException { temp = temporary.toRealPath(); }

    @Test void selectsOnlyActualGameArchivesForSupportedPlatforms() throws Exception {
        assertEquals("horizon-macos-arm64.zip", HorizonInstaller.platformAsset("Mac OS X", "aarch64"));
        assertEquals("horizon-macos-x64.zip", HorizonInstaller.platformAsset("Mac OS X", "x86_64"));
        assertEquals("horizon-windows-x64.zip", HorizonInstaller.platformAsset("Windows 11", "amd64"));
        assertEquals("horizon-linux-x86_64.tar.gz", HorizonInstaller.platformAsset("Linux", "x86_64"));
        assertThrows(IOException.class, () -> HorizonInstaller.platformAsset("Linux", "aarch64"));
        assertThrows(IOException.class, () -> HorizonInstaller.platformAsset("Windows 11", "x86"));
    }

    @Test void releaseSelectionRequiresNightlyGameAndSignedList() throws Exception {
        JsonObject release = release("horizon-macos-arm64.zip", 123);
        assertEquals("nightly", HorizonInstaller.select(release, "horizon-macos-arm64.zip").tag());
        assertThrows(IOException.class, () -> HorizonInstaller.select(release, "horizon-macos-x64.zip"));
        release.addProperty("tag_name", "launcher@0.2.37");
        assertThrows(IOException.class, () -> HorizonInstaller.select(release, "horizon-macos-arm64.zip"));
        release.addProperty("tag_name", "nightly");
        release.getAsJsonArray("assets").remove(2);
        assertThrows(IOException.class, () -> HorizonInstaller.select(release, "horizon-macos-arm64.zip"));
    }

    @Test void rejectsForeignUrlAndOversizedReleaseAsset() throws Exception {
        JsonObject release = release("horizon-macos-arm64.zip", 123);
        JsonObject asset = release.getAsJsonArray("assets").get(0).getAsJsonObject();
        asset.addProperty("browser_download_url", "https://example.com/horizon-macos-arm64.zip");
        assertThrows(IOException.class, () -> HorizonInstaller.select(release, "horizon-macos-arm64.zip"));
        asset.addProperty("browser_download_url", url("horizon-macos-arm64.zip"));
        asset.addProperty("size", Long.MAX_VALUE);
        assertThrows(IOException.class, () -> HorizonInstaller.select(release, "horizon-macos-arm64.zip"));
    }

    @Test void signatureBindsTagAndExactChecksumBytes() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] sums = bytes("a".repeat(64) + "  game.zip\n");
        String signature = sign(key, sums);
        assertEquals("a".repeat(64), HorizonInstaller.verifiedChecksum("nightly", sums, signature, "game.zip", publicKey(key)));
        assertThrows(IOException.class, () -> HorizonInstaller.verifiedChecksum("nightly-other", sums, signature, "game.zip", publicKey(key)));
        assertThrows(IOException.class, () -> HorizonInstaller.verifiedChecksum("nightly", bytes("b" + new String(sums, StandardCharsets.UTF_8).substring(1)), signature, "game.zip", publicKey(key)));
        assertThrows(IOException.class, () -> HorizonInstaller.verifiedChecksum("nightly", sums, signature, "missing.zip", publicKey(key)));
        assertThrows(IOException.class, () -> HorizonInstaller.verifiedChecksum("nightly", sums, "not-base64", "game.zip", publicKey(key)));
    }

    @Test void rejectsSignedDuplicateChecksums() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] sums = bytes(("a".repeat(64) + "  game.zip\n").repeat(2));
        assertThrows(IOException.class, () -> HorizonInstaller.verifiedChecksum("nightly", sums, sign(key, sums), "game.zip", publicKey(key)));
    }

    @Test void rejectsTraversalAndPlatformAmbiguousPaths() throws Exception {
        for (String name : List.of("../outside", "root/../../outside", "/absolute", "C:/file", "root\\file", "root/NUL.txt", "root/file.", "root/file "))
            assertThrows(IOException.class, () -> HorizonArchive.safe(temp, name), name);
        assertEquals(temp.resolve("root/file"), HorizonArchive.safe(temp, "root/file"));
    }

    @Test void zipRejectsTraversalAndCaseCollisions() throws Exception {
        Path archive = temp.resolve("bad.zip");
        Files.write(archive, zip(Map.of("../outside", bytes("bad"))));
        assertThrows(IOException.class, () -> HorizonArchive.extract(archive, temp.resolve("out1"), false));
        assertFalse(Files.exists(temp.resolve("outside")));
        Files.write(archive, zip(Map.of("root/FILE", bytes("a"), "root/file", bytes("b"))));
        assertThrows(IOException.class, () -> HorizonArchive.extract(archive, temp.resolve("out2"), false));
    }

    @Test void zipRejectsSymlinkUnixModeBeforeWritingFiles() throws Exception {
        byte[] archive = zip(Map.of("root/link", bytes("../../outside")));
        for (int i = 0; i < archive.length - 46; i++) {
            if (archive[i] == 0x50 && archive[i + 1] == 0x4b && archive[i + 2] == 1 && archive[i + 3] == 2) {
                archive[i + 5] = 3;
                archive[i + 40] = (byte) 0xff;
                archive[i + 41] = (byte) 0xa1;
                break;
            }
        }
        Path file = temp.resolve("symlink.zip");
        Files.write(file, archive);
        assertThrows(IOException.class, () -> HorizonArchive.extract(file, temp.resolve("out"), false));
        assertFalse(Files.exists(temp.resolve("out/root/link")));
    }

    @Test void tarRejectsLinksAndTruncatedEnd() throws Exception {
        Path file = temp.resolve("bad.tar.gz");
        Files.write(file, tar(Map.of("root/link", bytes("bad")), '2'));
        assertThrows(IOException.class, () -> HorizonArchive.extract(file, temp.resolve("out1"), true));
        Files.write(file, tar(Map.of("root/link", bytes("bad")), '1'));
        assertThrows(IOException.class, () -> HorizonArchive.extract(file, temp.resolve("out2"), true));
        byte[] valid = tar(Map.of("root/file", bytes("okay")), '0');
        Files.write(file, Arrays.copyOf(valid, valid.length - 7));
        assertThrows(IOException.class, () -> HorizonArchive.extract(file, temp.resolve("out3"), true));
    }

    @Test void tarAndZipExtractRegularFilesWithoutExecuting() throws Exception {
        Map<String, byte[]> files = Map.of("root/folder/file", bytes("fixture only"));
        Path archive = temp.resolve("files.zip");
        Files.write(archive, zip(files));
        HorizonArchive.extract(archive, temp.resolve("zip"), false);
        assertEquals("fixture only", Files.readString(temp.resolve("zip/root/folder/file")));
        Files.write(archive, tar(files, '0'));
        HorizonArchive.extract(archive, temp.resolve("tar"), true);
        assertEquals("fixture only", Files.readString(temp.resolve("tar/root/folder/file")));
    }

    @Test void refusesExistingSymlinkInDestination() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.createSymbolicLink(root.resolve("link"), temp);
        assertThrows(IOException.class, () -> HorizonArchive.safe(root, "link/outside"));
    }

    @Test void installsAtomicBundleAndReopensUnpreparedMetadataOffline() throws Exception {
        Fixture fixture = new Fixture(bundle(new JsonObject()));
        Path directory = temp.resolve("horizon");
        var installer = fixture.installer(directory);
        List<HorizonInstaller.Progress> progress = new ArrayList<>();
        var installation = installer.install(progress::add);
        assertEquals("26.3", installation.minecraft());
        assertEquals(25, installation.javaMajor());
        assertEquals(installation, new HorizonInstaller(directory).installed());
        assertEquals(1f, progress.getLast().fraction());
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i).fraction() >= progress.get(i - 1).fraction());
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(installation.path()).resolve("cactus-install.properties"))) { metadata.load(input); }
        assertEquals("false", metadata.getProperty("prepared"));
        assertFalse(Files.exists(Path.of(installation.path()).resolve("resources")));
        assertNoStaging(directory);
    }

    @Test void rejectsDamagedExporterAfterReopeningAndPreservesMetadata() throws Exception {
        Path directory = temp.resolve("horizon");
        var installation = new Fixture(bundle(new JsonObject())).installer(directory).install(p -> {});
        Path metadata = Path.of(installation.path()).resolve("cactus-install.properties");
        byte[] before = Files.readAllBytes(metadata);
        Files.writeString(Path.of(installation.path()).resolve("exporters/manifest.json"), "damaged");
        assertThrows(IOException.class, () -> new HorizonInstaller(directory).installed());
        assertArrayEquals(before, Files.readAllBytes(metadata));
    }

    @Test void failedUpdateKeepsPreviousInstallationAndRemovesStaging() throws Exception {
        Path directory = temp.resolve("horizon");
        var previous = new Fixture(bundle(new JsonObject())).installer(directory).install(p -> {});
        Fixture bad = new Fixture(Map.of("root/other", bytes("incomplete bundle")));
        assertThrows(IOException.class, () -> bad.installer(directory).install(p -> {}));
        assertEquals(previous, new HorizonInstaller(directory).installed());
        assertNoStaging(directory);
    }

    @Test void interruptedInstallationPropagatesAndNeverPublishesSuccess() throws Exception {
        Path directory = temp.resolve("horizon");
        var installer = new Fixture(bundle(new JsonObject())).installer(directory);
        List<Float> progress = new ArrayList<>();
        try {
            assertThrows(InterruptedIOException.class, () -> installer.install(p -> {
                progress.add(p.fraction());
                if (p.fraction() >= 0.82f) Thread.currentThread().interrupt();
            }));
        } finally { Thread.interrupted(); }
        assertNull(new HorizonInstaller(directory).installed());
        assertFalse(progress.contains(1f));
        assertNoStaging(directory);
    }

    @Test void badArchiveDigestDoesNotPublish() throws Exception {
        Fixture fixture = new Fixture(bundle(new JsonObject()));
        fixture.archive[0] ^= 1;
        Path directory = temp.resolve("horizon");
        assertThrows(IOException.class, () -> fixture.installer(directory).install(p -> {}));
        assertNull(new HorizonInstaller(directory).installed());
        assertNoStaging(directory);
    }

    @Test void corruptPointerDoesNotGetReset() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("horizon"));
        Files.writeString(directory.resolve("current"), "../outside");
        assertThrows(IOException.class, () -> new HorizonInstaller(directory).installed());
        assertEquals("../outside", Files.readString(directory.resolve("current")));
    }

    @Test void validatesOwnedLocalInputsAndPersistsOnlyPathWithoutPreparing() throws Exception {
        Path source = temp.resolve("minecraft");
        JsonObject pins = minecraft(source);
        Path directory = temp.resolve("horizon");
        var installer = new Fixture(bundle(pins)).installer(directory);
        var installation = installer.install(p -> {});
        byte[] original = Files.readAllBytes(source.resolve("versions/26.3/26.3.jar"));
        String status = installer.validateMinecraft(source.toString());
        assertTrue(status.contains("не подготовка ресурсов"));
        assertEquals(source.toString(), new HorizonInstaller(directory).installed().minecraftPath());
        assertArrayEquals(original, Files.readAllBytes(source.resolve("versions/26.3/26.3.jar")));
        assertFalse(Files.exists(Path.of(installation.path()).resolve("resources")));
    }

    @Test void wrongManifestAndMissingAssetsFailWithoutSavingSource() throws Exception {
        Path source = temp.resolve("minecraft");
        JsonObject pins = minecraft(source);
        var installer = new Fixture(bundle(pins)).installer(temp.resolve("horizon"));
        installer.install(p -> {});
        Files.writeString(source.resolve("versions/26.3/26.3.json"), "{}");
        assertThrows(IOException.class, () -> installer.validateMinecraft(source.toString()));
        assertFalse(Files.exists(temp.resolve("horizon/minecraft-path")));
        minecraft(source);
        try (var files = Files.walk(source.resolve("assets/objects"))) {
            for (Path path : files.filter(Files::isRegularFile).toList()) Files.delete(path);
        }
        assertThrows(IOException.class, () -> installer.validateMinecraft(source.toString()));
        assertFalse(Files.exists(temp.resolve("horizon/minecraft-path")));
    }

    @Test void updateCheckDoesNotInstallBeforeUserChoosesFirstInstallation() throws Exception {
        Path directory = temp.resolve("horizon");
        var update = new Fixture(bundle(new JsonObject())).installer(directory).checkUpdate(true, p -> {});
        assertNull(update.installation());
        assertTrue(update.available());
        assertFalse(Files.exists(directory.resolve("current")));
    }

    @Test void mutableNightlyUsesArchiveHashAndSupportsRollback() throws Exception {
        Path directory = temp.resolve("horizon");
        Fixture first = new Fixture(bundle(new JsonObject()));
        var previous = first.installer(directory).install(p -> {});
        Map<String, byte[]> changed = new HashMap<>(bundle(new JsonObject()));
        changed.put("root/" + HorizonInstaller.binary("horizon-client"), bytes("new fixture, never executable"));
        Fixture second = new Fixture(changed);
        var check = second.installer(directory).checkUpdate(false, p -> {});
        assertTrue(check.available());
        assertEquals(previous, new HorizonInstaller(directory).installed());
        var update = second.installer(directory).checkUpdate(true, p -> {});
        assertNotEquals(previous.path(), update.installation().path());
        assertEquals(previous.release(), update.installation().release());
        assertEquals(previous, new HorizonInstaller(directory).rollback());
        assertTrue(Files.exists(Path.of(update.installation().path())));
    }

    @Test void offlineCheckKeepsCachedInstallationAndPropagatesRealCancellation() throws Exception {
        Path directory = temp.resolve("horizon");
        var previous = new Fixture(bundle(new JsonObject())).installer(directory).install(p -> {});
        var offline = new HorizonInstaller(directory, (u, l, d, p) -> { throw new IOException("fixture offline"); }, "");
        var result = offline.checkUpdate(true, p -> {});
        assertEquals(previous, result.installation());
        assertEquals("", result.latest());
        assertTrue(result.status().contains("сохранена"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> offline.checkUpdate(true, p -> {}));
        } finally { Thread.interrupted(); }
    }

    @Test void failedAutomaticUpdatePreservesPreviousPointer() throws Exception {
        Path directory = temp.resolve("horizon");
        var previous = new Fixture(bundle(new JsonObject())).installer(directory).install(p -> {});
        Fixture broken = new Fixture(Map.of("root/missing", bytes("not a complete bundle")));
        var result = broken.installer(directory).checkUpdate(true, p -> {});
        assertEquals(previous, result.installation());
        assertEquals(previous, new HorizonInstaller(directory).installed());
        assertTrue(result.status().contains("не выполнены"));
        assertNoStaging(directory);
    }

    @Test void currentArchiveIsNotDownloadedAgain() throws Exception {
        Path directory = temp.resolve("horizon");
        Fixture fixture = new Fixture(bundle(new JsonObject()));
        var previous = fixture.installer(directory).install(p -> {});
        fixture.archive[0] ^= 1;
        assertEquals(previous, fixture.installer(directory).install(p -> {}));
        assertFalse(fixture.installer(directory).checkUpdate(true, p -> {}).available());
    }

    private void assertNoStaging(Path directory) throws IOException {
        try (var paths = Files.list(directory)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".staging-"))); }
    }

    private static String url(String name) { return "https://github.com/horizon-rust/horizon/releases/download/nightly/" + name; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] bytes, String algorithm) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
    private static String publicKey(KeyPair key) { byte[] encoded = key.getPublic().getEncoded(); return Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)); }
    private static String sign(KeyPair key, byte[] sums) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(bytes("horizon release nightly\n"));
        signature.update(sums);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static JsonObject release(String game, long size) {
        JsonObject release = new JsonObject();
        release.addProperty("tag_name", "nightly");
        release.addProperty("name", "Nightly fixture · never run");
        release.addProperty("draft", false);
        JsonArray assets = new JsonArray();
        for (String name : List.of(game, "SHA256SUMS", "SHA256SUMS.sig", "horizon-launcher-macos-arm64.zip")) {
            JsonObject asset = new JsonObject();
            asset.addProperty("name", name);
            asset.addProperty("size", size);
            asset.addProperty("state", "uploaded");
            asset.addProperty("browser_download_url", url(name));
            assets.add(asset);
        }
        release.add("assets", assets);
        return release;
    }

    static Map<String, byte[]> bundle(JsonObject pins) {
        pins = pins.deepCopy();
        pins.addProperty("version", "26.3");
        pins.addProperty("java_major", 25);
        JsonObject lock = new JsonObject();
        lock.add("minecraft", pins);
        return Map.of("root/" + HorizonInstaller.binary("horizon-client"), bytes("fixture, not executable"),
                "root/" + HorizonInstaller.binary("horizon-prepare"), bytes("fixture, not executable"),
                "root/research-lock.json", bytes(lock.toString()), "root/exporters/manifest.json", bytes("{}"));
    }

    private static JsonObject minecraft(Path root) throws Exception {
        byte[] client = bytes("user-owned fixture jar"), sound = bytes("sound fixture");
        String soundHash = hash(sound, "SHA-1");
        JsonObject index = JsonParser.parseString("{\"objects\":{\"sound\":{\"hash\":\"" + soundHash + "\",\"size\":" + sound.length + "}}}").getAsJsonObject();
        byte[] indexBytes = bytes(index.toString());
        JsonObject manifest = JsonParser.parseString("{\"id\":\"26.3\",\"libraries\":[],\"assetIndex\":{\"id\":\"34\",\"sha1\":\"" + hash(indexBytes, "SHA-1") + "\"}}").getAsJsonObject();
        byte[] manifestBytes = bytes(manifest.toString());
        for (var entry : Map.of("versions/26.3/26.3.json", manifestBytes, "versions/26.3/26.3.jar", client,
                "assets/indexes/34.json", indexBytes, "assets/objects/" + soundHash.substring(0, 2) + "/" + soundHash, sound).entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        JsonObject pins = new JsonObject();
        pins.addProperty("manifest_sha1", hash(manifestBytes, "SHA-1"));
        pins.addProperty("client_sha1", hash(client, "SHA-1"));
        return pins;
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] tar(Map<String, byte[]> files, char type) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                byte[] header = new byte[512];
                byte[] name = bytes(entry.getKey());
                System.arraycopy(name, 0, header, 0, name.length);
                byte[] size = bytes(String.format(Locale.ROOT, "%011o", entry.getValue().length));
                System.arraycopy(size, 0, header, 124, size.length);
                Arrays.fill(header, 148, 156, (byte) ' ');
                header[156] = (byte) type;
                int checksum = 0;
                for (byte b : header) checksum += b & 255;
                byte[] sum = bytes(String.format(Locale.ROOT, "%06o", checksum));
                System.arraycopy(sum, 0, header, 148, sum.length);
                header[154] = 0;
                output.write(header);
                output.write(entry.getValue());
                output.write(new byte[(512 - entry.getValue().length % 512) % 512]);
            }
            output.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    static final class Fixture {
        final String asset;
        final KeyPair key;
        final byte[] archive;
        final byte[] sums;
        final String signature;
        Fixture(Map<String, byte[]> files) throws Exception {
            asset = HorizonInstaller.platformAsset(System.getProperty("os.name"), System.getProperty("os.arch"));
            archive = asset.endsWith(".tar.gz") ? tar(files, '0') : zip(files);
            key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            sums = bytes(hash(archive, "SHA-256") + "  " + asset + "\n");
            signature = sign(key, sums);
        }
        HorizonInstaller installer(Path directory) { return installer(directory, new HorizonRuntime()); }
        HorizonInstaller installer(Path directory, HorizonRuntime runtime) {
            return new HorizonInstaller(directory, (url, limit, destination, progress) -> {
                if (destination != null) {
                    Files.write(destination, archive, StandardOpenOption.CREATE_NEW);
                    if (progress != null) progress.accept(1f);
                    return new byte[0];
                }
                if (url.endsWith("SHA256SUMS.sig")) return bytes(signature);
                if (url.endsWith("SHA256SUMS")) return sums;
                if (url.equals("https://api.github.com/repos/horizon-rust/horizon/releases/tags/nightly")) return bytes(release(asset, archive.length).toString());
                throw new AssertionError("Unexpected fixture request");
            }, publicKey(key), runtime);
        }
    }
}
