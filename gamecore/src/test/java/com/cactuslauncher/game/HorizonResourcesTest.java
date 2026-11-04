package com.cactuslauncher.game;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HorizonResourcesTest {
    @TempDir Path temporary;
    Path root;
    @BeforeEach void realPath() throws IOException { root = temporary.toRealPath(); }

    @Test void downloadsAndValidatesTheCurrentPlatformsNativeLibrary() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        String os = VersionInstaller.os();
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = architecture.equals("arm64") || architecture.equals("aarch64");
        String classifier = os.equals("osx") ? arm ? "natives-macos-arm64" : "natives-macos"
                : os.equals("windows") ? "natives-windows" : "natives-linux";
        String path = "com/mojang/jtracy/1.14.38/jtracy-1.14.38-" + classifier + ".jar";
        String url = "https://libraries.minecraft.net/" + path;
        byte[] nativeBytes = bytes("platform native fixture");
        JsonObject artifact = artifact(nativeBytes, url);
        artifact.addProperty("path", path);
        JsonObject library = new JsonObject();
        library.addProperty("name", "com.mojang:jtracy:1.14.38:" + classifier);
        JsonObject rule = new JsonObject();
        rule.addProperty("action", "allow");
        JsonObject osRule = new JsonObject();
        osRule.addProperty("name", os);
        rule.add("os", osRule);
        JsonArray rules = new JsonArray();
        rules.add(rule);
        library.add("rules", rules);
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        library.add("downloads", downloads);

        JsonObject manifest = JsonParser.parseString(new String(fixture.manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonArray("libraries").add(library);
        byte[] manifestBytes = bytes(manifest.toString());
        Files.write(fixture.source.resolve("versions/26.3/26.3.json"), manifestBytes);
        fixture.pins.addProperty("manifest_sha1", sha1(manifestBytes));
        fixture.data.put(url, nativeBytes);
        List<String> downloadsSeen = new ArrayList<>();

        HorizonResources.Inputs inputs = HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (requestedUrl, file, limit) -> {
            downloadsSeen.add(requestedUrl);
            Files.write(file, fixture.data.get(requestedUrl));
        });

        assertTrue(downloadsSeen.contains(url));
        assertArrayEquals(nativeBytes, Files.readAllBytes(inputs.minecraft().resolve("libraries").resolve(path)));
        HorizonInputs.validate(inputs.minecraft(), fixture.pins);
    }

    @Test void selectsAllowedPlatformAndArchitectureLibraryArtifacts() {
        JsonObject library = new JsonObject();
        library.addProperty("name", "com.mojang:jtracy:1.14.38:natives-macos-arm64");
        JsonObject rule = new JsonObject();
        rule.addProperty("action", "allow");
        JsonObject os = new JsonObject();
        os.addProperty("name", "osx");
        rule.add("os", os);
        library.add("rules", new JsonArray());
        library.getAsJsonArray("rules").add(rule);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", "com/mojang/jtracy/1.14.38/jtracy-1.14.38-natives-macos-arm64.jar");
        JsonObject downloads = new JsonObject();
        downloads.add("artifact", artifact);
        library.add("downloads", downloads);

        assertEquals(List.of(artifact), HorizonResources.libraryArtifacts(library, "osx", "aarch64", "14.0"));
        assertTrue(HorizonResources.libraryArtifacts(library, "osx", "x86_64", "14.0").isEmpty());
        assertTrue(HorizonResources.libraryArtifacts(library, "linux", "aarch64", "6.0").isEmpty());

        library.addProperty("name", "com.mojang:jtracy:1.14.38:natives-macos");
        artifact.addProperty("path", "com/mojang/jtracy/1.14.38/jtracy-1.14.38-natives-macos.jar");
        assertTrue(HorizonResources.libraryArtifacts(library, "osx", "aarch64", "14.0").isEmpty());
        assertEquals(List.of(artifact), HorizonResources.libraryArtifacts(library, "osx", "x86_64", "14.0"));
    }

    @Test void resolvesPlatformNativeClassifierFromMojangLibraryMetadata() {
        JsonObject library = new JsonObject();
        library.addProperty("name", "org.lwjgl:lwjgl:3.3.3");
        JsonObject natives = new JsonObject();
        natives.addProperty("osx", "natives-macos-${arch}");
        library.add("natives", natives);
        JsonObject armArtifact = new JsonObject();
        armArtifact.addProperty("path", "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar");
        JsonObject classifiers = new JsonObject();
        classifiers.add("natives-macos-arm64", armArtifact);
        JsonObject downloads = new JsonObject();
        downloads.add("classifiers", classifiers);
        library.add("downloads", downloads);

        assertEquals(List.of(armArtifact), HorizonResources.libraryArtifacts(library, "osx", "arm64", "14.0"));
        assertTrue(HorizonResources.libraryArtifacts(library, "osx", "x86_64", "14.0").isEmpty());
    }

    @Test void fillsPublicResourcesAndReusesVerifiedCacheOfflineWithoutChangingSource() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        List<String> downloads = new ArrayList<>();
        HorizonResources.Inputs inputs = HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            downloads.add(url);
            assertEquals(fixture.data.get(url).length, limit);
            Files.write(file, fixture.data.get(url));
        });
        assertEquals(4, downloads.size());
        assertArrayEquals(fixture.client, Files.readAllBytes(inputs.minecraft().resolve("versions/26.3/26.3.jar")));
        assertArrayEquals(fixture.manifest, Files.readAllBytes(fixture.source.resolve("versions/26.3/26.3.json")));
        assertFalse(Files.exists(fixture.source.resolve("assets")));
        assertEquals(inputs, HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (u, f, l) -> { throw new IOException("offline"); }));
        HorizonInputs.validate(inputs.minecraft(), fixture.pins);
    }

    @Test void missingPublicVersionJsonIsDownloadedOnlyAfterMatchingOwnClient() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        Files.delete(fixture.source.resolve("versions/26.3/26.3.json"));
        String manifestUrl = "https://piston-meta.mojang.com/v1/packages/" + sha1(fixture.manifest) + "/26.3.json";
        List<String> requests = new ArrayList<>();
        var inputs = HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            requests.add(url);
            Files.write(file, url.equals(manifestUrl) ? fixture.manifest : fixture.data.get(url));
        });
        assertEquals(manifestUrl, requests.getFirst());
        assertEquals(5, requests.size());
        assertFalse(Files.exists(fixture.source.resolve("versions/26.3/26.3.json")));
        assertArrayEquals(fixture.manifest, Files.readAllBytes(inputs.minecraft().resolve("versions/26.3/26.3.json")));
        HorizonInputs.validate(inputs.minecraft(), fixture.pins);
    }

    @Test void corruptResourceIsNotPublishedAndCompletedFilesSurviveRetry() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        assertThrows(IOException.class, () -> HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            Files.write(file, url.contains("resources.download.minecraft.net") ? new byte[(int) limit] : fixture.data.get(url));
        }));
        Path cache = root.resolve("cache").resolve(sha1(fixture.client));
        assertTrue(Files.exists(cache.resolve("server.jar")));
        String assetHash = sha1(fixture.sound);
        assertFalse(Files.exists(cache.resolve("assets/objects").resolve(assetHash.substring(0, 2)).resolve(assetHash)));
        List<String> retry = new ArrayList<>();
        HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            retry.add(url);
            Files.write(file, fixture.data.get(url));
        });
        assertEquals(1, retry.size());
    }

    @Test void requiresMatchingLocalClientBeforeAnyResourceDownloads() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        Files.writeString(fixture.source.resolve("versions/26.3/26.3.jar"), "wrong jar");
        assertThrows(IOException.class, () -> HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (u, f, l) -> fail("must not download")));
    }

    @Test void automaticallyDownloadsClientAndAllDependenciesThenReusesCacheWithoutSourceOrNetwork() throws Exception {
        Fixture fixture = new Fixture(root.resolve("unused-source"));
        String manifestUrl = "https://piston-meta.mojang.com/v1/packages/" + sha1(fixture.manifest) + "/26.3.json";
        List<String> requests = new ArrayList<>();
        var inputs = HorizonResources.acquire(null, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            requests.add(url);
            Files.write(file, url.equals(manifestUrl) ? fixture.manifest : fixture.data.get(url));
        });
        assertEquals(6, requests.size());
        assertTrue(requests.contains("https://piston-data.mojang.com/client.jar"));
        assertArrayEquals(fixture.client, Files.readAllBytes(inputs.minecraft().resolve("versions/26.3/26.3.jar")));
        HorizonInputs.validate(inputs.minecraft(), fixture.pins);
        assertEquals(inputs, HorizonResources.acquire(null, root.resolve("cache"), fixture.pins, p -> {}, (u, f, l) -> fail("offline cache must not download")));
    }

    @Test void automaticClientMustMatchPinnedChecksumAndNeverPublishesCorruption() throws Exception {
        Fixture fixture = new Fixture(root.resolve("unused-source"));
        String manifestUrl = "https://piston-meta.mojang.com/v1/packages/" + sha1(fixture.manifest) + "/26.3.json";
        assertThrows(IOException.class, () -> HorizonResources.acquire(null, root.resolve("cache"), fixture.pins, p -> {}, (url, file, limit) -> {
            Files.write(file, url.equals(manifestUrl) ? fixture.manifest : url.endsWith("client.jar") ? new byte[(int) limit] : fixture.data.get(url));
        }));
        assertFalse(Files.exists(root.resolve("cache").resolve(sha1(fixture.client)).resolve("versions/26.3/26.3.jar")));
        fixture.pins.addProperty("client_sha1", "a".repeat(40));
        List<String> requests = new ArrayList<>();
        assertThrows(IOException.class, () -> HorizonResources.acquire(null, root.resolve("mismatch"), fixture.pins, p -> {}, (url, file, limit) -> {
            requests.add(url); Files.write(file, fixture.manifest);
        }));
        assertEquals(List.of(manifestUrl), requests);
    }

    @Test void javaPreparationRequestsPinnedJdkOrUsesExplicitHomeWithoutDownloading() throws Exception {
        Path home = Files.createDirectories(root.resolve("portable-java/Contents/Home"));
        Path executable = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(executable, "fixture, never run");
        List<Integer> requested = new ArrayList<>();
        assertEquals(home, HorizonPreparation.javaHome("", 25, (major, progress) -> {
            requested.add(major); return executable;
        }, p -> {}));
        assertEquals(List.of(25), requested);
        assertEquals(home, HorizonPreparation.javaHome(home.toString(), 25, (major, progress) -> { throw new AssertionError("explicit Java"); }, p -> {}));
    }

    @Test void rejectsUntrustedResourceHostsCredentialsPortsAndSchemes() {
        for (String url : List.of("http://piston-data.mojang.com/file", "https://example.com/file", "https://localhost/file", "https://piston-data.mojang.com:443/file",
                "https://user@piston-data.mojang.com/file", "https://piston-data.mojang.com/file#fragment", "https://piston-data.mojang.com/file?token=x"))
            assertThrows(IOException.class, () -> HorizonResources.trusted(url));
        assertDoesNotThrow(() -> HorizonResources.trusted("https://resources.download.minecraft.net/ab/" + "a".repeat(40)));
    }

    @Test void cancellationDoesNotDownloadOrPublishPreparation() throws Exception {
        Fixture fixture = new Fixture(root.resolve("source"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> HorizonResources.acquire(fixture.source, root.resolve("cache"), fixture.pins, p -> {}, (u, f, l) -> fail("cancelled")));
        } finally { Thread.interrupted(); }
        assertFalse(HorizonPreparation.prepared(root));
    }

    @Test void launchApiMustMatchPinsAndRequiredCommands() throws Exception {
        JsonObject pins = JsonParser.parseString("{\"version\":\"26.3\",\"java_major\":25}").getAsJsonObject();
        JsonObject api = JsonParser.parseString("{\"launch_api\":\"0.1.0\",\"minecraft\":\"26.3\",\"java_major\":25,\"options\":[]}").getAsJsonObject();
        for (String name : List.of("--prepare", "--root", "--output", "--complete", "--check", "--progress", "--minecraft-dir", "--server-jar", "--java-home")) {
            JsonObject option = new JsonObject(); option.addProperty("name", name); api.getAsJsonArray("options").add(option);
        }
        HorizonPreparation.validateApi(api.toString(), pins);
        api.addProperty("launch_api", "2.0.0");
        assertThrows(IOException.class, () -> HorizonPreparation.validateApi(api.toString(), pins));
        api.addProperty("launch_api", "0.1.0");
        api.addProperty("java_major", 21);
        assertThrows(IOException.class, () -> HorizonPreparation.validateApi(api.toString(), pins));
        api.addProperty("java_major", 25);
        api.getAsJsonArray("options").remove(0);
        assertThrows(IOException.class, () -> HorizonPreparation.validateApi(api.toString(), pins));
    }

    @Test void resourcePointerRejectsTraversalAndSymlinks() throws Exception {
        Files.writeString(root.resolve("cactus-resources"), "../outside");
        assertThrows(IOException.class, () -> HorizonPreparation.prepared(root));
        Files.delete(root.resolve("cactus-resources"));
        Files.createSymbolicLink(root.resolve("cactus-resources"), root.resolve("outside"));
        assertThrows(IOException.class, () -> HorizonPreparation.prepared(root));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String sha1(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); }
    private static JsonObject artifact(byte[] data, String url) throws Exception {
        JsonObject item = new JsonObject(); item.addProperty("sha1", sha1(data)); item.addProperty("size", data.length); item.addProperty("url", url); return item;
    }
    private static final class Fixture {
        final Path source;
        final byte[] client = bytes("legally obtained local fixture, not executable");
        final byte[] sound = bytes("sound fixture");
        final byte[] manifest;
        final JsonObject pins = new JsonObject();
        final Map<String, byte[]> data = new HashMap<>();
        Fixture(Path source) throws Exception {
            this.source = source;
            String soundHash = sha1(sound);
            byte[] index = bytes("{\"objects\":{\"sound\":{\"hash\":\"" + soundHash + "\",\"size\":" + sound.length + "}}}");
            byte[] server = bytes("server fixture"), library = bytes("library fixture");
            String serverUrl = "https://piston-data.mojang.com/server.jar", libraryUrl = "https://libraries.minecraft.net/group/lib.jar", indexUrl = "https://piston-meta.mojang.com/index.json";
            data.put(serverUrl, server); data.put(libraryUrl, library); data.put(indexUrl, index);
            data.put("https://resources.download.minecraft.net/" + soundHash.substring(0, 2) + "/" + soundHash, sound);
            JsonObject json = new JsonObject(); json.addProperty("id", "26.3");
            String clientUrl = "https://piston-data.mojang.com/client.jar";
            data.put(clientUrl, client);
            JsonObject downloads = new JsonObject(); downloads.add("server", artifact(server, serverUrl)); downloads.add("client", artifact(client, clientUrl)); json.add("downloads", downloads);
            JsonObject indexInfo = artifact(index, indexUrl); indexInfo.addProperty("id", "34"); json.add("assetIndex", indexInfo);
            JsonObject libraryInfo = new JsonObject(); libraryInfo.addProperty("name", "group:lib:1");
            JsonObject libraryDownloads = new JsonObject(); JsonObject jar = artifact(library, libraryUrl); jar.addProperty("path", "group/lib.jar"); libraryDownloads.add("artifact", jar); libraryInfo.add("downloads", libraryDownloads);
            JsonArray libraries = new JsonArray(); libraries.add(libraryInfo); json.add("libraries", libraries);
            manifest = bytes(json.toString());
            Path version = source.resolve("versions/26.3"); Files.createDirectories(version);
            Files.write(version.resolve("26.3.json"), manifest); Files.write(version.resolve("26.3.jar"), client);
            pins.addProperty("version", "26.3"); pins.addProperty("java_major", 25);
            pins.addProperty("manifest_sha1", sha1(manifest)); pins.addProperty("client_sha1", sha1(client)); pins.addProperty("server_sha1", sha1(server));
        }
    }
}
