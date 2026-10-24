package com.cactuslauncher.game;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * A resource pack the launcher writes into a build: it replaces only the title-screen music
 * ({@code music.menu}) with the weighted {@link C418Music#TRACKS}; music in worlds stays vanilla.
 * The pack holds only sounds.json, pointing at the game's own sound files, so no audio is copied.
 */
public final class MenuMusicPack {
    public static final String FOLDER = "CactusMenuMusic";
    private static final String DESCRIPTION = "Cactus Launcher: музыка C418 в главном меню";
    private static final Pattern RELEASE = Pattern.compile("1\\.(\\d+)(?:\\.\\d+)?");

    /** What the pack would contain for a build; null when the version has no resource packs or the index lacks the tracks. */
    public record Plan(int packFormat, boolean legacyIds, int dataVersion, String mcmeta, String soundsJson, List<String> missing) {}

    private MenuMusicPack() {}

    /**
     * Adds (enabled) or removes the pack in [instance] for Minecraft [version]. Enabling needs the
     * installed version (its JSON, client JAR and asset index); otherwise nothing is changed.
     */
    public static void sync(Path instance, String version, boolean enabled) throws IOException {
        Path packs = instance.resolve("resourcepacks");
        if (Files.isSymbolicLink(instance) || Files.isSymbolicLink(packs)) throw new IOException("Папка ресурспаков не должна быть символической ссылкой.");
        Path pack = packs.resolve(FOLDER);
        if (!enabled) {
            if (Files.exists(instance.resolve("options.txt"), LinkOption.NOFOLLOW_LINKS)) {
                var names = readNames(instance);
                if (names != null && (names.remove("file/" + FOLDER) | names.remove(FOLDER))) writeNames(instance, names, -1);
            }
            deleteTree(pack);
            return;
        }
        Plan plan = plan(instance, version);
        if (plan == null) return;
        Files.createDirectories(pack.resolve("assets/minecraft"));
        writeIfChanged(pack.resolve("pack.mcmeta"), plan.mcmeta());
        writeIfChanged(pack.resolve("assets/minecraft/sounds.json"), plan.soundsJson());
        String id = plan.legacyIds() ? FOLDER : "file/" + FOLDER;
        var names = readNames(instance);
        boolean created = names == null;
        if (created) names = new ArrayList<>(plan.legacyIds() ? List.of() : List.of("vanilla"));
        if (names.contains(id)) return;
        names.remove(plan.legacyIds() ? "file/" + FOLDER : FOLDER);
        names.add(id); // last = highest priority, above the user's packs
        writeNames(instance, names, created ? plan.dataVersion() : -1);
    }

    /** Reads the installed version to build the pack; null when it cannot carry one. */
    public static Plan plan(Path instance, String version) throws IOException {
        if (!version.matches("[A-Za-z0-9._ -]{1,64}")) return null;
        Path versionFolder = instance.resolve("versions").resolve(version);
        Path json = versionFolder.resolve(version + ".json");
        Path jar = versionFolder.resolve(version + ".jar");
        if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonObject info;
        try (Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) { info = JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (RuntimeException e) { return null; }
        if (!info.has("assetIndex")) return null;
        String indexId = info.getAsJsonObject("assetIndex").get("id").getAsString();
        if (!indexId.matches("[A-Za-z0-9._-]{1,64}")) return null;
        Path index = instance.resolve("assets/indexes").resolve(indexId + ".json");
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonObject objects;
        try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            objects = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("objects");
        } catch (RuntimeException e) { return null; }
        int[] format = packFormat(jar, version);
        if (format == null) return null;
        return plan(format[0], format[1], objects);
    }

    /** [packFormat] is the resource pack major format of the game, [objects] its asset index "objects". */
    public static Plan plan(int packFormat, int dataVersion, JsonObject objects) {
        boolean modernNames = objects.has(C418Music.TRACKS.getFirst().assetKey());
        var sounds = new JsonArray();
        var missing = new ArrayList<String>();
        int lost = 0;
        JsonObject first = null;
        for (var track : C418Music.TRACKS) {
            String key = modernNames ? track.assetKey() : track.legacyAssetKey();
            if (!objects.has(key)) { missing.add(track.title()); lost += track.weight(); continue; }
            var sound = new JsonObject();
            sound.addProperty("name", modernNames ? track.path() : track.legacyPath());
            sound.addProperty("stream", true);
            sound.addProperty("weight", track.weight());
            if (first == null) first = sound;
            sounds.add(sound);
        }
        // A track the game lacks gives its chance to the first entry, Moog City 2.
        if (first == null || !first.get("name").getAsString().equals(modernNames ? C418Music.TRACKS.getFirst().path() : C418Music.TRACKS.getFirst().legacyPath()))
            return null;
        first.addProperty("weight", first.get("weight").getAsInt() + lost);
        var event = new JsonObject();
        event.addProperty("replace", true);
        event.add("sounds", sounds);
        var root = new JsonObject();
        root.add("music.menu", event);

        var meta = new JsonObject();
        meta.addProperty("description", DESCRIPTION);
        if (packFormat >= 65) {
            // 1.21.9+: min_format/max_format; pack_format and supported_formats are for older games only.
            meta.addProperty("min_format", packFormat);
            meta.addProperty("max_format", packFormat);
        } else {
            meta.addProperty("pack_format", packFormat);
        }
        var mcmeta = new JsonObject();
        mcmeta.add("pack", meta);
        // Version 1.13 (pack format 4) introduced "file/<name>" ids in options.txt.
        return new Plan(packFormat, packFormat < 4, dataVersion, ascii(mcmeta), ascii(root), List.copyOf(missing));
    }

    /** {resource pack major format, data version or -1}, from version.json in the client JAR (1.14+) or the version id. */
    static int[] packFormat(Path jar, String version) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("version.json");
            if (entry != null && entry.getSize() < 64 * 1024) {
                try (var input = zip.getInputStream(entry); Reader reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
                    JsonObject value = JsonParser.parseReader(reader).getAsJsonObject();
                    int data = value.has("world_version") ? value.get("world_version").getAsInt() : -1;
                    JsonElement pack = value.get("pack_version");
                    if (pack != null && pack.isJsonPrimitive()) return new int[] { pack.getAsInt(), data };
                    if (pack != null && pack.isJsonObject()) {
                        JsonObject formats = pack.getAsJsonObject();
                        if (formats.has("resource_major")) return new int[] { formats.get("resource_major").getAsInt(), data };
                        if (formats.has("resource")) return new int[] { formats.get("resource").getAsInt(), data };
                    }
                } catch (RuntimeException e) {
                    throw new IOException("version.json в клиенте Minecraft повреждён.", e);
                }
            }
        }
        Matcher release = RELEASE.matcher(version);
        if (!release.matches()) return null;
        int minor = Integer.parseInt(release.group(1));
        if (minor < 6) return null; // resource packs appeared in 1.6
        return new int[] { minor <= 8 ? 1 : minor <= 10 ? 2 : minor <= 12 ? 3 : 4, -1 };
    }

    /** The resourcePacks list of options.txt; null when the file or the line is missing. */
    private static List<String> readNames(Path instance) throws IOException {
        Path options = instance.resolve("options.txt");
        if (Files.isSymbolicLink(options)) throw new IOException("options.txt не должен быть символической ссылкой.");
        if (!Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) return null;
        for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            if (!line.startsWith("resourcePacks:")) continue;
            try {
                var result = new ArrayList<String>();
                for (JsonElement value : JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray())
                    if (value.isJsonPrimitive()) result.add(value.getAsString());
                return result;
            } catch (RuntimeException e) {
                throw new IOException("Список ресурспаков в options.txt повреждён. Файл сохранён без изменений.", e);
            }
        }
        return null;
    }

    /** Rewrites only the resourcePacks line; a new options.txt also gets the data version so the game does not "upgrade" it. */
    private static void writeNames(Path instance, List<String> names, int dataVersion) throws IOException {
        Path options = instance.resolve("options.txt");
        if (Files.isSymbolicLink(options)) throw new IOException("options.txt не должен быть символической ссылкой.");
        boolean exists = Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS);
        List<String> lines = exists ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8)) : new ArrayList<>();
        if (!exists && dataVersion > 0) lines.add("version:" + dataVersion);
        var array = new JsonArray();
        names.forEach(array::add);
        String value = "resourcePacks:" + array;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("resourcePacks:")) { lines.set(i, value); replaced = true; break; }
        if (!replaced) lines.add(value);
        Path temp = instance.resolve(".options-" + UUID.randomUUID());
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try { Files.move(temp, options, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, options, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static void writeIfChanged(Path file, String text) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Файл ресурспака не должен быть символической ссылкой.");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
        Path temp = file.resolveSibling("." + file.getFileName() + "-" + UUID.randomUUID());
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static void deleteTree(Path pack) throws IOException {
        if (Files.isSymbolicLink(pack)) { Files.delete(pack); return; }
        if (!Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) Files.delete(pack);
            return;
        }
        try (var children = Files.newDirectoryStream(pack)) {
            for (Path child : children) deleteTree(child);
        }
        Files.delete(pack);
    }

    /** Pretty JSON with non-ASCII escaped, so old games that read packs as Latin-1 still show the text. */
    private static String ascii(JsonElement value) {
        String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value);
        var out = new StringBuilder(text.length());
        text.chars().forEach(c -> { if (c < 128) out.append((char) c); else out.append(String.format("\\u%04x", c)); });
        return out.append('\n').toString();
    }
}
