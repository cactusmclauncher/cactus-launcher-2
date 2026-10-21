package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Updates of installed mods through Modrinth's hash endpoints ({@code /version_files} and
 * {@code /version_files/update}), filtered by the build's loader and Minecraft version.
 * Replaced jars go to {@link ModBackups}; disabled mods stay disabled.
 */
public final class ModUpdater {
    public record Dependency(String projectId, String title, String version, String fileName) {}
    /** {@code fileName}: the installed jar without ".disabled". */
    public record Update(String fileName, boolean enabled, String projectId, String title, String currentVersion,
                         String newVersion, String newFileName, List<Dependency> newDependencies) {}
    public record Result(int updated, int dependencies, String backupId) {}

    interface Api {
        JsonElement get(String path) throws IOException;
        JsonElement post(String path, JsonObject body) throws IOException;
        /** Downloads to {@code target} (a new file), verifying size and hash. */
        void download(ModrinthClient.FileInfo file, Path target) throws IOException;
    }

    record Installed(String fileName, boolean enabled) {}
    record Candidate(String hash, Installed installed, String projectId, String currentVersion, String newVersion,
                     ModrinthClient.FileInfo file, List<String> requiredDependencies) {}
    record Resolved(String projectId, String version, ModrinthClient.FileInfo file) {}
    private record Planned(Candidate candidate, String title, List<Resolved> dependencies) {}

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{3,64}");
    private static final int MAX_DEPENDENCIES = 32;
    private final Api api;

    public ModUpdater(ModrinthClient client) {
        this(new Api() {
            @Override public JsonElement get(String path) throws IOException { return client.apiGet(path); }
            @Override public JsonElement post(String path, JsonObject body) throws IOException { return client.apiPost(path, body); }
            @Override public void download(ModrinthClient.FileInfo file, Path target) throws IOException { client.downloadVerified(file, target); }
        });
    }

    ModUpdater(Api api) { this.api = api; }

    public List<Update> check(Path mods, String gameVersion, String loader) throws IOException {
        var result = new ArrayList<Update>();
        for (Planned planned : plan(mods, gameVersion, loader, 0)) {
            Candidate candidate = planned.candidate();
            result.add(new Update(candidate.installed().fileName(), candidate.installed().enabled(), candidate.projectId(), planned.title(),
                    candidate.currentVersion(), candidate.newVersion(), candidate.file().fileName(),
                    planned.dependencies().stream().map(dep -> new Dependency(dep.projectId(), titleOf(dep.projectId(), planned), dep.version(), dep.file().fileName())).toList()));
        }
        return List.copyOf(result);
    }

    private final Map<String, String> titles = new HashMap<>();
    private String titleOf(String projectId, Planned ignored) { return titles.getOrDefault(projectId, projectId); }

    /**
     * Applies updates for {@code onlyFiles} (installed jar names; null means all). Updates that need a new
     * dependency are skipped unless {@code allowNewDependencies}. {@code deadlineNanos} (System.nanoTime, 0 for none)
     * is checked before each network step and before files are touched; past it nothing is changed.
     */
    public Result apply(Path mods, String gameVersion, String loader, Set<String> onlyFiles,
                        boolean allowNewDependencies, long deadlineNanos) throws IOException {
        ModrinthClient.requireSafeModsDirectory(mods, true);
        List<Planned> plan = plan(mods, gameVersion, loader, deadlineNanos).stream()
                .filter(it -> onlyFiles == null || onlyFiles.contains(it.candidate().installed().fileName()))
                .filter(it -> allowNewDependencies || it.dependencies().isEmpty()).toList();
        if (plan.isEmpty()) return new Result(0, 0, null);
        Path buildFolder = mods.toAbsolutePath().normalize().getParent();
        Path stage = Files.createDirectory(mods.resolve(".mod-update-" + UUID.randomUUID()));
        try {
            var staged = new HashMap<ModrinthClient.FileInfo, Path>();
            int counter = 0;
            for (Planned planned : plan) {
                var files = new ArrayList<ModrinthClient.FileInfo>();
                files.add(planned.candidate().file());
                planned.dependencies().forEach(dep -> files.add(dep.file()));
                for (var file : files) {
                    checkDeadline(deadlineNanos);
                    Path part = stage.resolve((counter++) + "-" + file.fileName() + ".part");
                    api.download(file, part);
                    staged.put(file, part);
                }
            }
            checkDeadline(deadlineNanos);
            return commit(mods, buildFolder, plan, staged);
        } finally {
            ModrinthClient.deleteTree(stage);
        }
    }

