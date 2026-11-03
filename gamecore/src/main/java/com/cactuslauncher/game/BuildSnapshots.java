package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class BuildSnapshots {
    public record Snapshot(String id, long createdMillis, String label, int files, long uniqueBytes) {}
    private record Entry(String hash, long size) {}
    private static final String ROOT = ".cactus-snapshots";
    private static final Set<String> EXCLUDED = Set.of("logs", "crash-reports", "screenshots", "cache", ".cactus-snapshots", "saves");

    private BuildSnapshots() {}

    public static List<Snapshot> list(Path build) throws IOException {
        Path root = root(build);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path snapshots = root.resolve("snapshots");
        if (!Files.isDirectory(snapshots, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(snapshots)) return List.of();
        List<Snapshot> result = new ArrayList<>();
        try (var stream = Files.list(snapshots)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                Properties p = read(file);
                String id = file.getFileName().toString().replaceFirst("\\.properties$", "");
                result.add(new Snapshot(id, Long.parseLong(p.getProperty("created")), p.getProperty("label", "Точка восстановления"), Integer.parseInt(p.getProperty("count", "0")), Long.parseLong(p.getProperty("bytes", "0"))));
            }
        }
        return List.copyOf(result);
    }

    public static Snapshot create(Path build, String label) throws IOException {
        Path root = root(build);
        Files.createDirectories(root.resolve("objects"));
        Files.createDirectories(root.resolve("snapshots"));
        Map<String, Entry> entries = scan(build);
        Map<String, Path> sourceByHash = new HashMap<>();
        for (String relative : entries.keySet()) sourceByHash.putIfAbsent(entries.get(relative).hash(), build.resolve(relative));
        long unique = 0;
        for (Entry entry : entries.values()) {
            Path object = object(root, entry.hash());
            if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(object.getParent());
                Path temp = Files.createTempFile(object.getParent(), "object-", ".tmp");
                try {
                    Path source = sourceByHash.get(entry.hash());
                    Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                    if (!hash(temp).equals(entry.hash())) throw new IOException("Файл изменился во время создания точки. Повторите операцию.");
                    move(temp, object);
                } finally { Files.deleteIfExists(temp); }
                unique += entry.size();
            }
        }
        String id = UUID.randomUUID().toString();
        Properties p = new Properties();
        p.setProperty("format", "1"); p.setProperty("created", Long.toString(System.currentTimeMillis()));
        p.setProperty("label", label == null || label.isBlank() ? "Точка восстановления" : label.strip().substring(0, Math.min(label.strip().length(), 80)));
        p.setProperty("count", Integer.toString(entries.size())); p.setProperty("bytes", Long.toString(unique));
        entries.forEach((path, entry) -> { p.setProperty("file." + encode(path), entry.hash()); p.setProperty("size." + encode(path), Long.toString(entry.size())); });
        write(root.resolve("snapshots").resolve(id + ".properties"), p);
        return new Snapshot(id, Long.parseLong(p.getProperty("created")), p.getProperty("label"), entries.size(), unique);
    }

    public static void restore(Path build, String id) throws IOException {
        Path root = root(build);
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IOException("Некорректная точка восстановления.");
        Path manifest = root.resolve("snapshots").resolve(id + ".properties");
        Properties p = read(manifest);
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Формат точки восстановления не поддерживается.");
        Map<String, String> wanted = new TreeMap<>();
        for (String key : p.stringPropertyNames()) if (key.startsWith("file.")) {
            String relative = decode(key.substring(5));
            if (!safeRelative(relative)) throw new IOException("Точка восстановления содержит небезопасный путь.");
            String hash = p.getProperty(key);
            if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Точка восстановления повреждена.");
            Path object = object(root, hash);
            if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) || !hash(object).equals(hash)) throw new IOException("В хранилище не хватает файла " + relative + ".");
            wanted.put(relative, hash);
        }
        Map<String, Entry> current = scan(build);
        Snapshot safety = create(build, "Перед откатом");
        Path stage = Files.createTempDirectory(build, ".cactus-restore-");
        boolean mutationStarted = false;
        try {
            for (var e : wanted.entrySet()) {
                Path target = stage.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.copy(object(root, e.getValue()), target);
            }
            for (String path : wanted.keySet()) resolveSafe(build, path);
            for (String relative : current.keySet()) {
                if (!wanted.containsKey(relative)) Files.deleteIfExists(resolveSafe(build, relative));
            }
            for (String relative : wanted.keySet()) {
                Path target = resolveSafe(build, relative);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) throw new IOException("В сборке обнаружена символическая ссылка: " + relative);
                Files.createDirectories(target.getParent());
                move(stage.resolve(relative), target);
            }
            mutationStarted = true;
        } catch (IOException | RuntimeException e) {
            if (mutationStarted) {
                try { restoreWithoutBackup(build, safety.id()); } catch (Exception rollback) { e.addSuppressed(rollback); }
            }
            throw e instanceof IOException io ? io : new IOException("Не удалось восстановить точку.", e);
        } finally { deleteTree(stage); }
    }

    private static void restoreWithoutBackup(Path build, String id) throws IOException {
        Path root = root(build);
        Properties p = read(root.resolve("snapshots").resolve(id + ".properties"));
        Map<String, String> wanted = new TreeMap<>();
        for (String key : p.stringPropertyNames()) if (key.startsWith("file.")) wanted.put(decode(key.substring(5)), p.getProperty(key));
        Map<String, Entry> current = scan(build);
        for (String relative : current.keySet()) if (!wanted.containsKey(relative)) Files.deleteIfExists(resolveSafe(build, relative));
        for (var e : wanted.entrySet()) {
            Path target = resolveSafe(build, e.getKey());
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "restore-", ".tmp");
            try { Files.copy(object(root, e.getValue()), temp, StandardCopyOption.REPLACE_EXISTING); move(temp, target); }
            finally { Files.deleteIfExists(temp); }
        }
    }

    private static Map<String, Entry> scan(Path build) throws IOException {
        Path base = build.toRealPath();
        Map<String, Entry> result = new TreeMap<>();
        try (var stream = Files.walk(build)) {
            for (Path file : stream.filter(p -> !p.equals(build)).sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Символические ссылки не входят в точки восстановления: " + build.relativize(file));
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                Path relativePath = build.relativize(file);
                String relative = relativePath.toString().replace('\\', '/');
                if (excluded(relative)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(base)) throw new IOException("Небезопасный файл в сборке: " + relative);
                result.put(relative, new Entry(hash(file), Files.size(file)));
            }
        }
        return result;
    }

    private static boolean excluded(String relative) {
        String first = relative.substring(0, relative.indexOf('/') < 0 ? relative.length() : relative.indexOf('/')).toLowerCase(Locale.ROOT);
        return EXCLUDED.contains(first) || relative.equals(".cactus-last-launch");
    }
    private static boolean safeRelative(String s) { return !s.isBlank() && !s.startsWith("/") && !s.contains("\\") && Arrays.stream(s.split("/")).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")); }
    private static Path resolveSafe(Path build, String relative) throws IOException {
        if (!safeRelative(relative)) throw new IOException("Небезопасный путь.");
        Path target = build.resolve(relative).normalize();
        if (!target.startsWith(build.normalize())) throw new IOException("Небезопасный путь.");
        Path cursor = build;
        for (Path part : build.relativize(target)) { cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor)) throw new IOException("Символическая ссылка заблокировала восстановление: " + relative); }
        return target;
    }
    private static Path root(Path build) throws IOException {
        if (Files.isSymbolicLink(build)) throw new IOException("Папка сборки не должна быть символической ссылкой.");
        Path root = build.resolve(ROOT);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(root.resolve("objects")) || Files.isSymbolicLink(root.resolve("snapshots"))) throw new IOException("Хранилище точек повреждено.");
        return root;
    }

    private static Path object(Path root, String hash) { return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash); }
    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String encode(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String decode(String s) throws IOException { try { return new String(Base64.getUrlDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { throw new IOException("Точка восстановления повреждена.", e); } }
    private static Properties read(Path file) throws IOException { if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Точка восстановления не найдена или повреждена."); Properties p = new Properties(); try (var in = Files.newInputStream(file)) { p.load(in); } return p; }
    private static void write(Path file, Properties p) throws IOException { Path temp = Files.createTempFile(file.getParent(), "snapshot-", ".tmp"); try { try (var out = Files.newOutputStream(temp)) { p.store(out, null); } move(temp, file); } finally { Files.deleteIfExists(temp); } }
    private static void move(Path from, Path to) throws IOException { try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); } }
    private static void deleteTree(Path path) throws IOException { if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return; try (var paths = Files.walk(path)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
}
