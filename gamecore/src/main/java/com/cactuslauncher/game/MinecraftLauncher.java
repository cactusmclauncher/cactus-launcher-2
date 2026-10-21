package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

public final class MinecraftLauncher {
    private static final JavaOptimizer JAVA_OPTIMIZER = new JavaOptimizer();

    public static Process launch(Path root, String version, String name, int memoryGb) throws IOException {
        return launch(root, version, name, memoryGb, null, null, null, true, true);
    }

    public static Process launch(Path root, String version, String name, int memoryGb, ExternalAuth.Session session, Path injector, String authlibUrl) throws IOException {
        return launch(root, version, name, memoryGb, session, injector, authlibUrl, true, true);
    }

    public static Process launch(Path root, String version, String name, int memoryGb, ExternalAuth.Session session,
                                 Path injector, String authlibUrl, boolean automaticHeap, boolean automaticOptimization) throws IOException {
        return launch(root, version, name, memoryGb, session, injector, authlibUrl, automaticHeap, automaticOptimization, false);
    }

    public static Process launch(Path root, String version, String name, int memoryGb, ExternalAuth.Session session,
                                 Path injector, String authlibUrl, boolean automaticHeap, boolean automaticOptimization,
                                 boolean ghostMode) throws IOException {
        return launch(root, version, name, memoryGb, session, injector, authlibUrl, automaticHeap, automaticOptimization, ghostMode, List.of());
    }

    /** [extraGameArguments] go after the version's own game arguments, e.g. a server to join (ServerList.quickJoinArguments). */
    public static Process launch(Path root, String version, String name, int memoryGb, ExternalAuth.Session session,
                                 Path injector, String authlibUrl, boolean automaticHeap, boolean automaticOptimization,
                                 boolean ghostMode, List<String> extraGameArguments) throws IOException {
        return launchInDirectory(root, root, version, name, memoryGb, session, injector, authlibUrl,
                automaticHeap, automaticOptimization, ghostMode, extraGameArguments);
    }

    public static Process launchInDirectory(Path root, Path gameDirectory, String version, String name, int memoryGb,
                                             ExternalAuth.Session session, Path injector, String authlibUrl,
                                             boolean automaticHeap, boolean automaticOptimization, boolean ghostMode) throws IOException {
        return launchInDirectory(root, gameDirectory, version, name, memoryGb, session, injector, authlibUrl,
                automaticHeap, automaticOptimization, ghostMode, List.of());
    }

