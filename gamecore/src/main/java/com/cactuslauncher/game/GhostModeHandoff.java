package com.cactuslauncher.game;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;

public final class GhostModeHandoff {
    private GhostModeHandoff() {}

    public static void start(List<String> gameCommand, Path gameDirectory, Path logFile,
                             JavaOptimizer.Machine machine) throws IOException {
        Path handoff = Files.createTempFile("cactus-ghost-", ".handoff");
        Path status = Files.createTempFile("cactus-ghost-", ".status");
        Files.deleteIfExists(status);
        try {
            try { Files.setPosixFilePermissions(handoff, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException | IOException ignored) {}
            try { Files.setPosixFilePermissions(status, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException | IOException ignored) {}
            List<String> launcherCommand = launcherCommand();
            if (launcherCommand.isEmpty()) throw new IOException("Не удалось определить команду перезапуска лаунчера.");
            try (var output = new DataOutputStream(Files.newOutputStream(handoff))) {
                writeString(output, gameDirectory.toAbsolutePath().normalize().toString());
                writeString(output, logFile.toAbsolutePath().normalize().toString());
                writeCommand(output, gameCommand);
                writeString(output, System.getProperty("user.dir", "."));
                writeCommand(output, launcherCommand);
            }
            var command = new ArrayList<String>();
            command.add(javaExecutable().toString());
            command.addAll(new JavaOptimizer().daemonMemoryPlan(machine).jvmArguments());
            command.add("-cp");
            command.add(System.getProperty("java.class.path", ""));
            command.add(GhostModeDaemon.class.getName());
            command.add(handoff.toString());
            command.add(status.toString());
            Process daemon = new ProcessBuilder(command)
                    .directory(Path.of(System.getProperty("user.dir", ".")).toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(status)) {
                    String result = Files.readString(status, StandardCharsets.UTF_8).trim();
                    if (result.equals("OK")) return;
                    if (result.startsWith("ERROR")) throw new IOException(result.substring("ERROR".length()).trim());
                }
                if (!daemon.isAlive()) throw new IOException("Фоновый Ghost Mode процесс завершился при запуске.");
                try { Thread.sleep(40); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Запуск Ghost Mode прерван.", e);
                }
            }
            throw new IOException("Ghost Mode не подтвердил запуск игры за отведённое время.");
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(handoff);
            if (e instanceof IOException io) throw io;
            throw new IOException("Не удалось включить Ghost Mode.", e);
        } finally {
            Files.deleteIfExists(status);
        }
    }

    private static List<String> launcherCommand() {
        String javaCommand = System.getProperty("sun.java.command", "").trim();
        if (javaCommand.isEmpty()) return List.of();
        String main = javaCommand.split("\\s+", 2)[0];
        Optional<String[]> processArguments = ProcessHandle.current().info().arguments();
        if (processArguments.isPresent()) {
            String[] arguments = processArguments.get();
            for (String argument : arguments) {
                if (argument.equals(main)) {
                    var command = new ArrayList<String>();
                    command.add(javaExecutable().toString());
                    command.addAll(List.of(arguments));
                    return command;
                }
            }
        }
        var command = new ArrayList<String>();
        command.add(javaExecutable().toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (main.endsWith(".jar")) {
            command.add("-jar");
            command.add(main);
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path", ""));
            command.add(main);
        }
        return command;
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toAbsolutePath().normalize();
    }

    private static void writeCommand(DataOutputStream output, List<String> command) throws IOException {
        if (command.isEmpty() || command.size() > 2048) throw new IOException("Некорректная команда Ghost Mode.");
        output.writeInt(command.size());
        for (String argument : command) writeString(output, argument);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("Данные Ghost Mode слишком большие.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
