package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import com.cactuslauncher.game.ModConflicts.Problem;
import com.cactuslauncher.game.ModConflicts.Severity;
import com.cactuslauncher.game.ModConflicts.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModConflictsTest {
    @TempDir Path temp;
    private static final ModConflicts.Environment FABRIC = new ModConflicts.Environment("Fabric", "1.21.1", "0.16.5", 21);
    private static final ModConflicts.Environment FORGE = new ModConflicts.Environment("Forge", "1.20.1", "47.2.0", 17);

    private static ModMetadata.Jar jar(String file, byte[] bytes) throws Exception { return ModMetadata.read(file, bytes); }

    private static ModMetadata.Jar fabric(String file, String id, String version, String depends) throws Exception {
        return jar(file, ModTestJars.fabric(id, version, depends));
    }

    private static Problem only(List<Problem> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }

    @Test
    void healthyBuildHasNoProblems() throws Exception {
        var problems = ModConflicts.check(List.of(
                fabric("fabric-api.jar", "fabric-api", "0.102.0+1.21.1", "\"fabricloader\":\">=0.15.11\",\"minecraft\":\"~1.21.1\""),
                fabric("sodium.jar", "sodium", "0.6.0", "\"fabric-api\":\">=0.92\",\"minecraft\":\"1.21.x\",\"java\":\">=21\"")), FABRIC);
        assertEquals(List.of(), problems);
    }

    @Test
    void missingRequiredDependencyOffersModrinthInstall() throws Exception {
        var problem = only(ModConflicts.check(List.of(
                fabric("sodium.jar", "sodium", "0.6.0", "\"fabric-api\":\">=0.92.0\"")), FABRIC));
        assertEquals(Type.MISSING, problem.type());
        assertEquals(Severity.ERROR, problem.severity());
        assertEquals("sodium.jar", problem.fileName());
        assertEquals("Нужен Fabric API ≥ 0.92.0", problem.message());
        assertEquals("fabric-api", problem.dependencyId());
        // Legacy "fabric" id maps to the Fabric API project.
        assertEquals("fabric-api", only(ModConflicts.check(List.of(fabric("old.jar", "old", "1.0", "\"fabric\":\"*\"")), FABRIC)).dependencyId());
    }

    @Test
    void dependencyOutOfRange() throws Exception {
        var problem = only(ModConflicts.check(List.of(
                fabric("api.jar", "fabric-api", "0.90.0", null),
                fabric("sodium.jar", "sodium", "0.6.0", "\"fabric-api\":\">=0.92.0\"")), FABRIC));
        assertEquals(Type.VERSION, problem.type());
        assertEquals("Нужен Fabric API ≥ 0.92.0, установлен 0.90.0", problem.message());
        assertEquals("api.jar", problem.otherFile());
        assertNull(problem.dependencyId());
    }

    @Test
    void minecraftLoaderAndJavaRequirements() throws Exception {
        var problems = ModConflicts.check(List.of(
                fabric("old.jar", "oldmod", "1.0", "\"minecraft\":\"1.20.x\""),
                fabric("new.jar", "newmod", "1.0", "\"fabricloader\":\">=0.17.0\""),
                fabric("java.jar", "javamod", "1.0", "\"java\":\">=22\"")), FABRIC);
        assertEquals(3, problems.size(), problems.toString());
        var byFile = new java.util.HashMap<String, Problem>();
        problems.forEach(problem -> byFile.put(problem.fileName(), problem));
        assertEquals(Type.MINECRAFT, byFile.get("old.jar").type());
        assertEquals("Нужен Minecraft 1.20.x, в сборке 1.21.1", byFile.get("old.jar").message());
        assertEquals(Type.LOADER_VERSION, byFile.get("new.jar").type());
        assertEquals("Нужен Fabric Loader ≥ 0.17.0, в сборке 0.16.5", byFile.get("new.jar").message());
        assertEquals(Type.JAVA, byFile.get("java.jar").type());
        assertEquals("Нужна Java ≥ 22, у сборки Java 21", byFile.get("java.jar").message());
        problems.forEach(problem -> assertNull(problem.dependencyId()));
    }

    @Test
    void unknownEnvironmentValuesAreNotGuessed() throws Exception {
        var unknown = new ModConflicts.Environment("Fabric", "24w14a", null, 0);
        assertEquals(List.of(), ModConflicts.check(List.of(
                fabric("a.jar", "a", "1.0", "\"minecraft\":\"1.20.x\",\"fabricloader\":\">=0.99\",\"java\":\">=99\"")), unknown));
    }

    @Test
    void duplicateModIdsKeepTheNewest() throws Exception {
        var problem = only(ModConflicts.check(List.of(
                fabric("sodium-0.5.11.jar", "sodium", "0.5.11", null),
                fabric("sodium-0.6.0.jar", "sodium", "0.6.0", null)), FABRIC));
        assertEquals(Type.DUPLICATE, problem.type());
        assertEquals("sodium-0.5.11.jar", problem.fileName());
        assertEquals("sodium-0.6.0.jar", problem.otherFile());
        assertEquals("Повтор: этот мод уже есть в sodium-0.6.0.jar", problem.message());
    }

    @Test
    void breaksAndConflicts() throws Exception {
        String sodium = "{\"schemaVersion\":1,\"id\":\"sodium\",\"version\":\"0.6.0\",\"name\":\"Sodium\"," +
                "\"breaks\":{\"optifabric\":\"*\",\"iris\":\"<1.7.0\"},\"conflicts\":{\"lithium\":\"<0.12\"}}";
        var problems = ModConflicts.check(List.of(
                jar("sodium.jar", ModTestJars.fabric(sodium)),
                jar("optifabric.jar", ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"optifabric\",\"version\":\"1.14\",\"name\":\"OptiFabric\"}")),
                fabric("iris.jar", "iris", "1.8.0", null),
                fabric("lithium.jar", "lithium", "0.11.2", null)), FABRIC);
        assertEquals(2, problems.size(), problems.toString());
        assertEquals(Type.BREAKS, problems.get(0).type());
        assertEquals(Severity.ERROR, problems.get(0).severity());
        assertEquals("Конфликтует с OptiFabric", problems.get(0).message());
        assertEquals("optifabric.jar", problems.get(0).otherFile());
        assertEquals(Type.CONFLICTS, problems.get(1).type());
        assertEquals(Severity.WARNING, problems.get(1).severity());
        assertEquals("Может конфликтовать с lithium", problems.get(1).message());
    }

    @Test
    void wrongLoaderJarsAreReportedAndSkipped() throws Exception {
        var forgeJar = jar("create.jar", ModTestJars.forge(ModMetadataTest.FORGE_TOML));
        var quiltJar = jar("q.jar", ModTestJars.jar("quilt.mod.json", "{\"schema_version\":1,\"quilt_loader\":{\"id\":\"q\",\"version\":\"1\"}}"));
        var problems = ModConflicts.check(List.of(forgeJar, quiltJar), FABRIC);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(problem -> problem.type() == Type.LOADER));
        assertTrue(problems.stream().anyMatch(problem -> problem.message().equals("Мод для Forge, а сборка на Fabric")));
        assertTrue(problems.stream().anyMatch(problem -> problem.message().equals("Мод для Quilt, а сборка на Fabric")));
        var fabricInForge = only(ModConflicts.check(List.of(fabric("sodium.jar", "sodium", "0.6.0", null)), FORGE));
        assertEquals("Мод для Fabric, а сборка на Forge", fabricInForge.message());
    }

    @Test
    void forgeRangesAndOptionalDependencies() throws Exception {
        var create = jar("create.jar", ModTestJars.forge(ModMetadataTest.FORGE_TOML.replace("${file.jarVersion}", "0.5.1")));
        assertEquals(List.of(), ModConflicts.check(List.of(create), FORGE));
        // Optional JEI is fine when absent but must be in range when installed.
        var oldJei = jar("jei.jar", ModTestJars.forge("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"jei\"\nversion=\"15.1.0\"\ndisplayName=\"Just Enough Items\"\n"));
        var problem = only(ModConflicts.check(List.of(create, oldJei), FORGE));
        assertEquals(Type.VERSION, problem.type());
        assertEquals(Severity.ERROR, problem.severity());
        assertEquals("Рекомендуется JEI ≥ 15.2, установлен 15.1.0", problem.message());
        var oldForge = new ModConflicts.Environment("Forge", "1.20.1", "47.0.3", 17);
        var forgeProblem = only(ModConflicts.check(List.of(create), oldForge));
        assertEquals(Type.LOADER_VERSION, forgeProblem.type());
        assertEquals("Нужен Forge ≥ 47.1.3, в сборке 47.0.3", forgeProblem.message());
        var otherMinecraft = new ModConflicts.Environment("Forge", "1.20.2", "48.0.1", 17);
        assertTrue(ModConflicts.check(List.of(create), otherMinecraft).stream().anyMatch(p -> p.type() == Type.MINECRAFT));
    }

    @Test
    void nestedJarsProvideTheirIds() throws Exception {
        byte[] nested = ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"cloth-config\",\"version\":\"15.0.140\",\"provides\":[\"cloth-config2\"]}");
        byte[] outer = ModTestJars.withNested(
                ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"rei\",\"version\":\"16.0\",\"jars\":[{\"file\":\"META-INF/jars/cloth.jar\"}]}"),
                Map.of("META-INF/jars/cloth.jar", nested));
        var problems = ModConflicts.check(List.of(
                jar("rei.jar", outer),
                fabric("needs-cloth.jar", "needs", "1.0", "\"cloth-config2\":\">=15\"")), FABRIC);
        assertEquals(List.of(), problems);
        // A nested copy next to a top-level copy is not a duplicate: the loader picks one.
        assertEquals(List.of(), ModConflicts.check(List.of(jar("rei.jar", outer), fabric("cloth.jar", "cloth-config", "15.0.140", null)), FABRIC));
    }

    @Test
    void nestedModDependenciesAreChecked() throws Exception {
        byte[] nested = ModTestJars.fabric("inner", "1.0", "\"fabricloader\":\">=0.20\"");
        byte[] outer = ModTestJars.withNested(
                ModTestJars.fabric("{\"schemaVersion\":1,\"id\":\"outer\",\"name\":\"Outer\",\"version\":\"1.0\",\"jars\":[{\"file\":\"META-INF/jars/inner.jar\"}]}"),
                Map.of("META-INF/jars/inner.jar", nested));
        var problem = only(ModConflicts.check(List.of(jar("outer.jar", outer)), FABRIC));
        assertEquals("outer.jar", problem.fileName());
        assertEquals("Outer", problem.modName());
        assertEquals(Type.LOADER_VERSION, problem.type());
    }

    @Test
    void disabledModsAndLibrariesInTheFolderAreIgnored() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Files.write(mods.resolve("sodium.jar"), ModTestJars.fabric("sodium", "0.6.0", "\"fabric-api\":\"*\""));
        Files.write(mods.resolve("fabric-api.jar.disabled"), ModTestJars.fabric("fabric-api", "0.102.0", null));
        Files.write(mods.resolve("lithium.jar.disabled"), ModTestJars.fabric("lithium", "1.0", "\"missing-thing\":\"*\""));
        Files.write(mods.resolve("library.jar"), ModTestJars.jar("a/B.class", "x"));
        Files.writeString(mods.resolve("notes.txt"), "x");
        var problems = new ArrayList<>(ModConflicts.check(mods, FABRIC));
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("sodium.jar", problems.get(0).fileName());
        assertEquals(Type.MISSING, problems.get(0).type());
        assertEquals(List.of(), ModConflicts.check(temp.resolve("absent"), FABRIC));
    }

    @Test
    void metadataCacheIsReusedUntilTheJarChanges() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path sodium = Files.write(mods.resolve("sodium.jar"), ModTestJars.fabric("sodium", "0.6.0", "\"fabric-api\":\"*\""));
        var cache = new java.util.HashMap<String, ModMetadata.Jar>();
        assertEquals(1, ModConflicts.check(mods, FABRIC, cache).size());
        assertEquals(1, cache.size());
        assertEquals(1, ModConflicts.check(mods, FABRIC, cache).size());
        Files.write(sodium, ModTestJars.fabric("sodium", "0.6.0", null));
        Files.setLastModifiedTime(sodium, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(List.of(), ModConflicts.check(mods, FABRIC, cache));
    }

    @Test
    void symlinkedJarsAreNotRead() throws Exception {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Path outside = Files.write(temp.resolve("outside.jar"), ModTestJars.fabric("evil", "1.0", "\"nothing\":\"*\""));
        try { Files.createSymbolicLink(mods.resolve("link.jar"), outside); }
        catch (UnsupportedOperationException | java.io.IOException e) { return; }
        assertEquals(List.of(), ModConflicts.check(mods, FABRIC));
    }

    @Test
    void loaderVersionFromProfileIds() {
        assertEquals("0.16.5", ModConflicts.loaderVersion("Fabric", "fabric-loader-0.16.5-1.21.1"));
        assertEquals("47.2.0", ModConflicts.loaderVersion("Forge", "1.20.1-forge-47.2.0"));
        assertNull(ModConflicts.loaderVersion("Fabric", "1.21.1"));
    }
}