    public static Process launchInDirectory(Path root, Path gameDirectory, String version, String name, int memoryGb,
                                             ExternalAuth.Session session, Path injector, String authlibUrl,
                                             boolean automaticHeap, boolean automaticOptimization, boolean ghostMode,
                                             List<String> extraGameArguments) throws IOException {
        JsonObject profile;
        try (var reader = Files.newBufferedReader(root.resolve("versions/" + version + "/" + version + ".json"))) {
            profile = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject info = effectiveProfile(root, profile);
        String gameJarVersion = profile.has("jar") ? profile.get("jar").getAsString()
                : profile.has("inheritsFrom") ? profile.get("inheritsFrom").getAsString() : version;
        int major = info.has("javaVersion") ? info.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
        JavaOptimizer.Plan optimization = JAVA_OPTIMIZER.plan(gameDirectory.resolve("mods"), major, memoryGb,
                automaticHeap, automaticOptimization);
        boolean useRosetta = requiresRosetta(info);
        String runtimeArchitecture = useRosetta ? "x64" : null;
        Path executable = new JavaRuntimeManager(root.getParent().getParent().resolve("runtimes")).ensure(major, message -> {}, runtimeArchitecture);
        JavaOptimizer.CompatibleArguments compatible = automaticOptimization
                ? JavaOptimizer.compatibleArguments(executable, optimization.arguments())
                : new JavaOptimizer.CompatibleArguments(List.of(), List.of());
        List<String> selectedOptimizationArguments = automaticOptimization ? compatible.arguments() : List.of();
        var classpath = new ArrayList<String>();
        Path natives = root.resolve("natives");
        Files.createDirectories(natives);
        if (Files.isSymbolicLink(natives)) throw new IOException("Некорректная папка natives.");
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "osx" : osName.contains("win") ? "windows" : "linux";
        if (info.has("libraries")) for (var item : info.getAsJsonArray("libraries")) {
            var library = item.getAsJsonObject();
            if (!VersionInstaller.allowed(library)) continue;
            if (library.has("downloads")) {
                var downloads = library.getAsJsonObject("downloads");
                if (downloads.has("artifact")) classpath.add(libraryPath(root, downloads.getAsJsonObject("artifact")).toString());
            } else if (library.has("name")) {
                Path artifact = libraryPath(root, library.get("name").getAsString());
                if (Files.isRegularFile(artifact)) classpath.add(artifact.toString());
            }
            if (!library.has("downloads")) continue;
            var downloads = library.getAsJsonObject("downloads");
            if (library.has("natives") && library.getAsJsonObject("natives").has(os)) {
                var files = downloads.getAsJsonObject("classifiers");
                if (files != null) {
                    String classifier = VersionInstaller.nativeClassifier(
                            library.getAsJsonObject("natives").get(os).getAsString(), files, useRosetta);
                    if (files.has(classifier)) try (var zip = new ZipFile(libraryPath(root, files.getAsJsonObject(classifier)).toFile())) {
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                            Path target = natives.resolve(entry.getName()).normalize();
                            if (!target.startsWith(natives) || Files.isSymbolicLink(target)) throw new IOException("Некорректный файл natives.");
                            Files.createDirectories(target.getParent());
                            try (var input = zip.getInputStream(entry)) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
                        }
                    }
                }
            }
        }
        classpath.add(root.resolve("versions/" + gameJarVersion + "/" + gameJarVersion + ".jar").toString());
        var values = new HashMap<String, String>();
        values.put("auth_player_name", session != null ? session.name() : name);
        values.put("auth_uuid", session != null ? session.uuid()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""));
        values.put("auth_access_token", session != null ? session.accessToken() : "0");
        values.put("auth_session", session != null ? session.accessToken() : "0");
        values.put("clientId", "");
        values.put("clientid", "");
        values.put("auth_xuid", "");
        values.put("user_type", session != null ? "mojang" : "legacy");
        values.put("user_properties", "{}");
        values.put("version_name", info.has("id") ? info.get("id").getAsString() : version);
        values.put("version_type", info.has("type") ? info.get("type").getAsString() : "release");
        values.put("game_directory", gameDirectory.toString());
        values.put("assets_root", root.resolve("assets").toString());
        values.put("game_assets", root.resolve("assets/virtual/legacy").toString());
        values.put("assets_index_name", info.has("assetIndex") ? info.getAsJsonObject("assetIndex").get("id").getAsString() : "legacy");
        values.put("natives_directory", natives.toString());
        values.put("launcher_name", "CactusLauncher");
        values.put("launcher_version", "2.0");
        values.put("classpath", String.join(File.pathSeparator, classpath));
        values.put("classpath_separator", File.pathSeparator);
        values.put("library_directory", root.resolve("libraries").toString());
        var command = new ArrayList<String>();
        command.add(executable.toString());
        if (os.equals("osx")) command.add("-XstartOnFirstThread");
        if (injector != null && authlibUrl != null) command.add("-javaagent:" + injector + "=" + authlibUrl);
        if (info.has("arguments")) {
            append(command, info.getAsJsonObject("arguments").getAsJsonArray("jvm"), values);
        } else {
            command.add("-Djava.library.path=" + natives);
            command.add("-cp"); command.add(values.get("classpath"));
        }
        if (automaticOptimization) command.removeIf(JavaOptimizer::isCatalogArgument);
        command.removeIf(argument -> argument.startsWith("-Xmx"));
        command.removeIf(argument -> argument.startsWith("-XX:MaxRAMPercentage="));
        command.add("-Xmx" + optimization.heapGb() + "G");
        command.addAll(selectedOptimizationArguments);
        command.add(info.get("mainClass").getAsString());
        if (info.has("arguments")) append(command, info.getAsJsonObject("arguments").getAsJsonArray("game"), values);
        else for (String argument : info.get("minecraftArguments").getAsString().split(" +")) command.add(expand(argument, values));
        command.addAll(extraGameArguments);
        Files.createDirectories(root.resolve("logs"));
        Path launchLog = root.resolve("logs/cactus-launch.log");
        Files.newBufferedWriter(launchLog, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).close();
        Files.writeString(launchLog, "Настройки запуска Java: heap=" + optimization.heapGb() + " ГБ, GC="
                        + optimization.collector() + ", модов=" + optimization.modJarCount() + ", CPU="
                        + optimization.machine().processorName() + ", GPU=" + optimization.machine().graphicsName()
                        + ", RAM=" + Math.max(0, optimization.machine().totalMemoryBytes() / (1024L * 1024L * 1024L))
                        + " ГБ, флаги=" + (selectedOptimizationArguments.isEmpty() ? "нет" : String.join(" ", selectedOptimizationArguments))
                        + (compatible.rejected().isEmpty() ? "" : ", JVM не приняла=" + String.join(" ", compatible.rejected())) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        if (ghostMode) {
            GhostModeHandoff.start(command, root, launchLog, optimization.machine());
            return null;
        }
        Process process = new ProcessBuilder(command).directory(gameDirectory.toFile()).redirectErrorStream(true).start();
        Thread outputLogger = new Thread(() -> writeGameOutput(process, launchLog), "cactus-game-output");
        outputLogger.setDaemon(true);
        outputLogger.start();
        return process;
    }

