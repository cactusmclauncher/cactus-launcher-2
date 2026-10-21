package com.cactuslauncher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Mod metadata read from a jar: fabric.mod.json, quilt.mod.json, META-INF/mods.toml and
 * META-INF/neoforge.mods.toml, including mods nested with Jar-in-Jar (Fabric "jars", Quilt "jars",
 * Forge/NeoForge META-INF/jarjar/metadata.json).
 */
public final class ModMetadata {
    private ModMetadata() {}

    public enum Platform { FABRIC, QUILT, FORGE, NEOFORGE }

    /**
     * REQUIRED must be present in range; OPTIONAL only has to be in range when present;
     * BREAKS stops the game when present in range; CONFLICTS is a warning when present in range.
     */
    public enum Kind { REQUIRED, OPTIONAL, BREAKS, CONFLICTS }

    public record Dependency(String modId, ModVersions.Range range, Kind kind) {}

    /** One declared mod for [platform]; [nested] mods come from Jar-in-Jar and never count as duplicates. */
    public record Mod(Platform platform, String id, String name, String version, List<String> provides, List<Dependency> dependencies, boolean nested) {}

    /** A mod jar: the loaders it is written for (empty for plain libraries) and its declared mods, top level first. */
    public record Jar(String fileName, Set<Platform> platforms, List<Mod> mods) {
        public List<Mod> topLevel() { return mods.stream().filter(mod -> !mod.nested()).toList(); }
    }

    private static final int MAX_DEPTH = 3;
    private static final long MAX_METADATA = 1L << 20;
    private static final long MAX_NESTED_JAR = 48L << 20;

