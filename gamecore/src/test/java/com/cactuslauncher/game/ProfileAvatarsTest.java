package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileAvatarsTest {
    private static final String ID = "3f2a1c9e-0b7d-4e57-9a51-2c1d0e8f6b44";
    @TempDir Path temp;

    private ProfileAvatars avatars() { return new ProfileAvatars(temp.resolve("avatars")); }

    /** Left half red, right half blue, with a green band in the middle third. */
    private Path image(String name, String format, int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(width / 3, 0, width / 3, height);
        graphics.dispose();
        Path file = temp.resolve(name);
        assertTrue(ImageIO.write(image, format, file.toFile()));
        return file;
    }

    private BufferedImage stored(String id) throws IOException {
        byte[] bytes = avatars().read(id);
        assertNotNull(bytes);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void wideImageIsCroppedToCentredSquarePng() throws Exception {
        String version = avatars().store(ID, image("wide.png", "png", 900, 300));
        assertTrue(version.matches("[0-9a-f]{16}"));
        assertTrue(Files.isRegularFile(temp.resolve("avatars/" + ID + ".png")));
        BufferedImage result = stored(ID);
        assertEquals(ProfileAvatars.SIZE, result.getWidth());
        assertEquals(ProfileAvatars.SIZE, result.getHeight());
        // The 300 px wide centre band is exactly the green stripe, so the whole square is green.
        assertEquals(Color.GREEN.getRGB(), result.getRGB(5, 128));
        assertEquals(Color.GREEN.getRGB(), result.getRGB(250, 128));
    }

    @Test
    void jpegAndSmallImagesAreAccepted() throws Exception {
        avatars().store(ID, image("photo.jpg", "jpg", 64, 100));
        assertEquals(ProfileAvatars.SIZE, stored(ID).getWidth());
    }

    @Test
    void largeImageIsScaledDown() {
        var source = new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_ARGB);
        var result = ProfileAvatars.squareThumbnail(source, 256);
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
    }

    @Test
    void textFileIsRejected() throws Exception {
        Path fake = Files.writeString(temp.resolve("fake.png"), "not an image");
        var error = assertThrows(IOException.class, () -> avatars().store(ID, fake));
        assertEquals("Выберите изображение PNG или JPG.", error.getMessage());
        assertNull(avatars().read(ID));
    }

    @Test
    void truncatedPngIsRejected() throws Exception {
        byte[] png = Files.readAllBytes(image("full.png", "png", 200, 200));
        Path broken = Files.write(temp.resolve("broken.png"), java.util.Arrays.copyOf(png, 60));
        assertThrows(IOException.class, () -> avatars().store(ID, broken));
        assertNull(avatars().read(ID));
    }

    @Test
    void unsupportedFormatIsRejected() throws Exception {
        Path gif = image("anim.gif", "gif", 32, 32);
        assertThrows(IOException.class, () -> avatars().store(ID, gif));
    }

    @Test
    void oversizedFileIsRejectedBeforeDecoding() throws Exception {
        Path big = image("big.png", "png", 16, 16);
        Files.write(big, new byte[(int) ProfileAvatars.MAX_SOURCE_BYTES], java.nio.file.StandardOpenOption.APPEND);
        var error = assertThrows(IOException.class, () -> avatars().store(ID, big));
        assertTrue(error.getMessage().contains("5 МБ"));
    }

    @Test
    void hugeDimensionsAreRejected() throws Exception {
        Path tall = image("tall.png", "png", 1, 5000);
        var error = assertThrows(IOException.class, () -> avatars().store(ID, tall));
        assertTrue(error.getMessage().contains("4096"));
    }

    @Test
    void profileIdCannotEscapeFolder() throws Exception {
        Path ok = image("ok.png", "png", 10, 10);
        assertThrows(IllegalArgumentException.class, () -> avatars().store("../evil", ok));
        assertThrows(IllegalArgumentException.class, () -> avatars().store("a/b", ok));
        assertThrows(IllegalArgumentException.class, () -> avatars().read(""));
    }

    @Test
    void replaceAndDelete() throws Exception {
        String first = avatars().store(ID, image("a.png", "png", 50, 50));
        String second = avatars().store(ID, image("b.jpg", "jpg", 80, 40));
        assertNotEquals(first, second);
        avatars().delete(ID);
        assertNull(avatars().read(ID));
        avatars().delete(ID);
    }
}
