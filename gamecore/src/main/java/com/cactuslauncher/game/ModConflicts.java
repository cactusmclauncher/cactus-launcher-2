package com.cactuslauncher.game;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline compatibility check of the enabled mods of a build: duplicates, missing or out-of-range
 * dependencies (including Minecraft, the loader and Java), explicit breaks/conflicts and mods written
 * for another loader. Disabled ({@code .jar.disabled}) files are not read.
 */
public final class ModConflicts {
    private ModConflicts() {}

    /** ERROR stops the game from starting (the loader refuses); WARNING may work. */
    public enum Severity { ERROR, WARNING }

    public enum Type { DUPLICATE, MISSING, VERSION, BREAKS, CONFLICTS, LOADER, MINECRAFT, LOADER_VERSION, JAVA }

    /**
     * One problem of the jar [fileName]. [dependencyId] is the mod to install for MISSING problems
     * (null when it is Minecraft, the loader or Java); [otherFile] is the other jar of a duplicate or conflict.
     */
    public record Problem(Severity severity, Type type, String fileName, String modName, String message,
                          String dependencyId, String otherFile) {}

    /** The build: loader "Fabric"/"Forge", Minecraft version, loader version and Java major (null/0 when unknown). */
    public record Environment(String loader, String minecraftVersion, String loaderVersion, int javaVersion) {}

    private record Provider(String version, String fileName, String name, boolean builtIn) {}

    /** Reads every enabled jar of [modsFolder] (symlinks are skipped) and checks them. */
    public static List<Problem> check(Path modsFolder, Environment environment) throws IOException {
        return check(modsFolder, environment, null);
    }

    /**
     * As {@link #check(Path, Environment)}, reusing metadata from [cache] for jars whose path, size and
     * modification time are unchanged (the cache is cleared when it grows past 4000 entries).
     */
    public static List<Problem> check(Path modsFolder, Environment environment, Map<String, ModMetadata.Jar> cache) throws IOException {
        if (Files.isSymbolicLink(modsFolder)) throw new IOException("Папка модов не должна быть символической ссылкой.");
        if (!Files.isDirectory(modsFolder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var jars = new ArrayList<ModMetadata.Jar>();
        try (var files = Files.list(modsFolder)) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar") || Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String key = null;
                if (cache != null) {
                    var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    key = path.toAbsolutePath().normalize() + "|" + attributes.size() + "|" + attributes.lastModifiedTime().toMillis();
                    ModMetadata.Jar cached = cache.get(key);
                    if (cached != null) { jars.add(cached); continue; }
                }
                ModMetadata.Jar jar;
                try { jar = ModMetadata.read(path); }
                catch (IOException e) { jar = new ModMetadata.Jar(name, Set.of(), List.of()); }
                jars.add(jar);
                if (cache != null) {
                    if (cache.size() > 4000) cache.clear();
                    cache.put(key, jar);
                }
            }
        }
        return check(jars, environment);
    }

