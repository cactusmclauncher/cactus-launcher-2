package com.cactuslauncher.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Comparator;
import static org.junit.jupiter.api.Assertions.*;

class BuildSnapshotsTest {
    @TempDir Path temp;

    @Test void restoresStateAndKeepsSafetyPoint() throws Exception {
        Path build = Files.createDirectory(temp.resolve("build"));
        Files.createDirectories(build.resolve("mods"));
        Files.writeString(build.resolve("mods/a.jar"), "old");
        Files.writeString(build.resolve("config.txt"), "v1");
        Files.createDirectories(build.resolve("saves"));
        Files.writeString(build.resolve("saves/world.dat"), "world-v1");
        Files.createDirectories(build.resolve("logs"));
        Files.writeString(build.resolve("logs/latest.log"), "log-v1");
        var first = BuildSnapshots.create(build, "Начало");
        Files.writeString(build.resolve("mods/a.jar"), "new");
        Files.writeString(build.resolve("mods/b.jar"), "added");
        Files.writeString(build.resolve("config.txt"), "v2");
        Files.writeString(build.resolve("saves/world.dat"), "world-v2");
        var second = BuildSnapshots.create(build, "Изменения");
        assertEquals(Files.size(build.resolve("mods/a.jar")) + Files.size(build.resolve("mods/b.jar")) + Files.size(build.resolve("config.txt")), second.uniqueBytes());
        BuildSnapshots.restore(build, first.id());
        assertEquals("old", Files.readString(build.resolve("mods/a.jar")));
        assertFalse(Files.exists(build.resolve("mods/b.jar")));
        assertEquals("v1", Files.readString(build.resolve("config.txt")));
        assertEquals("world-v2", Files.readString(build.resolve("saves/world.dat")));
        assertEquals("log-v1", Files.readString(build.resolve("logs/latest.log")));
        assertEquals(3, BuildSnapshots.list(build).size());
    }

    @Test void rejectsSymbolicLinksWithoutChangingBuild() throws Exception {
        Path build = Files.createDirectory(temp.resolve("build"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "keep");
        Files.createSymbolicLink(build.resolve("escape.txt"), outside);
        assertThrows(java.io.IOException.class, () -> BuildSnapshots.create(build, "unsafe"));
        assertEquals("keep", Files.readString(outside));
    }
}
