package com.cactuslauncher.game;

import java.nio.file.*;
import java.util.Comparator;

public final class BuildStoreCheck {
    interface Operation { void run() throws Exception; }
    static void rejected(Operation action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | java.io.IOException expected) { return; }
        throw new AssertionError("Invalid operation accepted");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cactus-build-check-");
        try {
            Path file = root.resolve("builds.properties");
            var store = new BuildStore(file);
            if (!store.load().isEmpty()) throw new AssertionError();
            var first = store.create("Моя сборка", "1.21.1", "Vanilla").getFirst();
            if (!new BuildStore(file).load().getFirst().equals(first)) throw new AssertionError("Reopen failed");
            if (!java.util.List.of("grass", "dirt", "stone", "diamond").contains(first.icon())) throw new AssertionError("Random icon invalid");
            store.setIcon(first.id(), "diamond");
            if (!new BuildStore(file).load().getFirst().icon().equals("diamond")) throw new AssertionError("Icon not persisted");
            rejected(() -> store.setIcon(first.id(), "invalid"));
            rejected(() -> store.create("моя СБОРКА", "1.21.1", "Vanilla"));
            rejected(() -> store.create(" ", "1.21.1", "Vanilla"));
            rejected(() -> store.create("x".repeat(65), "1.21.1", "Vanilla"));
            rejected(() -> store.create("bad\nname", "1.21.1", "Vanilla"));
            rejected(() -> store.create("Test", "../escape", "Vanilla"));
            rejected(() -> store.create("Test", "1.21.1", "Unknown"));
            var renamed = store.rename(first.id(), "Новое имя").getFirst();
            if (!renamed.id().equals(first.id()) || !new BuildStore(file).load().getFirst().name().equals("Новое имя")) throw new AssertionError();
            Path folder = store.folder(first.id());
            Files.createDirectories(folder.resolve("saves/world"));
            Files.writeString(folder.resolve("saves/world/level.dat"), "preserve");
            store.delete(first.id());
            if (!new BuildStore(file).load().isEmpty() || !Files.exists(folder.resolve("saves/world/level.dat"))) throw new AssertionError("Delete removed game files");
            rejected(() -> store.rename(first.id(), "Gone"));
            rejected(() -> store.folder("../../escape"));
            var second = store.create("Second", "b1.7.3", "Vanilla").getFirst();
            if (store.lastLaunched(second.id()) != 0) throw new AssertionError("New build already launched");
            store.markLaunched(second.id());
            if (store.lastLaunched(second.id()) <= 0) throw new AssertionError("Launch attempt not recorded");
            Path secondLogs = Files.createDirectories(store.folder(second.id()).resolve("logs"));
            Files.writeString(secondLogs.resolve("cactus-launch.log"), "Window$WindowInitFailed: No supported graphics backend was found.\n"
                    + "- Tried OpenGL: Failed to create window for OpenGL context: Couldn't find matching GLX visual\n");
            var graphics = new CrashDiagnosis().analyze(store.folder(second.id()), "26.3", "Vanilla");
            if (!graphics.detail().contains("aka.ms/mcdriver") || graphics.title().equals(CrashDiagnosis.UNKNOWN_TITLE))
                throw new AssertionError("Graphics backend crash not recognised");
            if (GithubReleaseUpdater.compareVersions("2", "2.0.0") != 0 || GithubReleaseUpdater.compareVersions("2.0.1", "2.0.0") <= 0
                    || GithubReleaseUpdater.compareVersions("1.9", "2.0.0") >= 0) throw new AssertionError("Release version comparison");
            rejected(() -> GithubReleaseUpdater.compareVersions("2.0.0-beta", "2.0.0"));
            if (JavaRuntimeManager.javaMajor("21.0.12.1") != 21 || JavaRuntimeManager.javaMajor("21") != 21
                    || JavaRuntimeManager.javaMajor("1.8.0_402") != 8 || JavaRuntimeManager.javaMajor("17.0.2") != 17
                    || JavaRuntimeManager.javaMajor("junk") != -1) throw new AssertionError("Java version parsing");
            var third = store.create("Third", "a1.2.6", "Vanilla").getLast();
            rejected(() -> store.rename(third.id(), "SECOND"));
            byte[] bytes = Files.readAllBytes(file);
            Files.writeString(file, "broken");
            rejected(store::load);
            rejected(() -> store.create("Fourth", "1.21.1", "Vanilla"));
            if (!Files.readString(file).equals("broken")) throw new AssertionError("Corrupt file overwritten");
            Files.write(file, bytes);
            var threads = new java.util.ArrayList<Thread>();
            var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                threads.add(Thread.ofPlatform().start(() -> {
                    try { store.create("Parallel " + index, "1.21.1", "Fabric"); }
                    catch (Throwable e) { errors.add(e); }
                }));
            }
            for (var thread : threads) thread.join();
            if (!errors.isEmpty() || store.load().size() != 10) throw new AssertionError("Concurrent save failed");
            System.out.println("BuildStore: reopen, validation, rename, delete preservation, corruption and concurrent saves OK; launch marker, crash diagnosis, version parsing OK");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
