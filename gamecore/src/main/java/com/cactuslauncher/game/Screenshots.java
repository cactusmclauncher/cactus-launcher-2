package com.cactuslauncher.game;

import java.awt.Desktop;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;

/**
 * Screenshots of a build (its game folder's "screenshots"): listing, downscaled thumbnails with a disk
 * cache, and deletion. Only regular files directly inside the folder are used; symlinks are never followed.
 */
public final class Screenshots {
    private Screenshots() {}

    public record Shot(String fileName, long modifiedMillis, long size) {}

    private static final int MAX_CACHE_FILES = 3000;

    public static Path folder(Path buildFolder) throws IOException {
        Path folder = buildFolder.resolve("screenshots");
        if (Files.isSymbolicLink(buildFolder) || Files.isSymbolicLink(folder))
            throw new IOException("Папка скриншотов не должна быть символической ссылкой.");
        return folder;
    }

    /** PNG and JPEG files of the folder, newest first. */
    public static List<Shot> list(Path buildFolder) throws IOException {
        Path folder = folder(buildFolder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var shots = new ArrayList<Shot>();
        try (var files = Files.list(folder)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (!imageName(name)) continue;
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (IOException e) { continue; }
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) continue;
                shots.add(new Shot(name, attributes.lastModifiedTime().toMillis(), attributes.size()));
            }
        }
        shots.sort(Comparator.comparingLong(Shot::modifiedMillis).reversed().thenComparing(Shot::fileName, Comparator.reverseOrder()));
        return List.copyOf(shots);
    }

    /** The file [fileName] inside the screenshots folder; refuses paths, symlinks and non-images. */
    public static Path resolve(Path buildFolder, String fileName) throws IOException {
        if (fileName == null || !imageName(fileName)) throw new IOException("Некорректное имя скриншота.");
        Path folder = folder(buildFolder).toAbsolutePath().normalize();
        Path file;
        try {
            if (!Path.of(fileName).getFileName().toString().equals(fileName)) throw new IOException("Некорректное имя скриншота.");
            file = folder.resolve(fileName).normalize();
        } catch (InvalidPathException e) { throw new IOException("Некорректное имя скриншота.", e); }
        if (!file.getParent().equals(folder)) throw new IOException("Скриншот находится вне папки сборки.");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Скриншот не найден.");
        return file;
    }

    static boolean imageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && !name.contains("/") && !name.contains("\\")
                && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"));
    }

    /**
     * JPEG thumbnail of [file] no larger than [maxSize] px, from [cacheFolder] when the file is unchanged
     * (key: absolute path, modification time, size and [maxSize]); [cacheFolder] may be null.
     */
    public static byte[] thumbnail(Path file, int maxSize, Path cacheFolder) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Скриншот не найден.");
        Path cached = null;
        if (cacheFolder != null && !Files.isSymbolicLink(cacheFolder)) {
            String key = sha256(file.toAbsolutePath().normalize() + "|" + attributes.lastModifiedTime().toMillis() + "|" + attributes.size() + "|" + maxSize);
            cached = cacheFolder.resolve(key + ".jpg");
            if (!Files.isSymbolicLink(cached) && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
                try { return Files.readAllBytes(cached); } catch (IOException ignored) { }
            }
        }
        BufferedImage image = readScaled(file, maxSize);
        byte[] jpeg = jpeg(image, 0.85f);
        if (cached != null) {
            try {
                Files.createDirectories(cacheFolder);
                Path temp = Files.createTempFile(cacheFolder, "thumb-", ".tmp");
                try {
                    Files.write(temp, jpeg);
                    try { Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                pruneCache(cacheFolder);
            } catch (IOException ignored) {
                // The cache is an optimisation; a read-only disk still shows thumbnails.
            }
        }
        return jpeg;
    }

    /** Decodes [file] with subsampling so a 4K screenshot never has to be held at full size, then scales it smoothly. */
    public static BufferedImage readScaled(Path file, int maxSize) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Не удалось открыть скриншот.");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Формат скриншота не поддерживается.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((long) width * height > 100_000_000L) throw new IOException("Скриншот слишком большой.");
                int step = Math.max(1, Math.min(width, height) == 0 ? 1 : Math.max(width, height) / (maxSize * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                return scale(decoded, maxSize);
            } finally { reader.dispose(); }
        }
    }

    static BufferedImage scale(BufferedImage source, int maxSize) {
        int width = source.getWidth(), height = source.getHeight();
        double factor = Math.min(1.0, (double) maxSize / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor)), targetHeight = Math.max(1, (int) Math.round(height * factor));
        var result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.BLACK);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally { graphics.dispose(); }
        return result;
    }

    static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG не поддерживается.");
        var writer = writers.next();
        var output = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally { writer.dispose(); }
        return output.toByteArray();
    }

    /** Moves the screenshot to the system trash when supported, otherwise deletes it; returns true if trashed. */
    public static boolean delete(Path buildFolder, String fileName) throws IOException {
        return delete(buildFolder, fileName, true);
    }

    static boolean delete(Path buildFolder, String fileName, boolean trash) throws IOException {
        Path file = resolve(buildFolder, fileName);
        if (trash && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            if (Desktop.getDesktop().moveToTrash(file.toFile())) return true;
        }
        Files.delete(file);
        return false;
    }

    private static void pruneCache(Path cacheFolder) throws IOException {
        List<Path> files;
        try (var paths = Files.list(cacheFolder)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".jpg") && !Files.isSymbolicLink(path)).toList();
        }
        if (files.size() <= MAX_CACHE_FILES) return;
        var sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingLong(path -> {
            try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); } catch (IOException e) { return 0L; }
        }));
        for (Path path : sorted.subList(0, sorted.size() - MAX_CACHE_FILES * 3 / 4)) Files.deleteIfExists(path);
    }

    private static String sha256(String value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