    public static JavaOptimizer.Plan optimizationPlan(Path root, String version, int requestedHeapGb,
                                                       boolean automaticHeap, boolean automaticOptimization) throws IOException {
        int javaMajor = JavaOptimizer.javaMajorForMinecraftVersion(version);
        Path versionJson = root.resolve("versions").resolve(version).resolve(version + ".json");
        if (Files.isRegularFile(versionJson, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(versionJson)) {
            JsonObject profile;
            try (var reader = Files.newBufferedReader(versionJson)) {
                profile = effectiveProfile(root, JsonParser.parseReader(reader).getAsJsonObject());
            }
            if (profile.has("javaVersion") && profile.getAsJsonObject("javaVersion").has("majorVersion"))
                javaMajor = profile.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }
        return JAVA_OPTIMIZER.plan(root.resolve("mods"), javaMajor, requestedHeapGb, automaticHeap, automaticOptimization);
    }

    private static JsonObject effectiveProfile(Path root, JsonObject profile) throws IOException {
        if (!profile.has("inheritsFrom")) return profile;
        String parentId = profile.get("inheritsFrom").getAsString();
        if (!parentId.matches("[A-Za-z0-9._+-]{1,128}")) throw new IOException("Некорректная родительская версия загрузчика.");
        Path parentFile = root.resolve("versions").resolve(parentId).resolve(parentId + ".json");
        if (Files.isSymbolicLink(parentFile) || !Files.isRegularFile(parentFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Не установлена базовая версия Minecraft " + parentId + ".");
        JsonObject parent;
        try (var reader = Files.newBufferedReader(parentFile)) { parent = JsonParser.parseReader(reader).getAsJsonObject(); }
        if (parent.has("inheritsFrom")) throw new IOException("Вложенные профили загрузчика не поддерживаются.");
        JsonObject result = parent.deepCopy();
        JsonArray libraries = new JsonArray();
        Map<String, JsonElement> byName = new LinkedHashMap<>();
        if (parent.has("libraries")) for (JsonElement item : parent.getAsJsonArray("libraries")) {
            JsonObject lib = item.getAsJsonObject(); if (lib.has("name")) byName.put(lib.get("name").getAsString(), item);
        }
        if (profile.has("libraries")) for (JsonElement item : profile.getAsJsonArray("libraries")) {
            JsonObject lib = item.getAsJsonObject();
            if (lib.has("name")) byName.put(lib.get("name").getAsString(), item);
            else libraries.add(item);
        }
        byName.values().forEach(libraries::add);
        result.add("libraries", libraries);
        if (parent.has("arguments") || profile.has("arguments")) {
            JsonObject args = new JsonObject();
            for (String group : List.of("jvm", "game")) {
                JsonArray merged = new JsonArray();
                if (parent.has("arguments") && parent.getAsJsonObject("arguments").has(group))
                    parent.getAsJsonObject("arguments").getAsJsonArray(group).forEach(merged::add);
                if (profile.has("arguments") && profile.getAsJsonObject("arguments").has(group))
                    profile.getAsJsonObject("arguments").getAsJsonArray(group).forEach(merged::add);
                args.add(group, merged);
            }
            result.add("arguments", args);
        }
        for (var entry : profile.entrySet()) if (!Set.of("libraries", "arguments", "inheritsFrom", "jar").contains(entry.getKey()))
            result.add(entry.getKey(), entry.getValue().deepCopy());
        result.addProperty("id", profile.has("id") ? profile.get("id").getAsString() : parentId);
        if (profile.has("minecraftArguments")) result.add("minecraftArguments", profile.get("minecraftArguments").deepCopy());
        if (profile.has("jar")) result.add("jar", profile.get("jar").deepCopy());
        return result;
    }

    private static void writeGameOutput(Process process, Path logFile) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase(Locale.ROOT).contains("multiplayer is disabled")) continue;
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ignored) {
            // Drain output even if the log file becomes unavailable, so the game cannot block on its pipe.
            try { process.getInputStream().transferTo(OutputStream.nullOutputStream()); }
            catch (IOException ignoredAgain) { }
        }
    }

