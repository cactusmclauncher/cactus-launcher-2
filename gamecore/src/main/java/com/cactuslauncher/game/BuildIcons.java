package com.cactuslauncher.game;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class BuildIcons {
    public static boolean choose(Path folder) throws Exception {
        final Path[] selected = {null};
        EventQueue.invokeAndWait(() -> {
            var dialog = new FileDialog((Frame) null, "Иконка сборки — PNG или JPG", FileDialog.LOAD);
            try {
                dialog.setVisible(true);
                if (dialog.getFile() != null) selected[0] = Path.of(dialog.getDirectory(), dialog.getFile());
            } finally { dialog.dispose(); }
        });
        if (selected[0] == null) return false;
        if (Files.size(selected[0]) > 8 * 1024 * 1024) throw new IOException("Изображение должно быть меньше 8 МБ.");
        BufferedImage image;
        try (var stream = ImageIO.createImageInputStream(selected[0].toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Выберите изображение PNG или JPG.");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                if (reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096) throw new IOException("Размер изображения — не более 4096 × 4096.");
                image = reader.read(0);
            } finally { reader.dispose(); }
        }
        var resized = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = resized.createGraphics();
        try {
            int size = Math.min(image.getWidth(), image.getHeight());
            int x = (image.getWidth() - size) / 2;
            int y = (image.getHeight() - size) / 2;
            graphics.drawImage(image, 0, 0, 256, 256, x, y, x + size, y + size, null);
        } finally { graphics.dispose(); }
        Path temp = Files.createTempFile(folder, "icon-", ".png");
        try {
            ImageIO.write(resized, "png", temp.toFile());
            try { Files.move(temp, folder.resolve("icon.png"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, folder.resolve("icon.png"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        return true;
    }

    public static byte[] read(Path folder) throws IOException {
        Path file = folder.resolve("icon.png");
        if (!Files.isRegularFile(file) || Files.size(file) > 8 * 1024 * 1024) return null;
        return Files.readAllBytes(file);
    }
}
