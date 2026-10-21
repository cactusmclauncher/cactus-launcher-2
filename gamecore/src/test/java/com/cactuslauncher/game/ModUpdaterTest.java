package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModUpdaterTest {
    private static final String GAME = "1.21.1";
    @TempDir Path temp;
    private Path build;
    private Path mods;
    /** Download URL to file content. */
    private final Map<String, byte[]> cdn = new HashMap<>();
    private final Map<String, JsonObject> current = new LinkedHashMap<>();
    private final Map<String, JsonObject> latest = new LinkedHashMap<>();
    private final Map<String, JsonArray> projectVersions = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private boolean failDownloads;

    @BeforeEach
    void setUp() throws IOException {
        build = Files.createDirectories(temp.resolve("build"));
        mods = Files.createDirectories(build.resolve("mods"));
    }

    private final ModUpdater.Api api = new ModUpdater.Api() {
        @Override public JsonElement get(String path) {
            calls.add("GET " + path);
            if (path.startsWith("/projects?ids=")) {
                JsonArray projects = new JsonArray();
                for (String id : List.of("AAAA", "BBBB", "DEPP")) {
                    JsonObject project = new JsonObject();
                    project.addProperty("id", id);
                    project.addProperty("title", "Title " + id);
                    projects.add(project);
                }
                return projects;
            }
            for (var entry : projectVersions.entrySet()) if (path.startsWith("/project/" + entry.getKey() + "/version")) return entry.getValue();
            throw new AssertionError("Unexpected GET " + path);
        }

        @Override public JsonElement post(String path, JsonObject body) {
            calls.add("POST " + path);
            assertEquals("sha512", body.get("algorithm").getAsString());
            JsonObject result = new JsonObject();
            Map<String, JsonObject> source = switch (path) {
                case "/version_files" -> current;
                case "/version_files/update" -> {
                    assertEquals("[\"fabric\"]", body.get("loaders").toString());
                    assertEquals("[\"" + GAME + "\"]", body.get("game_versions").toString());
                    yield latest;
                }
                default -> throw new AssertionError(path);
            };
            for (var hash : body.getAsJsonArray("hashes")) if (source.containsKey(hash.getAsString())) result.add(hash.getAsString(), source.get(hash.getAsString()));
            return result;
        }

        @Override public void download(ModrinthClient.FileInfo file, Path target) throws IOException {
            if (failDownloads) throw new IOException("Контрольная сумма файла мода не совпала.");
            Files.write(target, cdn.get(file.url()), StandardOpenOption.CREATE_NEW);
        }
    };

    private static String sha512(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
    }

    private static JsonObject version(String id, String project, String number, String type, String date) {
        JsonObject version = new JsonObject();
        version.addProperty("id", id);
        version.addProperty("project_id", project);
        version.addProperty("version_number", number);
        version.addProperty("version_type", type);
        version.addProperty("date_published", date);
        version.addProperty("status", "listed");
        return version;
    }

    /** A downloadable version for {@code loaders} and {@code games}, served by the fake CDN. */
    private JsonObject release(String id, String project, String number, String fileName, List<String> games, List<String> loaders, String... required) throws Exception {
        JsonObject version = version(id, project, number, "release", "2026-03-01T00:00:00Z");
        JsonArray gameArray = new JsonArray(); games.forEach(gameArray::add);
        JsonArray loaderArray = new JsonArray(); loaders.forEach(loaderArray::add);
        version.add("game_versions", gameArray);
        version.add("loaders", loaderArray);
        byte[] content = ("jar " + fileName).getBytes(StandardCharsets.UTF_8);
        String url = "https://cdn.modrinth.com/data/" + project + "/" + fileName;
        cdn.put(url, content);
        JsonObject file = new JsonObject();
        file.addProperty("filename", fileName);
        file.addProperty("url", url);
        file.addProperty("size", content.length);
        file.addProperty("primary", true);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha512", sha512(content));
        file.add("hashes", hashes);
        JsonArray files = new JsonArray(); files.add(file);
        version.add("files", files);
        JsonArray dependencies = new JsonArray();
        for (String dependency : required) {
            JsonObject entry = new JsonObject();
            entry.addProperty("project_id", dependency);
            entry.addProperty("dependency_type", "required");
            dependencies.add(entry);
        }
        version.add("dependencies", dependencies);
        return version;
    }

    /** Writes an installed jar and registers its current Modrinth version. */
    private String installed(String fileName, String project, String number) throws Exception {
        byte[] content = ("old " + fileName).getBytes(StandardCharsets.UTF_8);
        Files.write(mods.resolve(fileName), content);
        String hash = sha512(content);
        current.put(hash, version("old-" + project, project, number, "release", "2026-01-01T00:00:00Z"));
        return hash;
    }

    private Properties index() throws IOException { return ModrinthClient.readIndex(mods); }

    /** a.jar (managed, needs new dependency DEPP) and b.jar.disabled (unmanaged). */
    private void twoUpdates() throws Exception {
        String a = installed("a.jar", "AAAA", "1.0");
        String b = installed("b.jar", "BBBB", "2.0");
        Files.move(mods.resolve("b.jar"), mods.resolve("b.jar.disabled"));
        Properties index = new Properties();
        index.setProperty("mod.AAAA.file", "a.jar");
        index.setProperty("mod.AAAA.title", "Alpha");
        index.setProperty("mod.AAAA.version", "1.0");
        index.setProperty("mod.AAAA.hash", "SHA-512:old");
        try (var output = Files.newOutputStream(mods.resolve("modrinth-index.properties"))) { index.store(output, null); }
        latest.put(a, release("new-a", "AAAA", "1.1", "a-1.1.jar", List.of(GAME), List.of("fabric"), "DEPP"));
        latest.put(b, release("new-b", "BBBB", "2.1", "b-2.1.jar", List.of(GAME), List.of("fabric")));
        JsonArray depVersions = new JsonArray();
        depVersions.add(release("dep-1", "DEPP", "0.9", "dep-0.9.jar", List.of(GAME), List.of("fabric")));
        projectVersions.put("DEPP", depVersions);
    }

    @Test
    void selectsOnlyCompatibleNewerReleases() throws Exception {
        var installedMods = new LinkedHashMap<String, ModUpdater.Installed>();
        JsonObject now = new JsonObject(), next = new JsonObject();
        String[] cases = {"good", "forgeOnly", "otherGame", "beta", "older", "same", "conflict"};
        for (String name : cases) {
            installedMods.put(name, new ModUpdater.Installed(name + ".jar", true));
            now.add(name, version("cur-" + name, "P" + name, "1.0", "release", "2026-01-01T00:00:00Z"));
        }
        next.add("good", release("n1", "Pgood", "1.1", "good-1.1.jar", List.of(GAME), List.of("fabric"), "Pforge", "NEWDEP"));
        next.add("forgeOnly", release("n2", "PforgeOnly", "1.1", "f.jar", List.of(GAME), List.of("forge")));
        next.add("otherGame", release("n3", "PotherGame", "1.1", "o.jar", List.of("1.20.1"), List.of("fabric")));
        JsonObject beta = release("n4", "Pbeta", "1.1-beta", "b.jar", List.of(GAME), List.of("fabric"));
        beta.addProperty("version_type", "beta");
        next.add("beta", beta);
        JsonObject older = release("n5", "Polder", "0.9", "old.jar", List.of(GAME), List.of("fabric"));
        older.addProperty("date_published", "2025-01-01T00:00:00Z");
        next.add("older", older);
        next.add("same", version("cur-same", "Psame", "1.0", "release", "2026-01-01T00:00:00Z"));
        JsonObject conflict = release("n7", "Pconflict", "1.1", "c.jar", List.of(GAME), List.of("fabric"));
        JsonObject incompatible = new JsonObject();
        incompatible.addProperty("project_id", "Pgood");
        incompatible.addProperty("dependency_type", "incompatible");
        conflict.getAsJsonArray("dependencies").add(incompatible);
        next.add("conflict", conflict);
        Set<String> installedProjects = new HashSet<>(List.of("Pgood", "Pforge", "PforgeOnly", "PotherGame", "Pbeta", "Polder", "Psame", "Pconflict"));

        var selected = ModUpdater.select(installedMods, now, next, GAME, "fabric", installedProjects);
        assertEquals(1, selected.size(), selected.toString());
        assertEquals("good.jar", selected.getFirst().installed().fileName());
        assertEquals("1.1", selected.getFirst().newVersion());
        // Pforge is installed already; only the new dependency is required.
        assertEquals(List.of("NEWDEP"), selected.getFirst().requiredDependencies());
    }

    @Test
    void checkReportsUpdatesWithNewDependencies() throws Exception {
        twoUpdates();
        var updates = new ModUpdater(api).check(mods, GAME, "Fabric");
        assertEquals(2, updates.size());
        var a = updates.getFirst();
        assertEquals("a.jar", a.fileName());
        assertEquals("Alpha", a.title());
        assertEquals("1.1", a.newVersion());
        assertEquals(List.of("Title DEPP"), a.newDependencies().stream().map(ModUpdater.Dependency::title).toList());
        var b = updates.get(1);
        assertFalse(b.enabled());
        assertEquals("Title BBBB", b.title());
        assertTrue(b.newDependencies().isEmpty());
    }

    @Test
    void applyKeepsDisabledModsDisabledAndBacksUp() throws Exception {
        twoUpdates();
        var result = new ModUpdater(api).apply(mods, GAME, "Fabric", null, true, 0);
        assertEquals(new ModUpdater.Result(2, 1, result.backupId()), result);
        assertEquals("jar a-1.1.jar", Files.readString(mods.resolve("a-1.1.jar")));
        assertTrue(Files.exists(mods.resolve("b-2.1.jar.disabled")));
        assertFalse(Files.exists(mods.resolve("b-2.1.jar")));
        assertTrue(Files.exists(mods.resolve("dep-0.9.jar")));
        assertFalse(Files.exists(mods.resolve("a.jar")));
        assertFalse(Files.exists(mods.resolve("b.jar.disabled")));
        Properties index = index();
        assertEquals("a-1.1.jar", index.getProperty("mod.AAAA.file"));
        assertEquals("1.1", index.getProperty("mod.AAAA.version"));
        assertEquals("dep-0.9.jar", index.getProperty("mod.DEPP.file"));
        assertEquals("b-2.1.jar", index.getProperty("mod.BBBB.file"));
        var backup = ModBackups.latest(build);
        assertEquals(result.backupId(), backup.id());
        assertEquals(2, backup.replaced());
        assertEquals(1, backup.added());
        assertFalse(backup.confirmed());
        try (var leftovers = Files.list(mods)) {
            assertTrue(leftovers.noneMatch(path -> path.getFileName().toString().startsWith(".mod-update-")));
        }
    }

    @Test
    void rollbackRestoresPreviousJarsAndIndex() throws Exception {
        twoUpdates();
        new ModUpdater(api).apply(mods, GAME, "Fabric", null, true, 0);
        // The user enables the updated mod before rolling back: the restored jar keeps that state.
        Files.move(mods.resolve("b-2.1.jar.disabled"), mods.resolve("b-2.1.jar"));
        assertEquals(2, ModBackups.rollbackLatest(build));
        assertEquals("old a.jar", Files.readString(mods.resolve("a.jar")));
        assertEquals("old b.jar", Files.readString(mods.resolve("b.jar")));
        for (String gone : List.of("a-1.1.jar", "b-2.1.jar", "dep-0.9.jar", "b.jar.disabled")) assertFalse(Files.exists(mods.resolve(gone)), gone);
        Properties index = index();
        assertEquals("a.jar", index.getProperty("mod.AAAA.file"));
        assertEquals("1.0", index.getProperty("mod.AAAA.version"));
        assertEquals("SHA-512:old", index.getProperty("mod.AAAA.hash"));
        assertEquals("Alpha", index.getProperty("mod.AAAA.title"));
        assertNull(index.getProperty("mod.DEPP.file"));
        assertNull(index.getProperty("mod.BBBB.file"));
        assertNull(ModBackups.latest(build));
        assertThrows(IOException.class, () -> ModBackups.rollbackLatest(build));
    }

    @Test
    void automaticModeSkipsUpdatesNeedingNewDependencies() throws Exception {
        twoUpdates();
        var result = new ModUpdater(api).apply(mods, GAME, "Fabric", null, false, 0);
        assertEquals(1, result.updated());
        assertEquals(0, result.dependencies());
        assertTrue(Files.exists(mods.resolve("a.jar")));
        assertTrue(Files.exists(mods.resolve("b-2.1.jar.disabled")));
    }

    @Test
    void singleModUpdateTouchesOnlyThatMod() throws Exception {
        twoUpdates();
        var result = new ModUpdater(api).apply(mods, GAME, "Fabric", Set.of("b.jar"), true, 0);
        assertEquals(1, result.updated());
        assertTrue(Files.exists(mods.resolve("a.jar")));
    }

    @Test
    void failedDownloadOrDeadlineChangesNothing() throws Exception {
        twoUpdates();
        failDownloads = true;
        assertThrows(IOException.class, () -> new ModUpdater(api).apply(mods, GAME, "Fabric", null, true, 0));
        failDownloads = false;
        assertThrows(IOException.class, () -> new ModUpdater(api).apply(mods, GAME, "Fabric", null, true, System.nanoTime() - 1));
        assertTrue(Files.exists(mods.resolve("a.jar")));
        assertTrue(Files.exists(mods.resolve("b.jar.disabled")));
        assertNull(ModBackups.latest(build));
        assertEquals("a.jar", index().getProperty("mod.AAAA.file"));
    }

    @Test
    void unavailableDependencySkipsThatUpdate() throws Exception {
        twoUpdates();
        projectVersions.put("DEPP", new JsonArray());
        var updates = new ModUpdater(api).check(mods, GAME, "Fabric");
        assertEquals(List.of("b.jar"), updates.stream().map(ModUpdater.Update::fileName).toList());
    }

    @Test
    void confirmAndPruneBackups() throws Exception {
        for (int i = 0; i < 5; i++) {
            Path folder = ModBackups.create(build);
            Properties manifest = new Properties();
            manifest.setProperty("created", Long.toString(i));
            manifest.setProperty("replaced.count", "0");
            ModBackups.writeManifest(folder, manifest);
        }
        String newest = ModBackups.latest(build).id();
        ModBackups.prune(build, ModBackups.KEEP);
        assertEquals(3, ModBackups.ids(build).size());
        assertEquals(newest, ModBackups.latest(build).id());
        assertEquals(4, ModBackups.latest(build).createdMillis());
        ModBackups.confirmLatest(build);
        assertTrue(ModBackups.latest(build).confirmed());
    }

    @Test
    void vanillaAndEmptyBuildsHaveNoUpdates() throws Exception {
        assertThrows(IOException.class, () -> new ModUpdater(api).check(mods, GAME, "Vanilla"));
        assertTrue(new ModUpdater(api).check(mods, GAME, "Fabric").isEmpty());
        assertTrue(calls.isEmpty(), "no network for an empty mods folder");
    }
}
