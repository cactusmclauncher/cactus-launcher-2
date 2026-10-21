package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class BuildStore {
    /** {@code autoUpdateMods}: check Modrinth and install compatible mod updates before each launch (off by default). */
    public record Build(String id, String name, String version, String loader, String icon, boolean autoUpdateMods) {
        public Build(String id, String name, String version, String loader, String icon) { this(id, name, version, loader, icon, false); }
    }
    private final Path file;
    private final Path directory;

    public BuildStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.directory = this.file.getParent().resolve("instances");
    }

    public synchronized List<Build> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        try {
            var properties = new Properties();
            try (var input = Files.newInputStream(file)) { properties.load(input); }
            if (!"1".equals(properties.getProperty("format"))) throw new IllegalArgumentException();
            var result = new ArrayList<Build>();
            var ids = new HashSet<String>();
            var names = new HashSet<String>();
            String order = properties.getProperty("order");
            if (order == null) throw new IllegalArgumentException();
            if (!order.isEmpty()) for (String id : order.split(",", -1)) {
                if (!UUID.fromString(id).toString().equals(id) || !ids.add(id)) throw new IllegalArgumentException();
                String prefix = "build." + id + ".";
                String name = validName(properties.getProperty(prefix + "name"));
                String version = validVersion(properties.getProperty(prefix + "version"));
                String loader = validLoader(properties.getProperty(prefix + "loader"));
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
                String icon = properties.getProperty(prefix + "icon", "grass");
                if (!List.of("grass", "dirt", "stone", "diamond", "snapshot", "custom").contains(icon)) throw new IllegalArgumentException();
                result.add(new Build(id, name, version, loader, icon, Boolean.parseBoolean(properties.getProperty(prefix + "autoUpdateMods"))));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new IOException("Файл сборок повреждён. Исходный файл сохранён.", e);
        }
    }

    public synchronized List<Build> create(String name, String version, String loader) throws IOException {
        var builds = new ArrayList<>(load());
        name = uniqueName(name, builds, null);
        var build = new Build(UUID.randomUUID().toString(), name, validVersion(version), validLoader(loader), "grass");
        Path folder = directory.resolve(build.id());
        if (Files.isSymbolicLink(directory)) throw new IOException("Папка сборок не должна быть символической ссылкой.");
        Files.createDirectories(directory);
        Files.createDirectory(folder);
        try {
            builds.add(build);
            save(builds);
        } catch (IOException e) {
            try { Files.delete(folder); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return List.copyOf(builds);
    }

    public synchronized List<Build> rename(String id, String name) throws IOException {
        var builds = load();
        require(builds, id);
        String valid = uniqueName(name, builds, id);
        var next = builds.stream().map(b -> b.id().equals(id) ? new Build(id, valid, b.version(), b.loader(), b.icon(), b.autoUpdateMods()) : b).toList();
        save(next);
        return next;
    }

    public synchronized List<Build> delete(String id) throws IOException {
        return delete(id, false);
    }

    public synchronized List<Build> delete(String id, boolean deleteFiles) throws IOException {
        var builds = load();
        require(builds, id);
        var next = builds.stream().filter(b -> !b.id().equals(id)).toList();
        save(next);
        if (deleteFiles) deleteInstance(id);
        return next;
    }

    private void deleteInstance(String id) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Папка сборок не должна быть символической ссылкой.");
        Path instance = directory.resolve(id).normalize();
        if (!instance.getParent().equals(directory) || Files.isSymbolicLink(instance) || !Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(instance)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public synchronized List<Build> setIcon(String id, String icon) throws IOException {
        if (!List.of("grass", "dirt", "stone", "diamond", "snapshot", "custom").contains(icon)) throw new IllegalArgumentException("Неизвестная иконка.");
        var builds = load();
        require(builds, id);
        var next = builds.stream().map(b -> b.id().equals(id) ? new Build(id, b.name(), b.version(), b.loader(), icon, b.autoUpdateMods()) : b).toList();
        save(next);
        return next;
    }

    public synchronized List<Build> setAutoUpdateMods(String id, boolean enabled) throws IOException {
        var builds = load();
        require(builds, id);
        var next = builds.stream().map(b -> b.id().equals(id) ? new Build(id, b.name(), b.version(), b.loader(), b.icon(), enabled) : b).toList();
        save(next);
        return next;
    }

    public synchronized Path folder(String id) throws IOException {
        require(load(), id);
        Path folder = directory.resolve(id);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(folder)) throw new IOException("Папка сборки не должна быть символической ссылкой.");
        Files.createDirectories(folder);
        return folder;
    }

    /** Records a launch attempt; kept as a marker file so builds.properties stays unchanged. */
    public synchronized void markLaunched(String id) throws IOException {
        Path marker = folder(id).resolve(".cactus-last-launch");
        if (Files.isSymbolicLink(marker)) throw new IOException("Файл запуска не должен быть символической ссылкой.");
        Files.writeString(marker, Long.toString(System.currentTimeMillis()));
    }

    /** Epoch millis of the last launch attempt, or 0 if the build was never launched. */
    public synchronized long lastLaunched(String id) {
        Path marker = directory.resolve(id).resolve(".cactus-last-launch");
        try {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return 0;
            return Long.parseLong(Files.readString(marker).trim());
        } catch (IOException | NumberFormatException ignored) { return 0; }
    }

    public synchronized Path logFile(String id) throws IOException {
        Path buildFolder = folder(id);
        Path logsFolder = buildFolder.resolve("logs");
        Path logFile = logsFolder.resolve("cactus-launch.log");
        if (Files.isSymbolicLink(logsFolder) || Files.isSymbolicLink(logFile))
            throw new IOException("Файл лога не должен быть символической ссылкой.");
        return logFile;
    }

    public synchronized List<String> installedMods(String id) throws IOException {
        Path mods = folder(id).resolve("mods");
        if (Files.isSymbolicLink(mods)) throw new IOException("Папка модов не должна быть символической ссылкой.");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.list(mods)) {
            return paths.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> path.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public synchronized Path modsFolder(String id) throws IOException {
        Path mods = folder(id).resolve("mods");
        if (Files.isSymbolicLink(mods)) throw new IOException("Папка модов не должна быть символической ссылкой.");
        Files.createDirectories(mods);
        return mods;
    }

    /** Complete log lines appended since {@code offset}; see {@link LogTail}. */
    public synchronized LogTail.Chunk readLogChunk(String id, long offset, int maxBytes) throws IOException {
        return LogTail.read(logFile(id), offset, maxBytes);
    }

    private static void require(List<Build> builds, String id) {
        if (builds.stream().noneMatch(b -> b.id().equals(id))) throw new IllegalArgumentException("Сборка не найдена.");
    }

    private static String uniqueName(String name, List<Build> builds, String excluded) {
        String value = validName(name);
        if (builds.stream().anyMatch(b -> !b.id().equals(excluded) && b.name().equalsIgnoreCase(value)))
            throw new IllegalArgumentException("Сборка с таким названием уже существует.");
        return value;
    }

    private static String validName(String value) {
        if (value == null) throw new IllegalArgumentException("Укажите название сборки.");
        value = value.strip();
        if (value.isEmpty() || value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Название сборки: от 1 до 64 символов без управляющих символов.");
        return value;
    }

    private static String validVersion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.+ -]{0,99}")) throw new IllegalArgumentException("Некорректная версия Minecraft.");
        return value;
    }

    private static String validLoader(String value) {
        if (!List.of("Vanilla", "Fabric", "Forge").contains(value)) throw new IllegalArgumentException("Неизвестный загрузчик.");
        return value;
    }

    private void save(List<Build> builds) throws IOException {
        Files.createDirectories(file.getParent());
        var properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("order", String.join(",", builds.stream().map(Build::id).toList()));
        for (var build : builds) {
            String prefix = "build." + build.id() + ".";
            properties.setProperty(prefix + "name", build.name());
            properties.setProperty(prefix + "version", build.version());
            properties.setProperty(prefix + "loader", build.loader());
            properties.setProperty(prefix + "icon", build.icon());
            if (build.autoUpdateMods()) properties.setProperty(prefix + "autoUpdateMods", "true");
        }
        Path temp = Files.createTempFile(file.getParent(), "builds-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) { properties.store(output, null); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
