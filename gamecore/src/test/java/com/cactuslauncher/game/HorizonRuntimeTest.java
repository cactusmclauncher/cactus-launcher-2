package com.cactuslauncher.game;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HorizonRuntimeTest {
    @TempDir Path temporary;
    Path temp;
    @BeforeEach void setup() throws IOException { temp = temporary.toRealPath(); }
    ProfileStore.Profile profile() { return new ProfileStore.Profile("12345678-1234-1234-1234-123456789abc", "LocalName"); }

    @Test void commandUsesNativeNoSessionAndPersistentProfileLayout() throws Exception {
        Path base = temp.resolve("profiles with spaces");
        var layout = HorizonRuntime.layout(base, profile());
        Files.writeString(layout.player().resolve("options.txt"), "existing-options");
        Files.createDirectories(layout.game().resolve("saves/world"));
        Files.writeString(layout.game().resolve("saves/world/level.dat"), "world");
        var renamed = HorizonRuntime.layout(base, new ProfileStore.Profile(profile().id(), "Renamed"));
        assertEquals(layout, renamed);
        assertEquals("name:Renamed\n", Files.readString(layout.player().resolve("horizon-player.txt")));
        assertEquals("existing-options", Files.readString(layout.player().resolve("options.txt")));
        assertEquals("world", Files.readString(layout.game().resolve("saves/world/level.dat")));
        assertEquals(List.of(temp.resolve("horizon-client").toString(), "--resources", temp.resolve("resources").toString(),
                "--game-dir", layout.game().toString(), "--home", layout.home().toString(), "--player-dir", layout.player().toString()),
                HorizonRuntime.command(temp.resolve("horizon-client"), temp.resolve("resources"), layout));
        assertNotEquals(layout.game(), HorizonRuntime.layout(base, new ProfileStore.Profile(UUID.randomUUID().toString(), "OtherName")).game());
    }

    @Test void refusesTraversalInvalidNicknameAndSymlinkedPlayerFiles() throws Exception {
        Path base = temp.resolve("profiles");
        for (var bad : List.of(new ProfileStore.Profile("../../outside", "ValidName"), new ProfileStore.Profile(profile().id(), "name\nsecret"), new ProfileStore.Profile(profile().id(), "ab")))
            assertThrows(IOException.class, () -> HorizonRuntime.layout(base, bad));
        var layout = HorizonRuntime.layout(base, profile());
        Path outside = temp.resolve("outside");
        Files.writeString(outside, "unchanged");
        for (String file : List.of("horizon-player.txt", "options.txt", "cactus-launch.log")) {
            Path target = layout.player().resolve(file);
            Files.deleteIfExists(target);
            Files.createSymbolicLink(target, outside);
            assertThrows(IOException.class, () -> HorizonRuntime.layout(base, profile()));
            Files.delete(target);
        }
        Files.delete(layout.player());
        Files.createSymbolicLink(layout.player(), temp);
        assertThrows(IOException.class, () -> HorizonRuntime.layout(base, profile()));
        assertEquals("unchanged", Files.readString(outside));
    }

    @Test void validatesApiAndRedactsCredentials() throws Exception {
        HorizonRuntime.validateApi(HorizonProcessFixture.API);
        assertThrows(IOException.class, () -> HorizonRuntime.validateApi("{}"));
        assertThrows(IOException.class, () -> HorizonRuntime.validateApi(HorizonProcessFixture.API.replace("0.1.0", "0.2.0")));
        assertThrows(IOException.class, () -> HorizonRuntime.validateApi(HorizonProcessFixture.API.replace("--player-dir", "--session-file")));
        String safe = HorizonRuntime.redact("{\"access_token\":\"secret-value\"} Authorization: Bearer bearer-value password=pass-value");
        for (String secret : List.of("secret-value", "bearer-value", "pass-value")) assertFalse(safe.contains(secret));
    }

    @Test void missingInstallationResourcesAndCorruptBundleNeverStartProcess() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        HorizonRuntime runtime = new HorizonRuntime(builder -> { starts.incrementAndGet(); throw new AssertionError("must not execute"); });
        Path directory = temp.resolve("horizon");
        var installer = new HorizonTest.Fixture(HorizonTest.bundle(new JsonObject())).installer(directory, runtime);
        var profiles = new ProfileStore(temp.resolve("profiles.properties"));
        assertThrows(IOException.class, () -> installer.launch(profiles, name -> fail()));
        Path root = Path.of(installer.install(p -> {}).path());
        assertThrows(IOException.class, () -> installer.launch(profiles, name -> fail()));
        Path resources = resources(root);
        Files.writeString(resources.resolve("data"), "tampered");
        assertThrows(IOException.class, () -> installer.launch(profiles, name -> fail()));
        Files.writeString(resources.resolve("data"), "prepared");
        Files.writeString(root.resolve(HorizonInstaller.binary("horizon-client")), "tampered");
        assertThrows(IOException.class, () -> installer.launch(profiles, name -> fail()));
        assertEquals(0, starts.get());
        assertFalse(installer.running());
    }

    @Test void resourcesPointerMustBeContainedAndInventoryVerified() throws Exception {
        Path root = Files.createDirectory(temp.resolve("bundle"));
        Path resources = resources(root);
        assertEquals(resources, HorizonPreparation.resolvedResources(root));
        Files.writeString(root.resolve("cactus-resources"), "../outside");
        assertThrows(IOException.class, () -> HorizonPreparation.resolvedResources(root));
        Files.writeString(root.resolve("cactus-resources"), resources.getFileName().toString());
        Files.delete(resources.resolve("data"));
        Files.createSymbolicLink(resources.resolve("data"), temp.resolve("outside"));
        assertThrows(IOException.class, () -> HorizonPreparation.resolvedResources(root));
    }

    @Test void cachedPreparedLaunchNeedsNoNetworkAndReportsAllExitCodes() throws Exception {
        Path directory = temp.resolve("horizon");
        var fixture = new HorizonTest.Fixture(HorizonTest.bundle(new JsonObject()));
        Path root = Path.of(fixture.installer(directory).install(p -> {}).path());
        resources(root);
        ProfileStore profiles = new ProfileStore(temp.resolve("profiles.properties"));
        var active = profiles.add("OfflineName");
        for (int exit : List.of(0, 1, 2, 101)) {
            List<List<String>> commands = new ArrayList<>();
            AtomicReference<Process> owned = new AtomicReference<>();
            HorizonRuntime runtime = helperRuntime(Integer.toString(exit), commands, owned);
            HorizonInstaller installer = new HorizonInstaller(directory, (u, l, d, p) -> { throw new AssertionError("network forbidden"); }, "", runtime);
            AtomicReference<String> started = new AtomicReference<>();
            var result = installer.launch(profiles, started::set);
            assertEquals(exit, result.exitCode());
            assertEquals("OfflineName", started.get());
            assertFalse(installer.running());
            assertFalse(owned.get().isAlive());
            assertEquals(List.of(root.resolve(HorizonInstaller.binary("horizon-client")).toString(), "--launch-api"), commands.getFirst());
            List<String> command = commands.getLast();
            assertEquals(resources(root).toString(), command.get(command.indexOf("--resources") + 1));
            assertTrue(command.stream().noneMatch(it -> it.contains("session") || it.contains("token") || it.contains("offline_without_owner")));
            Path player = Path.of(command.get(command.indexOf("--player-dir") + 1));
            assertTrue(player.startsWith(directory.resolve("profiles").resolve(active.activeId())));
            assertEquals("name:OfflineName\n", Files.readString(player.resolve("horizon-player.txt")));
            String log = Files.readString(Path.of(result.logPath()));
            assertTrue(log.contains("controlled helper output"));
            assertTrue(log.contains("exit=" + exit));
            assertFalse(log.contains("fixture-sensitive-value"));
        }
    }

    @Test void duplicateLaunchAndEveryMutationBlockedUntilOwnProcessStops() throws Exception {
        AtomicReference<Process> owned = new AtomicReference<>();
        HorizonRuntime runtime = helperRuntime("wait", new ArrayList<>(), owned);
        Path directory = temp.resolve("horizon");
        var installer = new HorizonTest.Fixture(HorizonTest.bundle(new JsonObject())).installer(directory, runtime);
        resources(Path.of(installer.install(p -> {}).path()));
        ProfileStore profiles = new ProfileStore(temp.resolve("profiles.properties"));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread launch = Thread.ofPlatform().start(() -> {
            try { installer.launch(profiles, name -> started.countDown()); }
            catch (Throwable e) { failure.set(e); }
        });
        Process unrelated = helper("wait").start();
        unrelated.getOutputStream().close();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(installer.running());
            assertThrows(IOException.class, () -> installer.launch(profiles, name -> fail()));
            assertThrows(IOException.class, () -> installer.install(p -> fail()));
            assertThrows(IOException.class, () -> installer.prepare("unused", "", p -> fail()));
            assertThrows(IOException.class, installer::rollback);
            assertThrows(IOException.class, () -> installer.checkUpdate(true, p -> fail()));
            launch.interrupt();
            launch.join(10000);
            assertFalse(launch.isAlive());
            assertInstanceOf(InterruptedIOException.class, failure.get());
            assertFalse(owned.get().isAlive());
            assertTrue(unrelated.isAlive());
            assertFalse(installer.running());
            assertNotNull(installer.install(p -> {}));
        } finally {
            launch.interrupt(); launch.join(10000);
            unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test void startFailureAndRejectedApiReleaseReservation() throws Exception {
        for (String mode : List.of("bad-api", "start-failure")) {
            HorizonRuntime runtime = mode.equals("bad-api") ? helperRuntime(mode, new ArrayList<>(), new AtomicReference<>())
                    : new HorizonRuntime(builder -> { throw new IOException("controlled spawn failure"); });
            Path directory = temp.resolve(mode);
            var installer = new HorizonTest.Fixture(HorizonTest.bundle(new JsonObject())).installer(directory, runtime);
            resources(Path.of(installer.install(p -> {}).path()));
            assertThrows(IOException.class, () -> installer.launch(new ProfileStore(temp.resolve(mode + ".properties")), name -> fail()));
            assertFalse(installer.running());
            assertNotNull(installer.install(p -> {}));
        }
    }

    static Path resources(Path root) throws Exception {
        Path resources = root.resolve("resources-12345678-1234-1234-1234-123456789abc");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("data"), "prepared");
        String digest = HorizonInstaller.digest(resources.resolve("data"), "SHA-256");
        Files.writeString(resources.resolve("cactus-files.json"), "{\"files\":[{\"path\":\"data\",\"sha256\":\"" + digest + "\"}]}");
        Files.writeString(root.resolve("cactus-resources"), resources.getFileName().toString());
        return resources;
    }

    static ProcessBuilder helper(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin", HorizonInstaller.binary("java")).toString();
        String classes;
        try { classes = Path.of(HorizonProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception e) { throw new AssertionError(e); }
        return new ProcessBuilder(java, "-cp", classes, HorizonProcessFixture.class.getName(), mode).redirectErrorStream(true);
    }

    static HorizonRuntime helperRuntime(String mode, List<List<String>> commands, AtomicReference<Process> owned) {
        return new HorizonRuntime(builder -> {
            commands.add(List.copyOf(builder.command()));
            assertTrue(builder.environment().keySet().stream().noneMatch(key -> key.toUpperCase(Locale.ROOT).startsWith("HORIZON_")));
            boolean probe = builder.command().contains("--launch-api");
            ProcessBuilder helper = helper(probe ? (mode.equals("bad-api") ? mode : "--launch-api") : mode);
            helper.directory(builder.directory());
            helper.environment().clear();
            helper.environment().putAll(builder.environment());
            Process process = helper.start();
            owned.set(process);
            return process;
        });
    }
}
