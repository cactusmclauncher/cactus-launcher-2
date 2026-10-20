package com.cactuslauncher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

public final class ResourcePackStore {
    public record Pack(String fileName, String title, String description, boolean enabled) {}

    public List<Pack> list(Path instance) throws IOException {
        Path folder = safeFolder(instance, false);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> active = enabledNames(instance);
        try (var files = Files.list(folder)) {
            return files.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? Files.isRegularFile(path.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)
                            : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .map(path -> readPack(path, active))
                    .sorted(Comparator.comparing(Pack::title, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public Path folder(Path instance) throws IOException {
        return safeFolder(instance, true);
    }

    public void importPack(Path instance, Path source) throws IOException {
        Path folder = safeFolder(instance, true);
        if (source == null || Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IOException("Выберите ZIP-ресурспак.");
        validatePackZip(source);
        String name = source.getFileName().toString();
        Path target = folder.resolve(name).normalize();
        if (!target.getParent().equals(folder) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            String base = name.substring(0, name.length() - 4);
            target = folder.resolve(base + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip");
        }
        Path temp = folder.resolve(".import-" + UUID.randomUUID());
        try {
            Files.copy(source, temp);
            validatePackZip(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void setEnabled(Path instance, String fileName, boolean enabled) throws IOException {
        if (!safePackName(fileName)) throw new IOException("Некорректное имя ресурспака.");
        Path folder = safeFolder(instance, false);
        Path pack = folder.resolve(fileName).normalize();
        if (!pack.getParent().equals(folder) || Files.isSymbolicLink(pack)
                || !(Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Ресурспак не найден.");
        List<String> names = new ArrayList<>(enabledNames(instance));
        if (!names.contains("vanilla")) names.add(0, "vanilla");
        String reference = "file/" + fileName;
        names.removeIf(name -> name.equals(reference));
        if (enabled) names.add(reference);
        writeEnabledNames(instance, names);
    }

    private Pack readPack(Path path, List<String> active) {
        String file = path.getFileName().toString();
        String title = file;
        String description = "ZIP-ресурспак";
        try {
            JsonElement root;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (var reader = Files.newBufferedReader(path.resolve("pack.mcmeta"), StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader);
                }
            } else try (ZipFile zip = new ZipFile(path.toFile())) {
                var entry = zip.getEntry("pack.mcmeta");
                if (entry == null) throw new IOException("pack.mcmeta отсутствует");
                try (var input = zip.getInputStream(entry)) {
                    root = JsonParser.parseReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
                }
            }
            {
                JsonElement value = root.getAsJsonObject().getAsJsonObject("pack").get("description");
                if (value != null) {
                    if (value.isJsonPrimitive()) description = value.getAsString();
                    else if (value.isJsonObject()) description = value.getAsJsonObject().has("text") ? value.getAsJsonObject().get("text").getAsString() : value.toString();
                    else description = value.toString();
                }
            }
        } catch (Exception ignored) {
            description = "Не удалось прочитать описание pack.mcmeta";
        }
        return new Pack(file, title, description, active.contains("file/" + file));
    }

    private List<String> enabledNames(Path instance) throws IOException {
        Path options = instance.resolve("options.txt");
        if (Files.isSymbolicLink(options)) throw new IOException("options.txt не должен быть символической ссылкой.");
        if (!Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            if (!line.startsWith("resourcePacks:")) continue;
            try {
                JsonArray array = JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray();
                var result = new ArrayList<String>();
                for (JsonElement value : array) if (value.isJsonPrimitive()) result.add(value.getAsString());
                return result;
            } catch (RuntimeException e) {
                throw new IOException("Список ресурспаков в options.txt повреждён. Файл сохранён без изменений.", e);
            }
        }
        return new ArrayList<>();
    }

    private void writeEnabledNames(Path instance, List<String> names) throws IOException {
        Path options = instance.resolve("options.txt");
        if (Files.isSymbolicLink(options)) throw new IOException("options.txt не должен быть символической ссылкой.");
        List<String> lines = Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8)) : new ArrayList<>();
        JsonArray array = new JsonArray();
        names.forEach(array::add);
        String value = "resourcePacks:" + array;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("resourcePacks:")) {
            lines.set(i, value);
            replaced = true;
            break;
        }
        if (!replaced) lines.add(value);
        Path temp = instance.resolve(".options-" + UUID.randomUUID());
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try { Files.move(temp, options, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, options, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private Path safeFolder(Path instance, boolean create) throws IOException {
        if (Files.isSymbolicLink(instance)) throw new IOException("Папка сборки не должна быть символической ссылкой.");
        Path folder = instance.resolve("resourcepacks");
        if (Files.isSymbolicLink(folder)) throw new IOException("Папка ресурспаков не должна быть символической ссылкой.");
        if (create) Files.createDirectories(folder);
        return folder.toAbsolutePath().normalize();
    }

    private boolean safePackName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 240
                || name.codePoints().anyMatch(Character::isISOControl) || name.contains("/") || name.contains("\\") || name.contains(":")) return false;
        try { return Path.of(name).getFileName().toString().equals(name); }
        catch (java.nio.file.InvalidPathException e) { return false; }
    }

    private void validatePackZip(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            if (entry == null || entry.getSize() > 1024 * 1024) throw new IOException("В архиве нет допустимого pack.mcmeta.");
            try (var input = zip.getInputStream(entry)) {
                JsonElement root = JsonParser.parseReader(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
                if (!root.isJsonObject() || !root.getAsJsonObject().has("pack") || !root.getAsJsonObject().get("pack").isJsonObject())
                    throw new IOException("pack.mcmeta имеет неверный формат.");
            } catch (RuntimeException e) {
                throw new IOException("pack.mcmeta имеет неверный формат.", e);
            }
        }
    }
}