    private Result commit(Path mods, Path buildFolder, List<Planned> plan, Map<ModrinthClient.FileInfo, Path> staged) throws IOException {
        Path backup = ModBackups.create(buildFolder);
        Properties index = ModrinthClient.readIndex(mods);
        Properties manifest = new Properties();
        var undo = new ArrayDeque<Path[]>();
        int replaced = 0, added = 0;
        try {
            for (Planned planned : plan) {
                Candidate candidate = planned.candidate();
                String suffix = candidate.installed().enabled() ? "" : ".disabled";
                Path old = mods.resolve(candidate.installed().fileName() + suffix);
                if (Files.isSymbolicLink(old) || !Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Файл мода изменился во время обновления: " + candidate.installed().fileName());
                Path target = mods.resolve(candidate.file().fileName() + suffix);
                if (!target.equals(old) && Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Файл уже существует: " + target.getFileName());
                String prefix = "replaced." + replaced + ".";
                Path saved = backup.resolve("replaced-" + replaced + ".jar");
                ModrinthClient.move(old, saved);
                undo.push(new Path[] {saved, old});
                ModrinthClient.move(staged.get(candidate.file()), target);
                undo.push(new Path[] {target, null});
                String project = candidate.projectId();
                String indexPrefix = "mod." + project + ".";
                boolean managed = candidate.installed().fileName().equals(index.getProperty(indexPrefix + "file"));
                manifest.setProperty(prefix + "file", old.getFileName().toString());
                manifest.setProperty(prefix + "backup", saved.getFileName().toString());
                manifest.setProperty(prefix + "new", candidate.file().fileName());
                manifest.setProperty(prefix + "project", project);
                manifest.setProperty(prefix + "managed", Boolean.toString(managed));
                if (managed) {
                    manifest.setProperty(prefix + "oldVersion", index.getProperty(indexPrefix + "version", ""));
                    manifest.setProperty(prefix + "oldHash", index.getProperty(indexPrefix + "hash", ""));
                }
                record(index, project, planned.title(), candidate.newVersion(), candidate.file());
                replaced++;
                for (Resolved dependency : planned.dependencies()) {
                    Path dependencyTarget = mods.resolve(dependency.file().fileName());
                    if (Files.exists(dependencyTarget, LinkOption.NOFOLLOW_LINKS) || Files.exists(mods.resolve(dependency.file().fileName() + ".disabled"), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Файл зависимости уже существует: " + dependency.file().fileName());
                    ModrinthClient.move(staged.get(dependency.file()), dependencyTarget);
                    undo.push(new Path[] {dependencyTarget, null});
                    manifest.setProperty("added." + added + ".file", dependency.file().fileName());
                    manifest.setProperty("added." + added + ".project", dependency.projectId());
                    record(index, dependency.projectId(), titles.getOrDefault(dependency.projectId(), dependency.projectId()), dependency.version(), dependency.file());
                    added++;
                }
            }
            manifest.setProperty("replaced.count", Integer.toString(replaced));
            manifest.setProperty("added.count", Integer.toString(added));
            manifest.setProperty("created", Long.toString(System.currentTimeMillis()));
            manifest.setProperty("confirmed", "false");
            ModBackups.writeManifest(backup, manifest);
            ModrinthClient.writeIndex(mods, index, backup);
        } catch (IOException | RuntimeException e) {
            while (!undo.isEmpty()) {
                Path[] step = undo.pop();
                try {
                    if (step[1] == null) Files.deleteIfExists(step[0]);
                    else ModrinthClient.move(step[0], step[1]);
                } catch (IOException restore) { e.addSuppressed(restore); }
            }
            try { ModrinthClient.deleteTree(backup); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof IOException io) throw io;
            throw new IOException("Не удалось обновить моды.", e);
        }
        ModBackups.prune(buildFolder, ModBackups.KEEP);
        return new Result(replaced, added, backup.getFileName().toString());
    }

    private static void record(Properties index, String project, String title, String version, ModrinthClient.FileInfo file) {
        String prefix = "mod." + project + ".";
        index.setProperty(prefix + "file", file.fileName());
        if (title != null && !title.isBlank()) index.setProperty(prefix + "title", title);
        index.setProperty(prefix + "version", version);
        index.setProperty(prefix + "hash", file.algorithm() + ":" + file.hash());
    }

    private List<Planned> plan(Path mods, String gameVersion, String loader, long deadlineNanos) throws IOException {
        String loaderId = ModrinthClient.loaderId(loader);
        ModrinthClient.requireSafeModsDirectory(mods, false);
        Map<String, Installed> installed = scan(mods);
        if (installed.isEmpty()) return List.of();
        JsonArray hashes = new JsonArray();
        installed.keySet().forEach(hashes::add);
        JsonObject currentBody = new JsonObject();
        currentBody.add("hashes", hashes);
        currentBody.addProperty("algorithm", "sha512");
        checkDeadline(deadlineNanos);
        JsonObject current = asObject(api.post("/version_files", currentBody));
        JsonObject updateBody = currentBody.deepCopy();
        updateBody.add("loaders", array(loaderId));
        updateBody.add("game_versions", array(gameVersion));
        checkDeadline(deadlineNanos);
        JsonObject latest = asObject(api.post("/version_files/update", updateBody));

        Set<String> installedProjects = new HashSet<>();
        for (var entry : current.entrySet()) if (entry.getValue().isJsonObject())
            installedProjects.add(ModrinthClient.safeString(entry.getValue().getAsJsonObject(), "project_id", ""));
        Properties index = ModrinthClient.readIndex(mods);
        for (String key : index.stringPropertyNames()) if (key.startsWith("mod.") && key.endsWith(".file")) installedProjects.add(key.substring(4, key.length() - 5));

        List<Candidate> candidates = select(installed, current, latest, gameVersion, loaderId, installedProjects);
        Set<String> known = new HashSet<>(installedProjects);
        candidates.forEach(it -> known.add(it.projectId()));
        var planned = new ArrayList<Candidate>();
        var dependencies = new HashMap<Candidate, List<Resolved>>();
        int total = 0;
        for (Candidate candidate : candidates) {
            checkDeadline(deadlineNanos);
            var resolved = new ArrayList<Resolved>();
            Set<String> visible = new HashSet<>(known);
            try {
                resolveDependencies(candidate.requiredDependencies(), gameVersion, loaderId, visible, resolved, 0);
            } catch (IOException e) {
                continue; // No compatible dependency: keep the current version of this mod.
            }
            if (total + resolved.size() > MAX_DEPENDENCIES) continue;
            total += resolved.size();
            resolved.forEach(dep -> known.add(dep.projectId()));
            planned.add(candidate);
            dependencies.put(candidate, resolved);
        }
        loadTitles(planned, dependencies, index, deadlineNanos);
        return planned.stream().map(candidate -> new Planned(candidate,
                titles.getOrDefault(candidate.projectId(), candidate.installed().fileName()), List.copyOf(dependencies.get(candidate)))).toList();
    }

    private void loadTitles(List<Candidate> candidates, Map<Candidate, List<Resolved>> dependencies, Properties index, long deadlineNanos) {
        Set<String> ids = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            ids.add(candidate.projectId());
            dependencies.get(candidate).forEach(dep -> ids.add(dep.projectId()));
        }
        for (String id : ids) {
            String title = index.getProperty("mod." + id + ".title");
            if (title != null && !title.isBlank()) titles.put(id, title);
        }
        ids.removeIf(titles::containsKey);
        if (ids.isEmpty()) return;
        try {
            checkDeadline(deadlineNanos);
            JsonArray request = new JsonArray();
            ids.forEach(request::add);
            JsonElement projects = api.get("/projects?ids=" + URLEncoder.encode(request.toString(), StandardCharsets.UTF_8));
            if (projects.isJsonArray()) for (var value : projects.getAsJsonArray()) {
                JsonObject project = value.getAsJsonObject();
                String id = ModrinthClient.safeString(project, "id", "");
                String title = ModrinthClient.safeString(project, "title", "");
                if (ids.contains(id) && !title.isBlank()) titles.put(id, title);
            }
        } catch (IOException | RuntimeException ignored) {
            // Titles are cosmetic; file names are shown instead.
        }
    }

    private void resolveDependencies(List<String> required, String gameVersion, String loader, Set<String> known,
                                     List<Resolved> result, int depth) throws IOException {
        if (depth > 8) throw new IOException("Слишком много обязательных зависимостей Modrinth.");
        for (String dependency : required) {
            JsonObject version;
            if (dependency.startsWith("version:")) {
                version = api.get("/version/" + URLEncoder.encode(dependency.substring(8), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!compatible(version, gameVersion, loader)) throw new IOException("Несовместимая зависимость.");
            } else {
                if (known.contains(dependency)) continue;
                String query = "?game_versions=" + URLEncoder.encode(array(gameVersion).toString(), StandardCharsets.UTF_8)
                        + "&loaders=" + URLEncoder.encode(array(loader).toString(), StandardCharsets.UTF_8);
                JsonElement versions = api.get("/project/" + URLEncoder.encode(dependency, StandardCharsets.UTF_8) + "/version" + query);
                version = versions.isJsonArray() ? ModrinthClient.chooseVersion(versions.getAsJsonArray()) : null;
                if (version == null || !compatible(version, gameVersion, loader)) throw new IOException("Нет совместимой версии зависимости " + dependency + ".");
            }
            String projectId = ModrinthClient.requiredString(version, "project_id");
            if (!ID.matcher(projectId).matches()) throw new IOException("Некорректный ID Modrinth.");
            if (!known.add(projectId)) continue;
            result.add(new Resolved(projectId, ModrinthClient.safeString(version, "version_number", ""), ModrinthClient.primaryFile(version)));
            if (result.size() > MAX_DEPENDENCIES) throw new IOException("Слишком много обязательных зависимостей Modrinth.");
            resolveDependencies(requiredDependencies(version), gameVersion, loader, known, result, depth + 1);
        }
    }

    /**
     * Picks the updates worth installing: a different, newer, listed version for this loader and Minecraft version,
     * not a pre-release when a release is installed, with a valid file and no conflict with installed mods.
     */
    static List<Candidate> select(Map<String, Installed> installed, JsonObject current, JsonObject latest,
                                  String gameVersion, String loader, Set<String> installedProjects) {
        var result = new ArrayList<Candidate>();
        var projects = new HashSet<String>();
        for (var entry : installed.entrySet()) {
            JsonObject now = member(current, entry.getKey());
            JsonObject next = member(latest, entry.getKey());
            if (now == null || next == null) continue;
            String projectId = ModrinthClient.safeString(next, "project_id", "");
            if (!ID.matcher(projectId).matches() || !projectId.equals(ModrinthClient.safeString(now, "project_id", ""))) continue;
            if (ModrinthClient.safeString(next, "id", "").equals(ModrinthClient.safeString(now, "id", ""))) continue;
            if (!"listed".equals(ModrinthClient.safeString(next, "status", "listed")) || !compatible(next, gameVersion, loader)) continue;
            if ("release".equals(ModrinthClient.safeString(now, "version_type", "release"))
                    && !"release".equals(ModrinthClient.safeString(next, "version_type", "release"))) continue;
            if (!newer(next, now)) continue;
            ModrinthClient.FileInfo file;
            try { file = ModrinthClient.primaryFile(next); } catch (IOException | RuntimeException e) { continue; }
            if ("SHA-512".equals(file.algorithm()) && file.hash().equals(entry.getKey())) continue;
            if (conflicts(next, installedProjects) || !projects.add(projectId)) continue;
            result.add(new Candidate(entry.getKey(), entry.getValue(), projectId,
                    ModrinthClient.safeString(now, "version_number", ""), ModrinthClient.safeString(next, "version_number", ""),
                    file, requiredDependencies(next).stream().filter(dep -> !installedProjects.contains(dep)).toList()));
        }
        result.sort(Comparator.comparing(candidate -> candidate.installed().fileName(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Required dependencies as project ids, or {@code "version:<id>"} when Modrinth names only a version. */
    static List<String> requiredDependencies(JsonObject version) {
        var result = new ArrayList<String>();
        JsonArray dependencies = version.has("dependencies") && version.get("dependencies").isJsonArray() ? version.getAsJsonArray("dependencies") : new JsonArray();
        for (var value : dependencies) {
            if (!value.isJsonObject()) continue;
            JsonObject dependency = value.getAsJsonObject();
            if (!"required".equals(ModrinthClient.safeString(dependency, "dependency_type", ""))) continue;
            String project = ModrinthClient.safeString(dependency, "project_id", "");
            String versionId = ModrinthClient.safeString(dependency, "version_id", "");
            if (ID.matcher(project).matches()) result.add(project);
            else if (ID.matcher(versionId).matches()) result.add("version:" + versionId);
        }
        return result;
    }

    private static boolean conflicts(JsonObject version, Set<String> installedProjects) {
        JsonArray dependencies = version.has("dependencies") && version.get("dependencies").isJsonArray() ? version.getAsJsonArray("dependencies") : new JsonArray();
        for (var value : dependencies) {
            if (!value.isJsonObject()) continue;
            JsonObject dependency = value.getAsJsonObject();
            if ("incompatible".equals(ModrinthClient.safeString(dependency, "dependency_type", ""))
                    && installedProjects.contains(ModrinthClient.safeString(dependency, "project_id", ""))) return true;
        }
        return false;
    }

    private static boolean compatible(JsonObject version, String gameVersion, String loader) {
        JsonElement games = version.get("game_versions");
        JsonElement loaders = version.get("loaders");
        return games != null && games.isJsonArray() && ModrinthClient.contains(games.getAsJsonArray(), gameVersion)
                && loaders != null && loaders.isJsonArray() && ModrinthClient.contains(loaders.getAsJsonArray(), loader);
    }

    private static boolean newer(JsonObject next, JsonObject now) {
        try {
            return Instant.parse(ModrinthClient.safeString(next, "date_published", ""))
                    .isAfter(Instant.parse(ModrinthClient.safeString(now, "date_published", "")));
        } catch (DateTimeParseException e) {
            return true; // Without dates the different version id is all we know.
        }
    }

    /** SHA-512 of every jar (enabled or ".disabled") directly in {@code mods}. */
    static Map<String, Installed> scan(Path mods) throws IOException {
        var result = new LinkedHashMap<String, Installed>();
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return result;
        try (var files = Files.list(mods)) {
            for (Path path : files.sorted().toList()) {
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean enabled = lower.endsWith(".jar");
                if (!enabled && !lower.endsWith(".jar.disabled")) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                String base = enabled ? name : name.substring(0, name.length() - ".disabled".length());
                if (!ModrinthClient.safeFileName(base)) continue;
                result.putIfAbsent(sha512(path), new Installed(base, enabled));
            }
        }
        return result;
    }

    private static String sha512(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Алгоритм проверки файла не поддерживается.", e);
        }
    }

    private static void checkDeadline(long deadlineNanos) throws IOException {
        if (deadlineNanos != 0 && System.nanoTime() - deadlineNanos > 0) throw new IOException("Modrinth не ответил вовремя.");
    }

    private static JsonObject member(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonObject asObject(JsonElement element) throws IOException {
        if (element == null || !element.isJsonObject()) throw new IOException("Modrinth вернул некорректные данные.");
        return element.getAsJsonObject();
    }

    private static JsonArray array(String value) { JsonArray array = new JsonArray(); array.add(value); return array; }
}
