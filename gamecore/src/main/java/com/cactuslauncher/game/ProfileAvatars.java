package com.cactuslauncher.game;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Profile pictures: {@code <folder>/<profile id>.png}, a square PNG of {@link #SIZE} pixels.
 * The file name comes only from the profile id, never from the chosen file.
 */
public final class ProfileAvatars {
    public static final long MAX_SOURCE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_SOURCE_DIMENSION = 4096;
    public static final int SIZE = 256;
    /** Upper bound for a stored avatar; a 256×256 ARGB PNG is far below it. */
    private static final long MAX_STORED_BYTES = 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("png", "jpeg");
    private final Path folder;

    public ProfileAvatars(Path folder) { this.folder = folder; }

    public static Path defaultFolder() { return ProfileStore.defaultFile().resolveSibling("avatars"); }

    /** Native file dialog; returns null when the user cancels. */
    public static Path choose() throws Exception {
        final Path[] selected = {null};
        EventQueue.invokeAndWait(() -> {
            var dialog = new FileDialog((Frame) null, "Картинка профиля — PNG или JPG", FileDialog.LOAD);
            dialog.setFilenameFilter((directory, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            });
            try {
                dialog.setVisible(true);
                if (dialog.getFile() != null) selected[0] = Path.of(dialog.getDirectory(), dialog.getFile());
            } finally { dialog.dispose(); }
        });
        return selected[0];
    }

    /**
     * Validates and decodes {@code source}, crops it to a centred square, scales it to {@link #SIZE}
     * and stores it for {@code profileId}. Returns a short content hash used as the avatar version.
     */
    public String store(String profileId, Path source) throws IOException {
        Path target = file(profileId);
        BufferedImage square = squareThumbnail(decode(source), SIZE);
        var buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(square, "png", buffer)) throw new IOException("Не удалось сохранить картинку профиля.");
        byte[] png = buffer.toByteArray();
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(target)) throw new IOException("Файл картинки профиля заменён ссылкой.");
        Path temp = Files.createTempFile(folder, "avatar-", ".tmp");
        try {
            Files.write(temp, png);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        return version(png);
    }

    /** Stored PNG bytes, or null when the profile has no readable avatar. */
    public byte[] read(String profileId) throws IOException {
        Path file = file(profileId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_STORED_BYTES) return null;
        return Files.readAllBytes(file);
    }

    public void delete(String profileId) throws IOException {
        Files.deleteIfExists(file(profileId));
    }

    Path file(String profileId) {
        if (profileId == null || !profileId.matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("Некорректный идентификатор профиля.");
        return folder.resolve(profileId + ".png");
    }

    /** Reads a PNG or JPEG after checking file size and header dimensions, so huge images are never decoded. */
    static BufferedImage decode(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Файл изображения не найден.");
        if (Files.size(source) > MAX_SOURCE_BYTES) throw new IOException("Изображение должно быть не больше 5 МБ.");
        try (var stream = ImageIO.createImageInputStream(source.toFile())) {
            if (stream == null) throw new IOException("Не удалось открыть изображение.");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Выберите изображение PNG или JPG.");
            var reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT)))
                    throw new IOException("Выберите изображение PNG или JPG.");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new IOException("Изображение пустое.");
                if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION)
                    throw new IOException("Размер изображения — не более 4096 × 4096.");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Не удалось прочитать изображение.");
                return image;
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                // Malformed files can make ImageIO plugins throw unchecked exceptions.
                throw new IOException("Изображение повреждено.", e);
            } finally { reader.dispose(); }
        }
    }

    /** Centre crop to a square, then scale to {@code size} (halving first for large sources to keep it smooth). */
    static BufferedImage squareThumbnail(BufferedImage image, int size) {
        int side = Math.min(image.getWidth(), image.getHeight());
        int x = (image.getWidth() - side) / 2;
        int y = (image.getHeight() - side) / 2;
        BufferedImage current = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        draw(current, image, 0, 0, side, side, x, y, x + side, y + side);
        while (current.getWidth() / 2 >= size) {
            int half = current.getWidth() / 2;
            BufferedImage next = new BufferedImage(half, half, BufferedImage.TYPE_INT_ARGB);
            draw(next, current, 0, 0, half, half, 0, 0, current.getWidth(), current.getHeight());
            current = next;
        }
        if (current.getWidth() == size) return current;
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        draw(result, current, 0, 0, size, size, 0, 0, current.getWidth(), current.getHeight());
        return result;
    }

    private static void draw(BufferedImage target, BufferedImage source, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
        } finally { graphics.dispose(); }
    }

    private static String version(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