    public static List<Problem> check(List<ModMetadata.Jar> jars, Environment environment) {
        var problems = new ArrayList<Problem>();
        boolean fabric = "Fabric".equalsIgnoreCase(environment.loader());
        boolean forge = "Forge".equalsIgnoreCase(environment.loader());
        if (!fabric && !forge) return List.of();
        ModMetadata.Platform platform = fabric ? ModMetadata.Platform.FABRIC : ModMetadata.Platform.FORGE;

        // Jars written for another loader are reported and not used for the rest of the check.
        var accepted = new ArrayList<ModMetadata.Jar>();
        for (var jar : jars) {
            if (jar.platforms().isEmpty()) continue;
            if (jar.platforms().contains(platform)) { accepted.add(jar); continue; }
            String other = loaderName(jar.platforms().stream().sorted().findFirst().orElseThrow());
            String name = jar.mods().isEmpty() ? jar.fileName() : jar.mods().get(0).name();
            problems.add(new Problem(Severity.ERROR, Type.LOADER, jar.fileName(), name,
                    "Мод для " + other + ", а сборка на " + (fabric ? "Fabric" : "Forge"), null, null));
        }

        Map<String, List<Provider>> providers = new HashMap<>();
        builtIn(providers, "minecraft", environment.minecraftVersion(), "Minecraft");
        if (fabric) {
            builtIn(providers, "fabricloader", environment.loaderVersion(), "Fabric Loader");
            builtIn(providers, "fabric-loader", environment.loaderVersion(), "Fabric Loader");
        } else {
            builtIn(providers, "forge", environment.loaderVersion(), "Forge");
            String fml = environment.loaderVersion() == null ? null : environment.loaderVersion().split("\\.")[0];
            builtIn(providers, "javafml", fml, "Forge");
        }
        builtIn(providers, "java", environment.javaVersion() > 0 ? Integer.toString(environment.javaVersion()) : null, "Java");
        for (var jar : accepted) for (var mod : mods(jar, platform)) {
            var provider = new Provider(mod.version(), jar.fileName(), mod.name(), false);
            providers.computeIfAbsent(mod.id(), key -> new ArrayList<>()).add(provider);
            for (String id : mod.provides()) providers.computeIfAbsent(id, key -> new ArrayList<>()).add(provider);
        }

        // Duplicates: the same top-level id in two jars. The newest copy stays, the others are reported.
        Map<String, List<Map.Entry<ModMetadata.Jar, ModMetadata.Mod>>> topLevel = new LinkedHashMap<>();
        for (var jar : accepted) for (var mod : mods(jar, platform)) {
            if (!mod.nested()) topLevel.computeIfAbsent(mod.id(), key -> new ArrayList<>()).add(Map.entry(jar, mod));
        }
        for (var copies : topLevel.values()) {
            var distinctFiles = copies.stream().map(entry -> entry.getKey().fileName()).distinct().count();
            if (distinctFiles < 2) continue;
            var sorted = new ArrayList<>(copies);
            sorted.sort(Comparator.comparing((Map.Entry<ModMetadata.Jar, ModMetadata.Mod> entry) -> entry.getValue().version(), ModConflicts::compareVersions).reversed());
            var kept = sorted.get(0);
            for (var copy : sorted.subList(1, sorted.size())) {
                if (copy.getKey().fileName().equals(kept.getKey().fileName())) continue;
                problems.add(new Problem(Severity.ERROR, Type.DUPLICATE, copy.getKey().fileName(), copy.getValue().name(),
                        "Повтор: этот мод уже есть в " + kept.getKey().fileName(), null, kept.getKey().fileName()));
            }
        }

        var seen = new HashSet<String>();
        for (var jar : accepted) {
            for (var mod : mods(jar, platform)) {
                String owner = mod.nested() ? topName(jar, platform) : mod.name();
                for (var dependency : mod.dependencies()) {
                    Problem problem = dependencyProblem(jar, owner, dependency, providers, fabric, environment);
                    if (problem != null && seen.add(problem.fileName() + "|" + problem.type() + "|" + dependency.modId()))
                        problems.add(problem);
                }
            }
        }
        problems.sort(Comparator.comparing(Problem::severity).thenComparing(Problem::fileName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(problems);
    }

    private static Problem dependencyProblem(ModMetadata.Jar jar, String owner, ModMetadata.Dependency dependency,
                                             Map<String, List<Provider>> providers, boolean fabric, Environment environment) {
        String id = dependency.modId();
        List<Provider> found = providers.getOrDefault(id, List.of());
        // Unknown environment values (snapshot versions, loader not installed yet) are not guessed.
        if (found.size() == 1 && found.get(0).builtIn() && found.get(0).version() == null) return null;
        if (found.stream().anyMatch(provider -> provider.builtIn() && !semverLike(provider.version()) && fabric)) return null;
        String range = dependency.range().display();
        switch (dependency.kind()) {
            case REQUIRED, OPTIONAL -> {
                boolean required = dependency.kind() == ModMetadata.Kind.REQUIRED;
                if (found.isEmpty()) {
                    if (!required) return null;
                    if (builtInId(id)) return null; // e.g. "forge" in a Fabric build is reported as LOADER already
                    return new Problem(Severity.ERROR, Type.MISSING, jar.fileName(), owner,
                            needs(id) + (range.isEmpty() ? "" : " " + range), installId(id), null);
                }
                if (found.stream().anyMatch(provider -> dependency.range().test(provider.version()))) return null;
                Provider provider = found.get(0);
                // Fabric only warns about recommended versions; Forge refuses optional mods out of range.
                Severity severity = required || !fabric ? Severity.ERROR : Severity.WARNING;
                Type type = switch (id) {
                    case "minecraft" -> Type.MINECRAFT;
                    case "java" -> Type.JAVA;
                    case "fabricloader", "fabric-loader", "forge", "javafml" -> Type.LOADER_VERSION;
                    default -> Type.VERSION;
                };
                String installed = switch (type) {
                    case MINECRAFT, LOADER_VERSION -> ", в сборке " + provider.version();
                    case JAVA -> ", у сборки Java " + provider.version();
                    default -> ", установлен " + (provider.version().isBlank() ? "другой" : provider.version());
                };
                String name = displayName(id, provider.name());
                String lead = (required ? (id.equals("java") ? "Нужна " : "Нужен ") : "Рекомендуется ") + name;
                return new Problem(severity, type, jar.fileName(), owner, lead + " " + range + installed,
                        null, provider.builtIn() ? null : provider.fileName());
            }
            case BREAKS, CONFLICTS -> {
                for (Provider provider : found) {
                    if (provider.builtIn() || provider.fileName().equals(jar.fileName())) {
                        if (!provider.builtIn() || provider.version() == null || !dependency.range().test(provider.version())) continue;
                        // A mod that breaks this Minecraft or loader version.
                        return new Problem(Severity.ERROR, Type.BREAKS, jar.fileName(), owner,
                                "Не работает с " + provider.name() + " " + provider.version(), null, null);
                    }
                    if (!dependency.range().test(provider.version())) continue;
                    boolean breaks = dependency.kind() == ModMetadata.Kind.BREAKS;
                    return new Problem(breaks ? Severity.ERROR : Severity.WARNING, breaks ? Type.BREAKS : Type.CONFLICTS,
                            jar.fileName(), owner, (breaks ? "Конфликтует с " : "Может конфликтовать с ") + provider.name(),
                            null, provider.fileName());
                }
                return null;
            }
        }
        return null;
    }

    private static List<ModMetadata.Mod> mods(ModMetadata.Jar jar, ModMetadata.Platform platform) {
        return jar.mods().stream().filter(mod -> mod.platform() == platform).toList();
    }

    private static String topName(ModMetadata.Jar jar, ModMetadata.Platform platform) {
        return mods(jar, platform).stream().filter(mod -> !mod.nested()).map(ModMetadata.Mod::name).findFirst().orElse(jar.fileName());
    }

    private static void builtIn(Map<String, List<Provider>> providers, String id, String version, String name) {
        providers.computeIfAbsent(id, key -> new ArrayList<>()).add(new Provider(version, "", name, true));
    }

    private static boolean builtInId(String id) {
        return switch (id) {
            case "minecraft", "java", "fabricloader", "fabric-loader", "forge", "javafml", "neoforge", "quilt_loader" -> true;
            default -> false;
        };
    }

    private static final Pattern SEMVER = Pattern.compile("\\d+(\\.\\d+)*");

    /** Minecraft snapshots ("24w14a") are not normalized here; dependencies on them are skipped. */
    private static boolean semverLike(String version) {
        return version == null || SEMVER.matcher(version).matches();
    }

    private static int compareVersions(String left, String right) {
        var a = ModVersions.SemVer.parse(left);
        var b = ModVersions.SemVer.parse(right);
        if (a != null && b != null) return a.compareTo(b);
        return ModVersions.compareMaven(left, right);
    }

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("fabric-api", "Fabric API"), Map.entry("fabric", "Fabric API"),
            Map.entry("fabricloader", "Fabric Loader"), Map.entry("fabric-loader", "Fabric Loader"),
            Map.entry("minecraft", "Minecraft"), Map.entry("forge", "Forge"), Map.entry("javafml", "Forge"),
            Map.entry("neoforge", "NeoForge"), Map.entry("fabric-language-kotlin", "Fabric Language Kotlin"),
            Map.entry("kotlinforforge", "Kotlin for Forge"), Map.entry("cloth-config", "Cloth Config"),
            Map.entry("cloth-config2", "Cloth Config"), Map.entry("architectury", "Architectury API"),
            Map.entry("geckolib", "GeckoLib"), Map.entry("yet_another_config_lib_v3", "YACL"),
            Map.entry("modmenu", "Mod Menu"), Map.entry("sodium", "Sodium"), Map.entry("iris", "Iris"),
            Map.entry("owo", "owo-lib"), Map.entry("balm", "Balm"), Map.entry("curios", "Curios API"),
            Map.entry("jei", "JEI"), Map.entry("rei", "REI"), Map.entry("optifabric", "OptiFabric"));

    /** Modrinth slugs for ids that differ from them. */
    private static final Map<String, String> SLUGS = Map.of(
            "fabric", "fabric-api", "cloth-config2", "cloth-config", "yet_another_config_lib_v3", "yacl",
            "owo", "owo-lib", "curios", "curios", "kotlinforforge", "kotlin-for-forge");

    static String displayName(String id, String fallback) {
        String known = NAMES.get(id);
        if (known != null) return known;
        return fallback == null || fallback.isBlank() ? id : fallback;
    }

    private static String needs(String id) {
        return (id.equals("java") ? "Нужна " : "Нужен ") + displayName(id, null);
    }

    /** The Modrinth slug to look up for a missing mod [id], or null for Minecraft, loaders and Java. */
    static String installId(String id) {
        if (builtInId(id)) return null;
        return SLUGS.getOrDefault(id, id);
    }

    /** Reads the loader version from the installed profile and Java from the Minecraft version json. */
    public static Environment environment(Path buildFolder, String loader, String minecraftVersion) {
        String loaderVersion = null;
        try {
            String profile = LoaderInstaller.profileId(buildFolder, loader, minecraftVersion);
            loaderVersion = loaderVersion(loader, profile);
        } catch (IOException ignored) { }
        int java = 0;
        Path json = buildFolder.resolve("versions").resolve(minecraftVersion).resolve(minecraftVersion + ".json");
        try {
            if (!Files.isSymbolicLink(json) && Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) {
                try (var reader = Files.newBufferedReader(json)) {
                    var root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("javaVersion")) java = root.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return new Environment(loader, minecraftVersion, loaderVersion, java);
    }

    /** "fabric-loader-0.16.5-1.21.1" gives 0.16.5; "1.20.1-forge-47.2.0" gives 47.2.0. */
    static String loaderVersion(String loader, String profileId) {
        if (profileId == null) return null;
        var fabric = Pattern.compile("fabric-loader-([0-9][^-]*)-").matcher(profileId);
        if ("Fabric".equalsIgnoreCase(loader) && fabric.find()) return fabric.group(1);
        var forge = Pattern.compile("forge-([0-9][0-9.]*)").matcher(profileId);
        if ("Forge".equalsIgnoreCase(loader) && forge.find()) return forge.group(1);
        return null;
    }

    private static String loaderName(ModMetadata.Platform platform) {
        return switch (platform) {
            case FABRIC -> "Fabric";
            case QUILT -> "Quilt";
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
        };
    }
}
