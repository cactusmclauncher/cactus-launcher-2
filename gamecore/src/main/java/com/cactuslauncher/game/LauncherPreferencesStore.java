package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

public final class LauncherPreferencesStore {
    public record Preferences(
            boolean setupComplete,
            boolean showPlayer,
            int parallelDownloads,
            int memoryGb,
            boolean automaticMemory,
            boolean autoJavaOptimization,
            boolean ghostMode,
            boolean fullscreen,
            boolean autoUpdate,
            boolean advancedMode,
            boolean translateModDescriptions,
            boolean modAntivirusEnabled,
            String resolution,
            String folder,
            String theme) {}

    public static final java.util.List<String> THEMES = java.util.List.of("system", "light", "dark");

    private final Path file;

    public LauncherPreferencesStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public synchronized Preferences load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Файл настроек лаунчера имеет неверный тип.");
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        try {
            if (!"1".equals(values.getProperty("format"))) throw new IllegalArgumentException();
            int downloads = Integer.parseInt(values.getProperty("parallelDownloads"));
            int memory = Integer.parseInt(values.getProperty("memoryGb"));
            String resolution = required(values, "resolution");
            String folder = required(values, "folder");
            String theme = values.getProperty("theme", "system");
            if (!THEMES.contains(theme)) theme = "system";
            if (downloads < 1 || downloads > 16 || memory < 2 || memory > 16
                    || !java.util.List.of("1280 × 720", "1600 × 900", "1920 × 1080", "2560 × 1440").contains(resolution))
                throw new IllegalArgumentException();
            return new Preferences(
                    bool(values, "setupComplete"), bool(values, "showPlayer"), downloads, memory,
                    bool(values, "automaticMemory"), bool(values, "autoJavaOptimization"),
                    bool(values, "ghostMode"), bool(values, "fullscreen"), optionalBool(values, "autoUpdate", true),
                    optionalBool(values, "advancedMode", false),
                    bool(values, "translateModDescriptions"), bool(values, "modAntivirusEnabled"), resolution, folder, theme);
        } catch (RuntimeException e) {
            throw new IOException("Файл настроек повреждён. Исходный файл сохранён.", e);
        }
    }

    public synchronized void save(Preferences value) throws IOException {
        if (value == null || value.parallelDownloads() < 1 || value.parallelDownloads() > 16
                || value.memoryGb() < 2 || value.memoryGb() > 16 || value.folder() == null || value.folder().isBlank()
                || !java.util.List.of("1280 × 720", "1600 × 900", "1920 × 1080", "2560 × 1440").contains(value.resolution())
                || !THEMES.contains(value.theme()))
            throw new IOException("Настройки лаунчера содержат недопустимые значения.");
        if (Files.isSymbolicLink(file)) throw new IOException("Файл настроек не должен быть символической ссылкой.");
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("format", "1");
        values.setProperty("setupComplete", Boolean.toString(value.setupComplete()));
        values.setProperty("showPlayer", Boolean.toString(value.showPlayer()));
        values.setProperty("parallelDownloads", Integer.toString(value.parallelDownloads()));
        values.setProperty("memoryGb", Integer.toString(value.memoryGb()));
        values.setProperty("automaticMemory", Boolean.toString(value.automaticMemory()));
        values.setProperty("autoJavaOptimization", Boolean.toString(value.autoJavaOptimization()));
        values.setProperty("ghostMode", Boolean.toString(value.ghostMode()));
        values.setProperty("fullscreen", Boolean.toString(value.fullscreen()));
        values.setProperty("autoUpdate", Boolean.toString(value.autoUpdate()));
        values.setProperty("advancedMode", Boolean.toString(value.advancedMode()));
        values.setProperty("translateModDescriptions", Boolean.toString(value.translateModDescriptions()));
        values.setProperty("modAntivirusEnabled", Boolean.toString(value.modAntivirusEnabled()));
        values.setProperty("resolution", value.resolution());
        values.setProperty("folder", value.folder());
        values.setProperty("theme", value.theme());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (var output = Files.newOutputStream(temp)) { values.store(output, null); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean bool(Properties values, String key) {
        String value = values.getProperty(key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }

    private static boolean optionalBool(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        if (value == null) return fallback;
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
}
