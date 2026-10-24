package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MenuMusicPackTest {
    @TempDir Path temp;

    @Test
    void disablingPackDoesNotDeleteThroughASymlink() throws Exception {
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Path keep = outside.resolve("keep.txt");
        Files.writeString(keep, "keep");
        Path instance = temp.resolve("instance");
        Path pack = instance.resolve("resourcepacks").resolve(MenuMusicPack.FOLDER);
        Files.createDirectories(pack);
        Path link = pack.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            assumeTrue(false, "symlinks are not available");
        }
        MenuMusicPack.sync(instance, "1.21.1", false);
        assertTrue(Files.exists(keep));
        assertFalse(Files.exists(pack));
    }

    @Test
    void weightsAddToOneHundred() {
        assertEquals(100, C418Music.totalWeight(C418Music.TRACKS));
    }
}
