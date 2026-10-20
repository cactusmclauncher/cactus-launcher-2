package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;

public final class CrashRecovery {
    private static final int MAX_COPY_DEPTH = 32;
    private static final Set<String> COPIED_FOLDERS = Set.of("mods", "config", "defaultconfigs");

    public Path createWorkspace(Path buildRoot, Path recoveryRoot, String excludedMod) throws IOException {
        if (excludedMod == null || Path.of(excludedMod).getNameCount() != 1 || !excludedMod.toLowerCase().endsWith(".jar"))
            throw new IOException("Некорректное имя мода.");
        Path source = buildRoot.toAbsolutePath().normalize();
        Path base = recoveryRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(base)) throw new IOException("Каталог пробного запуска не должен быть символической ссылкой.");
        Files.createDirectories(base);
        Path workspace = base.resolve(UUID.randomUUID().toString());
        Files.createDirectory(workspace);
        try {
            for (String folderName : COPIED_FOLDERS) {
                Path from = source.resolve(folderName);
                if (Files.isSymbolicLink(from) || !Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) continue;
                Path to = workspace.resolve(folderName);
                if (folderName.equals("mods")) {
                    // Loaders read only the top level of mods/, so subfolders are not part of the trial run.
                    Files.createDirectory(to);
                    try (var paths = Files.list(from)) {
                        for (Path file : paths.toList()) {
                            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                            if (file.getFileName().toString().equals(excludedMod)) continue;
                            Files.copy(file, to.resolve(file.getFileName().toString()), StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    }
                } else copyTree(from, to);
            }
            Path options = source.resolve("options.txt");
            if (!Files.isSymbolicLink(options) && Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) Files.copy(options, workspace.resolve("options.txt"));
            return workspace;
        } catch (IOException | RuntimeException error) {
            deleteWorkspace(workspace);
            throw error;
        }
    }

    /** Copies a config tree with its subfolders. Symbolic links are skipped, never followed. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, java.util.EnumSet.noneOf(FileVisitOption.class), MAX_COPY_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return FileVisitResult.CONTINUE;
                Path target = to.resolve(from.relativize(file).toString()).normalize();
                if (!target.startsWith(to)) throw new IOException("Некорректный путь в папке настроек.");
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void deleteWorkspace(Path workspace) throws IOException {
        if (workspace == null || Files.isSymbolicLink(workspace) || !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        Path normalized = workspace.toAbsolutePath().normalize();
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(path);
        }
    }
}
