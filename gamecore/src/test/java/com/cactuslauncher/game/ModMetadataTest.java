package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModMetadataTest {
    @TempDir Path temp;

    private static final String SODIUM = """
            {
              "schemaVersion": 1,
              "id": "sodium",
              "version": "0.5.11+mc1.21",
              "name": "Sodium",
              "provides": ["indium-compat"],
              "environment": "client",
              "depends": { "minecraft": ["1.21", "1.21.1"], "fabricloader": ">=0.15.0", "java": ">=21" },
              "recommends": { "modmenu": "*" },
              "breaks": { "optifabric": "*", "iris": "<1.7.0" },
              "conflicts": { "embeddium": "*" },
              "jars": [ { "file": "META-INF/jars/fabric-api-base.jar" } ]
            }
            """;

    @Test
    void readsFabricModJson() throws Exception {
        var jar = ModMetadata.read("sodium.jar", ModTestJars.fabric(SODIUM));
        assertEquals(Set.of(ModMetadata.Platform.FABRIC), jar.platforms());
        var mod = jar.mods().get(0);
        assertEquals("sodium", mod.id());
        assertEquals("Sodium", mod.name());
        assertEquals("0.5.11+mc1.21", mod.version());
        assertEquals(List.of("indium-compat"), mod.provides());
        assertFalse(mod.nested());
        var byId = mod.dependencies().stream().collect(java.util.stream.Collectors.toMap(ModMetadata.Dependency::modId, d -> d));
        assertEquals(ModMetadata.Kind.REQUIRED, byId.get("minecraft").kind());
        assertTrue(byId.get("minecraft").range().test("1.21.1"));
        assertFalse(byId.get("minecraft").range().test("1.20.6"));
        assertEquals(ModMetadata.Kind.OPTIONAL, byId.get("modmenu").kind());
        assertEquals(ModMetadata.Kind.BREAKS, byId.get("iris").kind());
        assertTrue(byId.get("iris").range().test("1.6.4"));
        assertEquals(ModMetadata.Kind.CONFLICTS, byId.get("embeddium").kind());
    }

    @Test
    void readsNestedFabricJarsAndTheirProvides() throws Exception {
        byte[] inner = ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"fabric-api-base\",\"version\":\"0.4.42\",\"provides\":[\"fabric-api-base-legacy\"]," +
                "\"depends\":{\"fabricloader\":\">=0.4.0\"},\"jars\":[{\"file\":\"META-INF/jars/deep.jar\"}]}");
        byte[] deep = ModTestJars.fabric("deep-lib", "1.0.0", null);
        inner = ModTestJars.withNested(inner, Map.of("META-INF/jars/deep.jar", deep));
        byte[] outer = ModTestJars.withNested(ModTestJars.fabric(SODIUM), Map.of("META-INF/jars/fabric-api-base.jar", inner));
        Path file = Files.write(temp.resolve("sodium.jar"), outer);
        var jar = ModMetadata.read(file);
        assertEquals(List.of("sodium", "fabric-api-base", "deep-lib"), jar.mods().stream().map(ModMetadata.Mod::id).toList());
        assertTrue(jar.mods().get(1).nested());
        assertTrue(jar.mods().get(2).nested());
        assertEquals(List.of("fabric-api-base-legacy"), jar.mods().get(1).provides());
        assertEquals(1, jar.topLevel().size());
    }

    @Test
    void serverOnlyFabricModsAreNotLoadedOnTheClient() throws Exception {
        var jar = ModMetadata.read("server.jar", ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"srv\",\"version\":\"1\",\"environment\":\"server\"}"));
        assertEquals(Set.of(ModMetadata.Platform.FABRIC), jar.platforms());
        assertTrue(jar.mods().isEmpty());
    }

    static final String FORGE_TOML = """
            # Forge 1.20.1 example
            modLoader="javafml" #mandatory
            loaderVersion="[47,)"
            license='MIT'
            [[mods]]
            modId="create"
            version="${file.jarVersion}"
            displayName="Create"
            description='''
            Multi-line description with "quotes" and [brackets]
            '''
            [[dependencies.create]]
                modId="forge"
                mandatory=true
                versionRange="[47.1.3,)"
                ordering="NONE"
                side="BOTH"
            [[dependencies.create]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.20.2)"
                side="BOTH"
            [[dependencies.create]]
                modId="jei"
                mandatory=false
                versionRange="[15.2,)"
                side="CLIENT"
            [[dependencies."create"]]
                modId="serverthing"
                mandatory=true
                side="SERVER"
            """;

    @Test
    void readsForgeModsTomlWithManifestVersion() throws Exception {
        byte[] bytes = ModTestJars.jar("META-INF/mods.toml", FORGE_TOML,
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 0.5.1.f\r\n\r\n");
        var jar = ModMetadata.read("create.jar", bytes);
        assertEquals(Set.of(ModMetadata.Platform.FORGE), jar.platforms());
        var mod = jar.mods().get(0);
        assertEquals("create", mod.id());
        assertEquals("Create", mod.name());
        assertEquals("0.5.1.f", mod.version());
        assertEquals(List.of("javafml", "forge", "minecraft", "jei"), mod.dependencies().stream().map(ModMetadata.Dependency::modId).toList());
        assertEquals(ModMetadata.Kind.OPTIONAL, mod.dependencies().get(3).kind());
        assertTrue(mod.dependencies().get(0).range().test("47"));
        assertFalse(mod.dependencies().get(1).range().test("47.0.1"));
    }

    @Test
    void readsNeoForgeTypesAndJarJar() throws Exception {
        String neo = """
                modLoader = "javafml"
                loaderVersion = "[4,)"
                [[mods]]
                modId = "neomod"
                version = "2.0.0"
                [[dependencies.neomod]]
                modId = "neoforge"
                type = "required"
                versionRange = "[21.1,)"
                [[dependencies.neomod]]
                modId = "optifine"
                type = "incompatible"
                [[dependencies.neomod]]
                modId = "rubidium"
                type = "discouraged"
                [[dependencies.neomod]]
                modId = "jei"
                type = "optional"
                """;
        byte[] lib = ModTestJars.jar("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"neolib\"\nversion=\"1.1\"\n");
        byte[] outer = ModTestJars.withNested(ModTestJars.jar("META-INF/neoforge.mods.toml", neo,
                "META-INF/jarjar/metadata.json", "{\"jars\":[{\"identifier\":{\"group\":\"x\",\"artifact\":\"neolib\"},\"version\":{\"range\":\"[1,)\",\"artifactVersion\":\"1.1\"},\"path\":\"META-INF/jarjar/neolib.jar\",\"isObfuscated\":false}]}"),
                Map.of("META-INF/jarjar/neolib.jar", lib));
        var jar = ModMetadata.read("neomod.jar", outer);
        assertEquals(Set.of(ModMetadata.Platform.NEOFORGE), jar.platforms());
        var kinds = jar.mods().get(0).dependencies().stream().map(ModMetadata.Dependency::kind).toList();
        assertEquals(List.of(ModMetadata.Kind.REQUIRED, ModMetadata.Kind.REQUIRED, ModMetadata.Kind.BREAKS, ModMetadata.Kind.CONFLICTS, ModMetadata.Kind.OPTIONAL), kinds);
        assertEquals("neolib", jar.mods().get(1).id());
        assertTrue(jar.mods().get(1).nested());
    }

    @Test
    void readsQuiltModJson() throws Exception {
        String quilt = """
                {"schema_version":1,"quilt_loader":{"group":"org.example","id":"qmod","version":"1.0.0",
                 "metadata":{"name":"Q Mod"},"provides":["qalias"],
                 "depends":["minecraft",{"id":"org.quiltmc:qsl","versions":">=6.0"},{"id":"opt","optional":true}],
                 "breaks":[{"id":"bad","versions":"*"}]}}
                """;
        var jar = ModMetadata.read("q.jar", ModTestJars.jar("quilt.mod.json", quilt));
        assertEquals(Set.of(ModMetadata.Platform.QUILT), jar.platforms());
        var mod = jar.mods().get(0);
        assertEquals("Q Mod", mod.name());
        assertEquals(List.of("qalias"), mod.provides());
        assertEquals(List.of("minecraft", "qsl", "opt", "bad"), mod.dependencies().stream().map(ModMetadata.Dependency::modId).toList());
        assertEquals(ModMetadata.Kind.OPTIONAL, mod.dependencies().get(2).kind());
        assertEquals(ModMetadata.Kind.BREAKS, mod.dependencies().get(3).kind());
    }

    @Test
    void plainLibrariesAndBrokenFilesHaveNoPlatform() throws Exception {
        assertTrue(ModMetadata.read("lib.jar", ModTestJars.jar("a/B.class", "x")).platforms().isEmpty());
        Path broken = Files.writeString(temp.resolve("broken.jar"), "not a zip");
        assertTrue(ModMetadata.read(broken).platforms().isEmpty());
        var badJson = ModMetadata.read("bad.jar", ModTestJars.fabric("{ not json"));
        assertTrue(badJson.platforms().isEmpty());
    }

    @Test
    void miniTomlHandlesInlineTablesArraysAndEscapes() throws Exception {
        var root = MiniToml.parse("""
                title = "a \\"quoted\\" \\u00e9"
                list = [ "x", 'y',
                  "z" ]  # comment
                [section.sub]
                inline = { a = 1, b.c = true }
                [[mods]]
                modId = "m1"
                [[mods]]
                modId = "m2"
                """);
        assertEquals("a \"quoted\" é", root.get("title"));
        assertEquals(List.of("x", "y", "z"), root.get("list"));
        @SuppressWarnings("unchecked") var section = (Map<String, Object>) ((Map<String, Object>) root.get("section")).get("sub");
        @SuppressWarnings("unchecked") var inline = (Map<String, Object>) section.get("inline");
        assertEquals("1", inline.get("a"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) inline.get("b")).get("c"));
        assertEquals(2, ((List<?>) root.get("mods")).size());
        assertThrows(java.io.IOException.class, () -> MiniToml.parse("a = \"unterminated\n"));
    }
}
