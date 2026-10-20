package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;

public final class CrashRecovery {
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
                Files.createDirectory(to);
                try (var paths = Files.list(from)) {
                    for (Path file : paths.toList()) {
                        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (folderName.equals("mods") && file.getFileName().toString().equals(excludedMod)) continue;
                        Files.copy(file, to.resolve(file.getFileName().toString()), StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            Path options = source.resolve("options.txt");
            if (!Files.isSymbolicLink(options) && Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) Files.copy(options, workspace.resolve("options.txt"));
            return workspace;
        } catch (IOException | RuntimeException error) {
            deleteWorkspace(workspace);
            throw error;
        }
    }

    public void deleteWorkspace(Path workspace) throws IOException {
        if (workspace == null || Files.isSymbolicLink(workspace) || !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) return;
        Path normalized = workspace.toAbsolutePath().normalize();
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(path);
        }
    }
}
