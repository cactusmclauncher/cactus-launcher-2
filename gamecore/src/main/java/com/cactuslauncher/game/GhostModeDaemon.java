package com.cactuslauncher.game;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GhostModeDaemon {
    private GhostModeDaemon() {}

    public static void main(String[] arguments) {
        if (arguments.length != 2) return;
        Path handoff = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path status = Path.of(arguments[1]).toAbsolutePath().normalize();
        boolean gameStarted = false;
        boolean startupConfirmed = false;
        Process game = null;
        try {
            Handoff data = read(handoff);
            Files.deleteIfExists(handoff);
            game = new ProcessBuilder(data.gameCommand())
                    .directory(data.gameDirectory().toFile())
                    .redirectErrorStream(true)
                    .start();
            gameStarted = true;
            writeStatus(status, "OK");
            startupConfirmed = true;
            writeGameOutput(game, data.logFile());
            game.waitFor();
            waitForStatusAcknowledgement(status);
            Files.deleteIfExists(status);
            new ProcessBuilder(data.launcherCommand())
                    .directory(data.launcherDirectory().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception error) {
            if (!gameStarted) {
                try { writeStatus(status, "ERROR " + safeMessage(error)); }
                catch (IOException ignored) {}
            } else if (!startupConfirmed && game != null) {
                game.destroyForcibly();
                try { writeStatus(status, "ERROR " + safeMessage(error)); }
                catch (IOException ignored) {}
            }
        } finally {
            try { Files.deleteIfExists(handoff); } catch (IOException ignored) {}
        }
    }

    private static Handoff read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 8L * 1024 * 1024)
            throw new IOException("Некорректные данные Ghost Mode.");
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            Path gameDirectory = directory(readString(input));
            Path logFile = Path.of(readString(input)).toAbsolutePath().normalize();
            List<String> gameCommand = readCommand(input);
            Path launcherDirectory = directory(readString(input));
            List<String> launcherCommand = readCommand(input);
            if (input.available() != 0 || logFile.getParent() == null || !Files.isDirectory(logFile.getParent()))
                throw new IOException("Данные Ghost Mode повреждены.");
            return new Handoff(gameDirectory, logFile, gameCommand, launcherDirectory, launcherCommand);
        }
    }

    private static Path directory(String value) throws IOException {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) throw new IOException("Папка Ghost Mode недоступна.");
        return path;
    }

    private static List<String> readCommand(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 1 || count > 2048) throw new IOException("Команда Ghost Mode повреждена.");
        var command = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) command.add(readString(input));
        return List.copyOf(command);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 2 * 1024 * 1024) throw new IOException("Данные Ghost Mode повреждены.");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Данные Ghost Mode повреждены.");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeGameOutput(Process game, Path logFile) throws InterruptedException {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(game.getInputStream(), StandardCharsets.UTF_8));
             var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
                     java.nio.file.StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase(java.util.Locale.ROOT).contains("multiplayer is disabled")) continue;
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ignored) {
            try { game.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignoredAgain) {}
        }
    }

    private static void writeStatus(Path status, String value) throws IOException {
        Path temporary = Files.createTempFile(status.getParent(), "cactus-ghost-status-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try { Files.move(temporary, status, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, status, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void waitForStatusAcknowledgement(Path status) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(status) && System.nanoTime() < deadline) {
            try { Thread.sleep(25); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return (message == null || message.isBlank() ? "Не удалось запустить игру в Ghost Mode." : message)
                .replace('\n', ' ').replace('\r', ' ');
    }

    private record Handoff(Path gameDirectory, Path logFile, List<String> gameCommand,
                           Path launcherDirectory, List<String> launcherCommand) {}
}
