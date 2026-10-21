package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScreenshotsTest {
    @TempDir Path temp;

    private Path image(String name, int width, int height, long modified) throws Exception {
        Path folder = Files.createDirectories(temp.resolve("build/screenshots"));
        Path file = folder.resolve(name);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, name.endsWith(".png") ? "png" : "jpeg", file.toFile());
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    @Test
    void listsImagesNewestFirstAndSkipsOtherFiles() throws Exception {
        image("2026-10-01_10.00.00.png", 4, 4, 1_000);
        image("2026-10-02_10.00.00.png", 4, 4, 3_000);
        image("photo.jpg", 4, 4, 2_000);
        Path folder = temp.resolve("build/screenshots");
        Files.writeString(folder.resolve("notes.txt"), "x");
        Files.writeString(folder.resolve(".hidden.png"), "x");
        Files.createDirectories(folder.resolve("dir.png"));
        var shots = Screenshots.list(temp.resolve("build"));
        assertEquals(java.util.List.of("2026-10-02_10.00.00.png", "photo.jpg", "2026-10-01_10.00.00.png"),
                shots.stream().map(Screenshots.Shot::fileName).toList());
        assertEquals(java.util.List.of(), Screenshots.list(temp.resolve("empty")));
    }

    @Test
    void resolveRefusesPathsOutsideTheFolder() throws Exception {
        image("a.png", 2, 2, 1);
        Files.writeString(temp.resolve("build/secret.png"), "x");
        Path build = temp.resolve("build");
        assertEquals("a.png", Screenshots.resolve(build, "a.png").getFileName().toString());
        for (String bad : new String[] {"../secret.png", "sub/a.png", "a.txt", "", ".png", "/etc/a.png", "..\\secret.png"})
            assertThrows(java.io.IOException.class, () -> Screenshots.resolve(build, bad), bad);
        assertThrows(java.io.IOException.class, () -> Screenshots.resolve(build, "missing.png"));
    }

    @Test
    void symlinksAreNotFollowed() throws Exception {
        image("a.png", 2, 2, 1);
        Path outside = Files.writeString(temp.resolve("outside.png"), "x");
        Path build = temp.resolve("build");
        try { Files.createSymbolicLink(build.resolve("screenshots/link.png"), outside); }
        catch (UnsupportedOperationException | java.io.IOException e) { return; }
        assertEquals(java.util.List.of("a.png"), Screenshots.list(build).stream().map(Screenshots.Shot::fileName).toList());
        assertThrows(java.io.IOException.class, () -> Screenshots.resolve(build, "link.png"));
        Path linkedBuild = Files.createDirectories(temp.resolve("other"));
        Files.createSymbolicLink(linkedBuild.resolve("screenshots"), build.resolve("screenshots"));
        assertThrows(java.io.IOException.class, () -> Screenshots.list(linkedBuild));
    }

    @Test
    void thumbnailsAreDownscaledAndCachedByPathTimeAndSize() throws Exception {
        Path file = image("big.png", 1920, 1080, 5_000);
        Path cache = temp.resolve("cache");
        byte[] first = Screenshots.thumbnail(file, 320, cache);
        var decoded = ImageIO.read(new ByteArrayInputStream(first));
        assertEquals(320, decoded.getWidth());
        assertEquals(180, decoded.getHeight());
        try (var files = Files.list(cache)) { assertEquals(1, files.count()); }
        assertArrayEquals(first, Screenshots.thumbnail(file, 320, cache));
        // A changed file gets a new cache entry.
        Files.setLastModifiedTime(file, FileTime.fromMillis(9_000));
        Screenshots.thumbnail(file, 320, cache);
        try (var files = Files.list(cache)) { assertEquals(2, files.count()); }
        // Without a cache folder the thumbnail is still made.
        assertTrue(Screenshots.thumbnail(file, 100, null).length > 0);
        // Small images are not upscaled.
        Path small = image("small.jpg", 50, 40, 1);
        assertEquals(50, ImageIO.read(new ByteArrayInputStream(Screenshots.thumbnail(small, 320, null))).getWidth());
    }

    @Test
    void deleteRemovesOnlyTheScreenshot() throws Exception {
        Path file = image("a.png", 2, 2, 1);
        assertThrows(java.io.IOException.class, () -> Screenshots.delete(temp.resolve("build"), "../a.png", false));
        assertFalse(Screenshots.delete(temp.resolve("build"), "a.png", false));
        assertFalse(Files.exists(file));
    }
}
