package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class HorizonRuntime {
    public record Result(int exitCode, String logPath) {}
    record Layout(Path game, Path player, Path home, Path log) {}
    @FunctionalInterface interface Processes { Process start(ProcessBuilder builder) throws IOException; }
    private final Processes processes;
    private boolean reserved;

    public HorizonRuntime() { this(ProcessBuilder::start); }
    HorizonRuntime(Processes processes) { this.processes = processes; }
    synchronized void reserve() throws IOException {
        if (reserved) throw new IOException("Horizon уже запускается или работает.");
        reserved = true;
    }
    synchronized void release() { reserved = false; }
    public synchronized boolean active() { return reserved; }

    static Layout layout(Path base, ProfileStore.Profile profile) throws IOException {
        if (profile == null || profile.id() == null || !profile.id().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                || profile.name() == null || !profile.name().matches("[A-Za-z0-9_]{3,16}"))
            throw new IOException("Выберите корректный локальный профиль Cactus.");
        Path root = base.toAbsolutePath().normalize().resolve(profile.id().toLowerCase(Locale.ROOT));
        Path game = root.resolve("game"), player = root.resolve("player"), home = root.resolve("runtime");
        for (Path dir : List.of(game, player, home)) HorizonInstaller.secureDirectory(dir);
        for (String file : List.of("options.txt", "horizon-player.txt", "cactus-launch.log")) {
            Path path = player.resolve(file);
            HorizonInstaller.secureExisting(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) HorizonInstaller.regular(path);
        }
        Path temporary = Files.createTempFile(player, ".name-", ".tmp");
        try {
            Files.writeString(temporary, "name:" + profile.name() + "\n", StandardCharsets.UTF_8);
            try { Files.move(temporary, player.resolve("horizon-player.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, player.resolve("horizon-player.txt"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return new Layout(game, player, home, player.resolve("cactus-launch.log"));
    }

    static List<String> command(Path executable, Path resources, Layout layout) {
        return List.of(executable.toString(), "--resources", resources.toString(), "--game-dir", layout.game().toString(),
                "--home", layout.home().toString(), "--player-dir", layout.player().toString());
    }

    static ProcessBuilder builder(Path root, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.startsWith("HORIZON_") || upper.contains("TOKEN") || upper.contains("PASSWORD") || upper.contains("SECRET")
                    || (upper.contains("AUTH") && !upper.equals("XAUTHORITY")) || upper.contains("COOKIE") || List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES").contains(upper);
        });
        return builder;
    }

    static void validateApi(String text) throws IOException {
        try {
            JsonObject api = JsonParser.parseString(text).getAsJsonObject();
            if (!api.get("launch_api").getAsString().equals("0.1.0")) throw new IOException("Неподдерживаемый Launch API Horizon.");
            Set<String> options = new HashSet<>();
            for (JsonElement element : api.getAsJsonArray("options")) options.add(element.getAsJsonObject().get("name").getAsString());
            if (!options.containsAll(List.of("--resources", "--game-dir", "--home", "--player-dir"))) throw new IOException("Horizon не поддерживает параметры автономного запуска.");
        } catch (RuntimeException failure) { throw new IOException("Некорректный Launch API Horizon.", failure); }
    }

    Result run(Path root, Path resources, Path profiles, ProfileStore.Profile profile, Consumer<String> running) throws IOException {
        Path executable = root.resolve(HorizonInstaller.binary("horizon-client"));
        HorizonInstaller.regular(executable);
        HorizonInstaller.secureExisting(resources);
        if (!Files.isDirectory(resources, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Ресурсы Horizon отсутствуют.");
        if (!executable.toFile().setExecutable(true, true) && !Files.isExecutable(executable)) throw new IOException("Не удалось разрешить выполнение horizon-client.");
        HorizonInstaller.cancelled();
        validateApi(probe(root, executable));
        HorizonInstaller.cancelled();
        Layout layout = layout(profiles, profile);
        Process process = null;
        Thread reader = null;
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        BufferedWriter log = Files.newBufferedWriter(layout.log(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        try {
            HorizonInstaller.cancelled();
            process = processes.start(builder(root, command(executable, resources, layout)));
            process.getOutputStream().close();
            Process owned = process;
            reader = Thread.ofVirtual().start(() -> {
                try { drain(owned.getInputStream(), log); }
                catch (IOException failure) { readFailure.set(failure); owned.destroy(); }
            });
            running.accept(profile.name());
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (readFailure.get() != null) throw new IOException("Не удалось сохранить журнал Horizon.", readFailure.get());
            }
            int exit = process.exitValue();
            reader.join(5000);
            if (reader.isAlive()) { process.getInputStream().close(); reader.join(1000); throw new IOException("Не удалось завершить чтение журнала Horizon."); }
            if (readFailure.get() != null) throw new IOException("Не удалось сохранить журнал Horizon.", readFailure.get());
            log.write("\nCactus: exit=" + exit + "\n");
            return new Result(exit, layout.log().toString());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Запуск Horizon остановлен.");
        } finally {
            if (process != null) stopOwned(process);
            if (reader != null && reader.isAlive()) {
                boolean interrupted = Thread.interrupted();
                try { reader.join(2000); }
                catch (InterruptedException ignored) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            log.close();
        }
    }

    private String probe(Path root, Path executable) throws IOException {
        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            HorizonInstaller.cancelled();
            process = processes.start(builder(root, List.of(executable.toString(), "--launch-api")));
            process.getOutputStream().close();
            Process owned = process;
            Future<byte[]> output = reader.submit(() -> owned.getInputStream().readNBytes(65537));
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Horizon не ответил на запрос Launch API.");
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 65536) throw new IOException("Не удалось проверить Launch API Horizon.");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Запуск Horizon отменён.");
        } catch (ExecutionException | TimeoutException failure) { throw new IOException("Не удалось прочитать Launch API Horizon.", failure); }
        finally { if (process != null) { stopOwned(process); process.getInputStream().close(); } reader.shutdownNow(); }
    }

    static String redact(String line) {
        return line.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1[скрыто]")
                .replaceAll("(?i)((?:access[_-]?token|refresh[_-]?token|password|secret|authorization|cookie|session)\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'}]+", "$1[скрыто]")
                .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[скрыто]");
    }

    private static void drain(InputStream stream, BufferedWriter log) throws IOException {
        try (Reader input = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            long written = 0;
            for (int c; (c = input.read()) != -1;) {
                if (c == '\n') {
                    if (written < 8 * 1024 * 1024) { String safe = redact(line.toString()); log.write(safe); log.newLine(); log.flush(); written += safe.length() + 1; }
                    line.setLength(0);
                } else if (c != '\r' && line.length() < 8192) line.append((char) c);
            }
            if (!line.isEmpty() && written < 8 * 1024 * 1024) { log.write(redact(line.toString())); log.newLine(); }
        }
    }

    private static void stopOwned(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) {
                process.destroy();
                try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException ignored) { interrupted = true; process.destroyForcibly(); }
                while (process.isAlive()) {
                    try { process.waitFor(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