    /** Reads [jar]; unreadable or foreign archives become a jar without platforms and mods. */
    public static Jar read(Path jar) throws IOException {
        String fileName = jar.getFileName().toString();
        try (var zip = new ZipFile(jar.toFile())) {
            return read(fileName, name -> {
                var entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream input = zip.getInputStream(entry)) {
                    return readLimited(input, name.endsWith(".jar") ? MAX_NESTED_JAR : MAX_METADATA);
                }
            }, 0);
        } catch (java.util.zip.ZipException e) {
            return new Jar(fileName, Set.of(), List.of());
        }
    }

    /** Reads a jar held in memory, as tests and nested jars do. */
    public static Jar read(String fileName, byte[] bytes) throws IOException {
        return read(fileName, memory(bytes), 0);
    }

    @FunctionalInterface
    interface Entries { byte[] get(String name) throws IOException; }

    private static Entries memory(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory() || !(wanted(name) || name.toLowerCase(Locale.ROOT).endsWith(".jar"))) continue;
                byte[] data = readLimited(zip, name.endsWith(".jar") ? MAX_NESTED_JAR : MAX_METADATA);
                total += data.length;
                if (total > MAX_NESTED_JAR) throw new IOException("Вложенный jar слишком большой.");
                entries.put(name, data);
            }
        }
        return entries::get;
    }

    private static boolean wanted(String name) {
        return name.equals("fabric.mod.json") || name.equals("quilt.mod.json") || name.equals("META-INF/mods.toml")
                || name.equals("META-INF/neoforge.mods.toml") || name.equals("META-INF/MANIFEST.MF")
                || name.equals("META-INF/jarjar/metadata.json");
    }

    private static Jar read(String fileName, Entries entries, int depth) throws IOException {
        var platforms = EnumSet.noneOf(Platform.class);
        var mods = new ArrayList<Mod>();
        var nestedPaths = new ArrayList<String>();
        boolean nested = depth > 0;

        byte[] fabric = entries.get("fabric.mod.json");
        if (fabric != null) {
            JsonObject root = json(fabric);
            if (root != null) {
                platforms.add(Platform.FABRIC);
                Mod mod = fabricMod(root, nested);
                if (mod != null) mods.add(mod);
                for (JsonElement value : array(root, "jars")) {
                    if (value.isJsonObject()) {
                        String file = string(value.getAsJsonObject(), "file");
                        if (file != null) nestedPaths.add(file);
                    }
                }
            }
        }
        byte[] quilt = entries.get("quilt.mod.json");
        if (quilt != null) {
            JsonObject root = json(quilt);
            JsonObject loader = root == null ? null : object(root, "quilt_loader");
            if (loader != null) {
                platforms.add(Platform.QUILT);
                if (fabric == null) {
                    Mod mod = quiltMod(loader, nested);
                    if (mod != null) mods.add(mod);
                    for (JsonElement value : array(loader, "jars")) {
                        if (value.isJsonPrimitive()) nestedPaths.add(value.getAsString());
                        else if (value.isJsonObject() && string(value.getAsJsonObject(), "file") != null) nestedPaths.add(string(value.getAsJsonObject(), "file"));
                    }
                }
            }
        }
        String manifestVersion = null;
        byte[] manifest = entries.get("META-INF/MANIFEST.MF");
        if (manifest != null) {
            try { manifestVersion = new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes().getValue("Implementation-Version"); }
            catch (IOException ignored) { }
        }
        for (var platform : List.of(Platform.FORGE, Platform.NEOFORGE)) {
            byte[] toml = entries.get(platform == Platform.FORGE ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml");
            if (toml == null) continue;
            Map<String, Object> root;
            try { root = MiniToml.parse(new String(toml, StandardCharsets.UTF_8)); }
            catch (IOException e) { continue; }
            platforms.add(platform);
            mods.addAll(forgeMods(root, platform, manifestVersion, nested));
        }
        byte[] jarJar = entries.get("META-INF/jarjar/metadata.json");
        if (jarJar != null) {
            JsonObject root = json(jarJar);
            if (root != null) for (JsonElement value : array(root, "jars")) {
                if (value.isJsonObject() && string(value.getAsJsonObject(), "path") != null) nestedPaths.add(string(value.getAsJsonObject(), "path"));
            }
        }
        if (depth < MAX_DEPTH) for (String path : nestedPaths) {
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            if (normalized.contains("..")) continue;
            byte[] bytes = entries.get(normalized);
            if (bytes == null) continue;
            try {
                Jar inner = read(normalized, memory(bytes), depth + 1);
                for (Mod mod : inner.mods()) mods.add(new Mod(mod.platform(), mod.id(), mod.name(), mod.version(), mod.provides(), mod.dependencies(), true));
            } catch (IOException ignored) {
                // A broken nested library does not hide the problems of the outer mod.
            }
        }
        return new Jar(fileName, Set.copyOf(platforms), List.copyOf(mods));
    }

    // ---------------------------------------------------------------- Fabric

    private static Mod fabricMod(JsonObject root, boolean nested) {
        String id = string(root, "id");
        if (id == null) return null;
        if ("server".equals(string(root, "environment"))) return null;
        String version = stringOr(root, "version", "");
        var provides = new ArrayList<String>();
        for (JsonElement value : array(root, "provides")) if (value.isJsonPrimitive()) provides.add(value.getAsString());
        var dependencies = new ArrayList<Dependency>();
        fabricDependencies(root, "depends", Kind.REQUIRED, dependencies);
        fabricDependencies(root, "recommends", Kind.OPTIONAL, dependencies);
        fabricDependencies(root, "suggests", Kind.OPTIONAL, dependencies);
        fabricDependencies(root, "breaks", Kind.BREAKS, dependencies);
        fabricDependencies(root, "conflicts", Kind.CONFLICTS, dependencies);
        return new Mod(Platform.FABRIC, id, stringOr(root, "name", id), version, List.copyOf(provides), List.copyOf(dependencies), nested);
    }

    private static void fabricDependencies(JsonObject root, String key, Kind kind, List<Dependency> target) {
        JsonObject object = object(root, key);
        if (object == null) return;
        for (var entry : object.entrySet()) {
            target.add(new Dependency(entry.getKey(), fabricRange(entry.getValue()), kind));
        }
    }

    private static ModVersions.Range fabricRange(JsonElement value) {
        if (value == null || value.isJsonNull()) return ModVersions.ANY;
        if (value.isJsonArray()) {
            var predicates = new ArrayList<String>();
            for (JsonElement item : value.getAsJsonArray()) if (item.isJsonPrimitive()) predicates.add(item.getAsString());
            return ModVersions.fabric(predicates);
        }
        return value.isJsonPrimitive() ? ModVersions.fabric(value.getAsString()) : ModVersions.ANY;
    }

    // ---------------------------------------------------------------- Quilt

    private static Mod quiltMod(JsonObject loader, boolean nested) {
        String id = string(loader, "id");
        if (id == null) return null;
        String version = stringOr(loader, "version", "");
        JsonObject metadata = object(loader, "metadata");
        String name = metadata == null ? id : stringOr(metadata, "name", id);
        var provides = new ArrayList<String>();
        for (JsonElement value : array(loader, "provides")) {
            if (value.isJsonPrimitive()) provides.add(quiltId(value.getAsString()));
            else if (value.isJsonObject() && string(value.getAsJsonObject(), "id") != null) provides.add(quiltId(string(value.getAsJsonObject(), "id")));
        }
        var dependencies = new ArrayList<Dependency>();
        quiltDependencies(loader, "depends", false, dependencies);
        quiltDependencies(loader, "breaks", true, dependencies);
        return new Mod(Platform.QUILT, id, name, version, List.copyOf(provides), List.copyOf(dependencies), nested);
    }

    private static void quiltDependencies(JsonObject loader, String key, boolean breaks, List<Dependency> target) {
        for (JsonElement value : array(loader, key)) {
            if (value.isJsonPrimitive()) {
                target.add(new Dependency(quiltId(value.getAsString()), ModVersions.ANY, breaks ? Kind.BREAKS : Kind.REQUIRED));
            } else if (value.isJsonObject()) {
                JsonObject dependency = value.getAsJsonObject();
                String id = string(dependency, "id");
                if (id == null) continue; // "any"/"all" groups are not interpreted.
                boolean optional = dependency.has("optional") && dependency.get("optional").isJsonPrimitive() && dependency.get("optional").getAsBoolean();
                Kind kind = breaks ? (optional ? Kind.CONFLICTS : Kind.BREAKS) : (optional ? Kind.OPTIONAL : Kind.REQUIRED);
                target.add(new Dependency(quiltId(id), fabricRange(dependency.get("versions")), kind));
            }
        }
    }

    private static String quiltId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ---------------------------------------------------------------- Forge / NeoForge

    @SuppressWarnings("unchecked")
    private static List<Mod> forgeMods(Map<String, Object> root, Platform platform, String manifestVersion, boolean nested) {
        var result = new ArrayList<Mod>();
        Object modsValue = root.get("mods");
        if (!(modsValue instanceof List<?> modsList)) return result;
        String modLoader = text(root.get("modLoader"));
        String loaderVersion = text(root.get("loaderVersion"));
        Map<String, Object> allDependencies = root.get("dependencies") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        for (Object item : modsList) {
            if (!(item instanceof Map<?, ?> rawMod)) continue;
            Map<String, Object> mod = (Map<String, Object>) rawMod;
            String id = text(mod.get("modId"));
            if (id == null) continue;
            String version = text(mod.get("version"));
            if (version == null || version.contains("${file.jarVersion}")) version = manifestVersion == null ? "" : manifestVersion;
            String name = text(mod.get("displayName"));
            var dependencies = new ArrayList<Dependency>();
            // FML language provider version, e.g. loaderVersion="[47,)" for modLoader="javafml".
            if ("javafml".equals(modLoader) && loaderVersion != null) dependencies.add(new Dependency("javafml", ModVersions.maven(loaderVersion), Kind.REQUIRED));
            Object declared = allDependencies.get(id);
            if (declared instanceof List<?> list) for (Object value : list) {
                if (!(value instanceof Map<?, ?> rawDependency)) continue;
                Map<String, Object> dependency = (Map<String, Object>) rawDependency;
                String dependencyId = text(dependency.get("modId"));
                if (dependencyId == null) continue;
                String side = text(dependency.get("side"));
                if (side != null && side.equalsIgnoreCase("SERVER")) continue;
                dependencies.add(new Dependency(dependencyId, ModVersions.maven(text(dependency.get("versionRange"))), forgeKind(dependency)));
            }
            result.add(new Mod(platform, id, name == null ? id : name, version, List.of(), List.copyOf(dependencies), nested));
        }
        return result;
    }

    private static Kind forgeKind(Map<String, Object> dependency) {
        String type = text(dependency.get("type"));
        if (type != null) return switch (type.toLowerCase(Locale.ROOT)) {
            case "optional" -> Kind.OPTIONAL;
            case "incompatible" -> Kind.BREAKS;
            case "discouraged" -> Kind.CONFLICTS;
            default -> Kind.REQUIRED;
        };
        Object mandatory = dependency.get("mandatory");
        if (mandatory instanceof Boolean value) return value ? Kind.REQUIRED : Kind.OPTIONAL;
        if (mandatory != null) return "false".equalsIgnoreCase(mandatory.toString()) ? Kind.OPTIONAL : Kind.REQUIRED;
        return Kind.REQUIRED;
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1; ) {
            total += count;
            if (total > limit) throw new IOException("Файл внутри мода слишком большой.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static JsonObject json(byte[] bytes) {
        try {
            JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return value.isJsonObject() ? value.getAsJsonObject() : null;
        } catch (RuntimeException e) { return null; }
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String stringOr(JsonObject root, String key, String fallback) {
        String value = string(root, key);
        return value == null ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().strip();
    }
}