    static boolean requiresRosetta(JsonObject info) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") || !(architecture.equals("aarch64") || architecture.equals("arm64")) || !info.has("libraries")) return false;
        for (var item : info.getAsJsonArray("libraries")) {
            var library = item.getAsJsonObject();
            if (!library.has("name") || !(library.get("name").getAsString().startsWith("org.lwjgl.lwjgl:lwjgl:") || library.get("name").getAsString().startsWith("org.lwjgl:lwjgl"))) continue;
            if (!library.has("natives") || !library.getAsJsonObject("natives").has("osx")) continue;
            String template = library.getAsJsonObject("natives").get("osx").getAsString();
            JsonObject classifiers = library.has("downloads") && library.getAsJsonObject("downloads").has("classifiers")
                    ? library.getAsJsonObject("downloads").getAsJsonObject("classifiers") : new JsonObject();
            String armClassifier = template.replace("${arch}", "arm64");
            if (!armClassifier.toLowerCase(Locale.ROOT).contains("arm64") || !classifiers.has(armClassifier)) return true;
        }
        return false;
    }

    private static Path libraryPath(Path root, JsonObject artifact) throws IOException {
        Path path = root.resolve("libraries").resolve(artifact.get("path").getAsString()).normalize();
        if (!path.startsWith(root.resolve("libraries")) || !Files.isRegularFile(path)) throw new IOException("Отсутствует библиотека Minecraft.");
        return path;
    }

    private static Path libraryPath(Path root, String coordinate) throws IOException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || parts.length > 4) throw new IOException("Некорректная библиотека загрузчика.");
        for (String part : parts) if (!part.matches("[A-Za-z0-9._+-]+")) throw new IOException("Некорректная библиотека загрузчика.");
        String group = parts[0].replace('.', '/'), artifact = parts[1], version = parts[2];
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        Path path = root.resolve("libraries").resolve(group).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + classifier + ".jar").normalize();
        if (!path.startsWith(root.resolve("libraries"))) throw new IOException("Некорректный путь библиотеки.");
        return path;
    }

    private static void append(List<String> command, JsonArray arguments, Map<String, String> values) throws IOException {
        if (arguments == null) return;
        for (var argument : arguments) {
            if (argument.isJsonPrimitive()) command.add(expand(argument.getAsString(), values));
            else {
                var object = argument.getAsJsonObject();
                if (!VersionInstaller.allowed(object)) continue;
                var value = object.get("value");
                if (value.isJsonArray()) for (var element : value.getAsJsonArray()) command.add(expand(element.getAsString(), values));
                else command.add(expand(value.getAsString(), values));
            }
        }
    }

    private static String expand(String text, Map<String, String> values) throws IOException {
        for (var entry : values.entrySet()) text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        if (text.contains("${")) throw new IOException("Версия требует неподдерживаемый параметр запуска: " + text);
        return text;
    }
}
