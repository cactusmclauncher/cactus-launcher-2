package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class HorizonPreparation {
    interface JavaProvider { Path ensure(int major, Consumer<String> progress) throws IOException; }

    static Path javaHome(String requested, int major, JavaProvider provider, Consumer<HorizonInstaller.Progress> progress) throws IOException {
        HorizonInstaller.cancelled();
        Path home = requested.isBlank()
                ? provider.ensure(major, message -> progress.accept(new HorizonInstaller.Progress(message, 0.56f))).getParent().getParent()
                : Path.of(requested).toAbsolutePath().normalize();
        HorizonInstaller.secureExisting(home);
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Папка Java отсутствует.");
        return home;
    }

    static void prepare(Path root, Path cache, Path source, String javaHome, Consumer<HorizonInstaller.Progress> progress) throws IOException {
        JsonObject pins = HorizonInstaller.pins(root);
        Path executable = root.resolve(HorizonInstaller.binary("horizon-prepare"));
        HorizonInstaller.regular(executable);
        if (!executable.toFile().setExecutable(true, true) && !Files.isExecutable(executable)) throw new IOException("Не удалось разрешить выполнение подписанного horizon-prepare.");
        String api = run(root, List.of(executable.toString(), "--launch-api", "--root", root.toString()), line -> {}, 60);
        validateApi(api, pins);
        HorizonResources.Inputs inputs = HorizonResources.acquire(source, cache, pins, progress);
        Path staging = Files.createTempDirectory(root, ".preparing-");
        try {
            Path output = staging.resolve("resources");
            List<String> command = new ArrayList<>(List.of(executable.toString(), "--prepare", "--root", root.toString(),
                    "--minecraft-dir", inputs.minecraft().toString(), "--server-jar", inputs.server().toString(), "--output", output.toString(), "--complete", "--progress", "json"));
            Path home = javaHome(javaHome, pins.get("java_major").getAsInt(), new JavaRuntimeManager(cache.resolveSibling("runtimes"))::ensureJdk, progress);
            command.addAll(List.of("--java-home", home.toString()));
            float[] fraction = {0.6f};
            progress.accept(new HorizonInstaller.Progress("Генерируем ресурсы; нужна Java " + pins.get("java_major").getAsInt() + "…", fraction[0]));
            run(root, command, line -> {
                try {
                    JsonObject value = JsonParser.parseString(line).getAsJsonObject();
                    if (value.has("fraction")) fraction[0] = Math.max(fraction[0], Math.min(0.96f, 0.6f + 0.36f * value.get("fraction").getAsFloat()));
                    if (value.has("message")) progress.accept(new HorizonInstaller.Progress(value.get("message").getAsString(), fraction[0]));
                    else if (value.has("fraction")) progress.accept(new HorizonInstaller.Progress("Генерируем ресурсы…", fraction[0]));
                } catch (RuntimeException ignored) { }
            }, 1800);
            run(root, List.of(executable.toString(), "--check", "--root", root.toString(), "--output", output.toString()), line -> {}, 120);
            writeInventory(output);
            HorizonInstaller.cancelled();
            Path target = root.resolve("resources-" + UUID.randomUUID());
            Files.move(output, target, StandardCopyOption.ATOMIC_MOVE);
            Path pointer = staging.resolve("pointer");
            Files.writeString(pointer, target.getFileName().toString(), StandardOpenOption.CREATE_NEW);
            HorizonInstaller.cancelled();
            Files.move(pointer, root.resolve("cactus-resources"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(new HorizonInstaller.Progress("Ресурсы подготовлены и проверены.", 1f));
        } finally { HorizonInstaller.deleteTree(staging); }
    }

    static void validateApi(String text, JsonObject pins) throws IOException {
        try {
            JsonObject api = JsonParser.parseString(text).getAsJsonObject();
            if (!api.get("launch_api").getAsString().equals("0.1.0") || !api.get("minecraft").getAsString().equals(pins.get("version").getAsString())
                    || api.get("java_major").getAsInt() != pins.get("java_major").getAsInt()) throw new IOException("Неподдерживаемый Launch API или требования подготовки Horizon.");
            Set<String> options = new HashSet<>();
            for (JsonElement entry : api.getAsJsonArray("options")) options.add(entry.getAsJsonObject().get("name").getAsString());
            if (!options.containsAll(List.of("--prepare", "--root", "--output", "--complete", "--check", "--progress", "--minecraft-dir", "--server-jar", "--java-home")))
                throw new IOException("В preparer нет необходимых команд.");
        } catch (RuntimeException failure) { throw new IOException("Некорректный Launch API Horizon.", failure); }
    }

    static boolean prepared(Path root) throws IOException { return resolvedResources(root) != null; }

    static Path resolvedResources(Path root) throws IOException {
        HorizonInstaller.secureExisting(root);
        Path pointer = root.resolve("cactus-resources");
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) return null;
        HorizonInstaller.regular(pointer);
        if (Files.size(pointer) > 128) throw new IOException("Повреждён указатель ресурсов.");
        String name = Files.readString(pointer);
        if (!name.matches("resources-[a-f0-9-]{36}")) throw new IOException("Повреждён указатель ресурсов.");
        Path resources = root.resolve(name);
        JsonObject inventory = HorizonResources.json(resources.resolve("cactus-files.json"), 32 * 1024 * 1024);
        JsonArray files = inventory.getAsJsonArray("files");
        if (files == null || files.isEmpty() || files.size() > 200000) throw new IOException("Повреждён список ресурсов.");
        Set<String> seen = new HashSet<>();
        for (JsonElement element : files) {
            HorizonInstaller.cancelled();
            JsonObject file = element.getAsJsonObject();
            String relative = file.get("path").getAsString();
            if (!seen.add(relative) || !HorizonInstaller.digest(HorizonArchive.safe(resources, relative), "SHA-256").equals(file.get("sha256").getAsString()))
                throw new IOException("Подготовленные ресурсы повреждены. Подготовьте их заново.");
        }
        return resources;
    }

    private static void writeInventory(Path output) throws IOException {
        JsonArray entries = new JsonArray();
        long total = 0;
        try (var paths = Files.walk(output)) {
            Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                HorizonInstaller.cancelled();
                if (Files.isSymbolicLink(file)) throw new IOException("Ссылка в подготовленных ресурсах запрещена.");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                HorizonInstaller.regular(file);
                if (entries.size() >= 200000 || (total += Files.size(file)) > 8L * 1024 * 1024 * 1024) throw new IOException("Ресурсы превышают лимит размера.");
                JsonObject entry = new JsonObject();
                entry.addProperty("path", output.relativize(file).toString().replace('\\', '/'));
                entry.addProperty("sha256", HorizonInstaller.digest(file, "SHA-256"));
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) throw new IOException("Preparer не создал ресурсы.");
        JsonObject inventory = new JsonObject();
        inventory.add("files", entries);
        Files.writeString(output.resolve("cactus-files.json"), inventory.toString(), StandardOpenOption.CREATE_NEW);
    }

    static String run(Path root, List<String> command, Consumer<String> lines, int timeoutSeconds) throws IOException {
        HorizonInstaller.cancelled();
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        Process process = builder.start();
        process.getOutputStream().close();
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(128);
        Object end = new Object();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (Reader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder line = new StringBuilder();
                for (int c; (c = input.read()) != -1;) {
                    if (c == '\n') { queue.put(line.toString()); line.setLength(0); }
                    else if (c != '\r') {
                        if (line.length() >= 8192) throw new IOException("Слишком длинная строка preparer.");
                        line.append((char) c);
                    }
                }
                if (!line.isEmpty()) queue.put(line.toString());
                queue.put(end);
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            catch (IOException failure) { queue.offer(failure); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        StringBuilder tail = new StringBuilder();
        try {
            boolean ended = false;
            while (!ended || process.isAlive()) {
                HorizonInstaller.cancelled();
                if (System.nanoTime() > deadline) throw new IOException("Время подготовки Horizon истекло.");
                Object value = queue.poll(100, TimeUnit.MILLISECONDS);
                if (value == end) ended = true;
                else if (value instanceof IOException failure) throw failure;
                else if (value instanceof String line) {
                    lines.accept(line);
                    tail.append(line).append('\n');
                    if (tail.length() > 65536) tail.delete(0, tail.length() - 65536);
                } else if (!reader.isAlive() && queue.isEmpty()) ended = true;
            }
            if (process.exitValue() != 0) throw new IOException("Подготовка Horizon завершилась с кодом " + process.exitValue() + ": " + tail.toString().strip());
            return tail.toString();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Подготовка Horizon отменена.");
        } finally {
            process.descendants().forEach(child -> { child.destroy(); if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) { process.destroy(); if (process.isAlive()) process.destroyForcibly(); }
            reader.interrupt();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
        }
    }
}
