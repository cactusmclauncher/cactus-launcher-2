package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Backups of mod jars replaced by {@link ModUpdater}: {@code <build>/.mod-backups/<timestamp>/}
 * with the old jars and a {@code manifest.properties}. Rollback always undoes the newest update.
 */
public final class ModBackups {
    /** {@code confirmed}: a game session ended normally after this update. */
    public record Backup(String id, long createdMillis, int replaced, int added, boolean confirmed) {}

    static final int KEEP = 3;
    static final String MANIFEST = "manifest.properties";
    private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}-\\d{3}(-\\d{1,3})?");
    private static final Pattern BACKUP_FILE = Pattern.compile("replaced-\\d{1,4}\\.jar");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ModBackups() {}

    static Path root(Path buildFolder) throws IOException {
        Path root = buildFolder.resolve(".mod-backups");
        if (Files.isSymbolicLink(root)) throw new IOException("Папка резервных копий модов не должна быть символической ссылкой.");
        return root;
    }

    /** A new, empty backup folder named by the current time. */
    static Path create(Path buildFolder) throws IOException {
        Path root = Files.createDirectories(root(buildFolder));
        String base = LocalDateTime.now().format(FORMAT);
        for (int suffix = 0; suffix < 1000; suffix++) {
            Path folder = root.resolve(suffix == 0 ? base : base + "-" + suffix);
            try { return Files.createDirectory(folder); }
            catch (FileAlreadyExistsException ignored) { /* Two updates in the same millisecond. */ }
        }
        throw new IOException("Не удалось создать резервную копию модов.");
    }

    /** Backup ids, oldest first. */
    static List<String> ids(Path buildFolder) throws IOException {
        Path root = root(buildFolder);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(root)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(path.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).filter(name -> ID.matcher(name).matches())
                    .sorted(Comparator.comparing((String name) -> name.substring(0, 19)).thenComparingInt(ModBackups::suffix)).toList();
        }
    }

    private static int suffix(String id) { return id.length() > 19 ? Integer.parseInt(id.substring(20)) : 0; }

    public static Backup latest(Path buildFolder) throws IOException {
        List<String> ids = ids(buildFolder);
        if (ids.isEmpty()) return null;
        String id = ids.getLast();
        Properties manifest = readManifest(root(buildFolder).resolve(id));
        return new Backup(id, parseLong(manifest.getProperty("created")), count(manifest, "replaced"), count(manifest, "added"),
                Boolean.parseBoolean(manifest.getProperty("confirmed")));
    }

    /** Marks the newest update as working; it is then no longer offered for rollback after a crash. */
    public static void confirmLatest(Path buildFolder) throws IOException {
        List<String> ids = ids(buildFolder);
        if (ids.isEmpty()) return;
        Path folder = root(buildFolder).resolve(ids.getLast());
        Properties manifest = readManifest(folder);
        if (Boolean.parseBoolean(manifest.getProperty("confirmed"))) return;
        manifest.setProperty("confirmed", "true");
        writeManifest(folder, manifest);
    }

    /** Keeps the newest {@code keep} backups. */
    static void prune(Path buildFolder, int keep) throws IOException {
        List<String> ids = ids(buildFolder);
        for (int index = 0; index < ids.size() - keep; index++) ModrinthClient.deleteTree(root(buildFolder).resolve(ids.get(index)));
    }

    /**
     * Undoes the newest update: removes the dependencies it added and puts the replaced jars back,
     * keeping each mod enabled or disabled as it is now. Returns the number of restored jars.
     */
    public static int rollbackLatest(Path buildFolder) throws IOException {
        List<String> ids = ids(buildFolder);
        if (ids.isEmpty()) throw new IOException("Нет обновления модов, которое можно откатить.");
        Path folder = root(buildFolder).resolve(ids.getLast());
        Properties manifest = readManifest(folder);
        Path mods = buildFolder.resolve("mods");
        ModrinthClient.requireSafeModsDirectory(mods, true);
        Properties index = ModrinthClient.readIndex(mods);
        Path stage = Files.createTempDirectory(mods, ".mod-rollback-");
        var undo = new ArrayDeque<Path[]>();
        int restored = 0;
        try {
            for (int i = 0; i < count(manifest, "added"); i++) {
                String file = safeBase(manifest.getProperty("added." + i + ".file"));
                for (String name : List.of(file, file + ".disabled")) {
                    Path current = mods.resolve(name);
                    if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                        Path parked = stage.resolve("added-" + i + "-" + name);
                        ModrinthClient.move(current, parked);
                        undo.push(new Path[] {parked, current});
                    }
                }
                removeProject(index, manifest.getProperty("added." + i + ".project", ""));
            }
            for (int i = 0; i < count(manifest, "replaced"); i++) {
                String prefix = "replaced." + i + ".";
                String original = manifest.getProperty(prefix + "file", "");
                boolean wasDisabled = original.endsWith(".disabled");
                String originalBase = safeBase(wasDisabled ? original.substring(0, original.length() - ".disabled".length()) : original);
                String newBase = safeBase(manifest.getProperty(prefix + "new"));
                String backupName = manifest.getProperty(prefix + "backup", "");
                if (!BACKUP_FILE.matcher(backupName).matches()) throw new IOException("Резервная копия модов повреждена.");
                Path backup = folder.resolve(backupName);
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("В резервной копии не хватает файла " + originalBase + ".");
                boolean disabled = wasDisabled;
                for (String name : List.of(newBase, newBase + ".disabled")) {
                    Path current = mods.resolve(name);
                    if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                        disabled = name.endsWith(".disabled");
                        Path parked = stage.resolve("updated-" + i + "-" + name);
                        ModrinthClient.move(current, parked);
                        undo.push(new Path[] {parked, current});
                    }
                }
                Path target = mods.resolve(originalBase + (disabled ? ".disabled" : ""));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Файл уже существует: " + target.getFileName());
                ModrinthClient.move(backup, target);
                undo.push(new Path[] {target, backup});
                String project = manifest.getProperty(prefix + "project", "");
                if (Boolean.parseBoolean(manifest.getProperty(prefix + "managed"))) {
                    index.setProperty("mod." + project + ".file", originalBase);
                    index.setProperty("mod." + project + ".version", manifest.getProperty(prefix + "oldVersion", ""));
                    index.setProperty("mod." + project + ".hash", manifest.getProperty(prefix + "oldHash", ""));
                } else removeProject(index, project);
                restored++;
            }
            ModrinthClient.writeIndex(mods, index, stage);
        } catch (IOException | RuntimeException e) {
            while (!undo.isEmpty()) {
                Path[] step = undo.pop();
                try { ModrinthClient.move(step[0], step[1]); } catch (IOException restore) { e.addSuppressed(restore); }
            }
            ModrinthClient.deleteTree(stage);
            if (e instanceof IOException io) throw io;
            throw new IOException("Резервная копия модов повреждена.", e);
        }
        ModrinthClient.deleteTree(stage);
        ModrinthClient.deleteTree(folder);
        return restored;
    }

    static Properties readManifest(Path folder) throws IOException {
        Path file = folder.resolve(MANIFEST);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Резервная копия модов повреждена.");
        Properties manifest = new Properties();
        try (var input = Files.newInputStream(file)) { manifest.load(input); }
        return manifest;
    }

    static void writeManifest(Path folder, Properties manifest) throws IOException {
        Path temp = Files.createTempFile(folder, "manifest-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) { manifest.store(output, "Cactus Launcher mod update backup"); }
            ModrinthClient.move(temp, folder.resolve(MANIFEST));
        } finally { Files.deleteIfExists(temp); }
    }

    static void removeProject(Properties index, String projectId) {
        if (projectId.isEmpty()) return;
        String prefix = "mod." + projectId + ".";
        for (String key : index.stringPropertyNames()) if (key.startsWith(prefix)) index.remove(key);
    }

    private static int count(Properties manifest, String kind) {
        int value = (int) parseLong(manifest.getProperty(kind + ".count"));
        return Math.max(0, Math.min(value, 4096));
    }

    private static long parseLong(String value) {
        try { return value == null ? 0 : Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String safeBase(String name) throws IOException {
        if (!ModrinthClient.safeFileName(name)) throw new IOException("Резервная копия модов повреждена.");
        return name;
    }
}
