package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Modrinth search and per-instance mod installation. Network calls are synchronous and belong on IO. */
public final class ModrinthClient {
    public record Project(String id, String title, String description, String author, long downloads, String iconUrl) {}
    public record InstalledMod(String fileName, String title, String projectId, String version, boolean enabled) {}
    private record FileInfo(String fileName, String url, String algorithm, String hash, long size) {}
    private record Resolved(String projectId, String title, String version, FileInfo file) {}

    private static final String API = "https://api.modrinth.com/v2";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{3,64}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+ -]{0,199}\\.jar", Pattern.CASE_INSENSITIVE);
    private static final long MAX_MOD_SIZE = 256L * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<Project> search(String query, String minecraftVersion, String loader, int offset) throws IOException {
        if (offset < 0 || offset > 10000) throw new IOException("Некорректная позиция в каталоге модов.");
        String loaderId = loaderId(loader);
        JsonArray facets = new JsonArray();
        facets.add(facet("project_type:mod"));
        facets.add(facet("versions:" + minecraftVersion));
        facets.add(facet("categories:" + loaderId));
        String encodedFacets = URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8);
        String searchQuery = query == null ? "" : query.strip();
        String index = searchQuery.isEmpty() ? "downloads" : "relevance";
        String url = API + "/search?query=" + URLEncoder.encode(searchQuery, StandardCharsets.UTF_8)
                + "&facets=" + encodedFacets + "&index=" + index + "&limit=30&offset=" + offset;
        JsonObject response = getJson(url, API);
        var result = new ArrayList<Project>();
        JsonArray hits = response.getAsJsonArray("hits");
        if (hits == null) return List.of();
        for (var value : hits) {
            JsonObject hit = value.getAsJsonObject();
            result.add(new Project(
                    requiredString(hit, "project_id"),
                    safeString(hit, "title", "Без названия"),
                    safeString(hit, "description", ""),
                    safeString(hit, "author", ""),
                    hit.has("downloads") ? hit.get("downloads").getAsLong() : 0,
                    safeString(hit, "icon_url", "")
            ));
        }
        return List.copyOf(result);
    }

    public byte[] icon(String url) throws IOException {
        if (!isModrinthCdn(url)) return null;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "CactusLauncher/2.0").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200 || !isModrinthCdn(response.uri().toString())) return null;
                byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                return bytes.length > 0 && bytes.length <= 2 * 1024 * 1024 ? bytes : null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Загрузка иконки отменена.");
        }
    }

    public List<InstalledMod> installed(Path modsDirectory) throws IOException {
        requireSafeModsDirectory(modsDirectory, false);
        if (!Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Properties index = readIndex(modsDirectory);
        var managed = new HashMap<String, InstalledMod>();
        for (String key : index.stringPropertyNames()) {
            if (!key.startsWith("mod.") || !key.endsWith(".file")) continue;
            String projectId = key.substring(4, key.length() - 5);
            String file = index.getProperty(key);
            if (!safeFileName(file)) throw new IOException("Индекс Modrinth содержит некорректное имя файла.");
            Path installedPath = modsDirectory.resolve(file);
            boolean enabled = Files.isRegularFile(installedPath, LinkOption.NOFOLLOW_LINKS);
            if (!enabled) installedPath = modsDirectory.resolve(file + ".disabled");
            if (!Files.isRegularFile(installedPath, LinkOption.NOFOLLOW_LINKS)) continue;
            managed.put(file, new InstalledMod(file,
                    index.getProperty("mod." + projectId + ".title", file), projectId,
                        index.getProperty("mod." + projectId + ".version", ""), enabled));
        }
        try (var files = Files.list(modsDirectory)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !(name.toLowerCase(Locale.ROOT).endsWith(".jar") || name.toLowerCase(Locale.ROOT).endsWith(".jar.disabled"))) continue;
                boolean enabled = name.toLowerCase(Locale.ROOT).endsWith(".jar");
                String actualFile = enabled ? name : name.substring(0, name.length() - ".disabled".length());
                managed.putIfAbsent(actualFile, new InstalledMod(actualFile, actualFile.substring(0, actualFile.length() - 4), "", "", enabled));
            }
        }
        return managed.values().stream().sorted(Comparator.comparing(InstalledMod::title, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void install(String projectId, String minecraftVersion, String loader, Path modsDirectory) throws IOException {
        if (!ID.matcher(projectId).matches()) throw new IOException("Некорректный ID Modrinth.");
        String loaderId = loaderId(loader);
        requireSafeModsDirectory(modsDirectory, true);
        Path stage = modsDirectory.resolve(".modrinth-stage-" + UUID.randomUUID());
        Files.createDirectory(stage);
        try {
            Properties index = readIndex(modsDirectory);
            Set<String> installedProjectIds = activeProjectIds(modsDirectory, index);
            var resolved = new LinkedHashMap<String, Resolved>();
            resolveProject(projectId, minecraftVersion, loaderId, resolved, new HashSet<>(), installedProjectIds, 0);
            var stagedFiles = new LinkedHashMap<Resolved, Path>();
            for (Resolved mod : resolved.values()) {
                if (!safeFileName(mod.file().fileName())) throw new IOException("Modrinth вернул некорректное имя файла.");
                Path target = modsDirectory.resolve(mod.file().fileName());
                if (Files.isSymbolicLink(target)) throw new IOException("Файл мода не должен быть символической ссылкой.");
                if (Files.exists(target) && !ownedBy(index, target.getFileName().toString(), mod.projectId())) {
                    if (matches(target, mod.file())) continue;
                    throw new IOException("Файл уже существует и не принадлежит Modrinth: " + mod.file().fileName());
                }
                Path staged = stage.resolve(mod.file().fileName());
                downloadVerified(mod.file(), staged);
                stagedFiles.put(mod, staged);
            }

            var committed = new ArrayList<Path>();
            var backups = new LinkedHashMap<Path, Path>();
            Properties nextIndex = copy(index);
            try {
                for (Resolved mod : resolved.values()) {
                    Path target = modsDirectory.resolve(mod.file().fileName());
                    Path staged = stagedFiles.get(mod);
                    if (staged != null) {
                        if (Files.exists(target)) {
                            Path backup = stage.resolve("backup-" + UUID.randomUUID());
                            move(target, backup);
                            backups.put(target, backup);
                        }
                        move(staged, target);
                        committed.add(target);
                    }
                    String prefix = "mod." + mod.projectId() + ".";
                    nextIndex.setProperty(prefix + "file", mod.file().fileName());
                    nextIndex.setProperty(prefix + "title", mod.title());
                    nextIndex.setProperty(prefix + "version", mod.version());
                    nextIndex.setProperty(prefix + "hash", mod.file().algorithm() + ":" + mod.file().hash());
                }
                writeIndex(modsDirectory, nextIndex, stage);
                for (String oldKey : index.stringPropertyNames()) {
                    if (!oldKey.startsWith("mod.") || !oldKey.endsWith(".file")) continue;
                    String oldProject = oldKey.substring(4, oldKey.length() - 5);
                    String oldFile = index.getProperty(oldKey);
                    if (!resolved.containsKey(oldProject) || oldFile.equals(nextIndex.getProperty("mod." + oldProject + ".file"))) continue;
                    boolean stillReferenced = nextIndex.stringPropertyNames().stream()
                            .anyMatch(key -> key.startsWith("mod.") && key.endsWith(".file") && oldFile.equals(nextIndex.getProperty(key)));
                    Path stale = modsDirectory.resolve(oldFile);
                    if (!stillReferenced && safeFileName(oldFile) && !Files.isSymbolicLink(stale)
                            && Files.isRegularFile(stale, LinkOption.NOFOLLOW_LINKS)) {
                        try { Files.deleteIfExists(stale); } catch (IOException ignored) { /* Keep the valid install; stale files remain removable from the UI. */ }
                    }
                }
            } catch (IOException e) {
                for (Path path : committed) Files.deleteIfExists(path);
                for (var backup : backups.entrySet()) if (Files.exists(backup.getValue())) move(backup.getValue(), backup.getKey());
                throw e;
            }
        } finally {
            deleteTree(stage);
        }
    }

    public void remove(String fileName, Path modsDirectory) throws IOException {
        if (!safeFileName(fileName)) throw new IOException("Некорректное имя файла мода.");
        requireSafeModsDirectory(modsDirectory, false);
        Path target = modsDirectory.resolve(fileName);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target = modsDirectory.resolve(fileName + ".disabled");
        if (Files.isSymbolicLink(target)) throw new IOException("Файл мода не должен быть символической ссылкой.");
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return;
        Properties index = readIndex(modsDirectory);
        for (String key : index.stringPropertyNames()) {
            if (key.startsWith("mod.") && key.endsWith(".file") && fileName.equals(index.getProperty(key))) {
                removeProject(index, key.substring(4, key.length() - 5));
            }
        }
        Path stage = Files.createTempDirectory(modsDirectory, ".modrinth-remove-");
        try {
            Path backup = stage.resolve(fileName);
            move(target, backup);
            try { writeIndex(modsDirectory, index, stage); }
            catch (IOException e) { move(backup, target); throw e; }
        } finally { deleteTree(stage); }
    }

    public void setEnabled(String fileName, boolean enabled, Path modsDirectory) throws IOException {
        if (!safeFileName(fileName)) throw new IOException("Некорректное имя файла мода.");
        requireSafeModsDirectory(modsDirectory, true);
        Path source = modsDirectory.resolve(fileName);
        Path target = modsDirectory.resolve(fileName + ".disabled");
        if (!enabled) {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) throw new IOException("Файл мода не найден.");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Отключённая копия мода уже существует.");
            move(source, target);
        } else {
            if (!fileName.endsWith(".jar")) throw new IOException("Некорректное имя файла мода.");
            Path disabled = modsDirectory.resolve(fileName + ".disabled");
            if (!Files.isRegularFile(disabled, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(disabled)) throw new IOException("Отключённый мод не найден.");
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Включённая копия мода уже существует.");
            move(disabled, source);
        }
    }

    private void resolveProject(String projectId, String gameVersion, String loader,
                                LinkedHashMap<String, Resolved> result, Set<String> visiting,
                                Set<String> installedProjectIds, int depth) throws IOException {
        if (result.containsKey(projectId)) return;
        if (depth > 24) throw new IOException("Слишком много обязательных зависимостей Modrinth.");
        if (!visiting.add(projectId)) return;
        JsonArray versions = getProjectVersions(projectId, gameVersion, loader);
        JsonObject version = chooseVersion(versions);
        if (version == null) throw new IOException("Нет совместимой версии мода или зависимости: " + projectId);
        verifyNoIncompatibleMods(version, projectId, result.keySet(), visiting, installedProjectIds);
        JsonArray dependencies = version.has("dependencies") ? version.getAsJsonArray("dependencies") : new JsonArray();
        for (var value : dependencies) {
            JsonObject dependency = value.getAsJsonObject();
            if (!"required".equals(safeString(dependency, "dependency_type", ""))) continue;
            String dependencyVersionId = safeString(dependency, "version_id", "");
            String dependencyProjectId = safeString(dependency, "project_id", "");
            if (!dependencyVersionId.isEmpty()) resolveVersion(dependencyVersionId, gameVersion, loader, result, visiting, installedProjectIds, depth + 1);
            else if (!dependencyProjectId.isEmpty()) resolveProject(dependencyProjectId, gameVersion, loader, result, visiting, installedProjectIds, depth + 1);
            else throw new IOException("Обязательная зависимость Modrinth не содержит ID проекта или версии.");
        }
        String title = getProjectTitle(projectId);
        result.put(projectId, new Resolved(projectId, title, requiredString(version, "version_number"), primaryFile(version)));
        visiting.remove(projectId);
    }

    private void resolveVersion(String versionId, String gameVersion, String loader,
                                LinkedHashMap<String, Resolved> result, Set<String> visiting,
                                Set<String> installedProjectIds, int depth) throws IOException {
        if (depth > 24) throw new IOException("Слишком много обязательных зависимостей Modrinth.");
        JsonObject version = getJson(API + "/version/" + encodePath(versionId), API);
        String projectId = requiredString(version, "project_id");
        try { verifyCompatibility(version, gameVersion, loader); }
        catch (IOException e) { throw new IOException("Несовместимая обязательная зависимость: " + projectId + ". " + e.getMessage(), e); }
        if (result.containsKey(projectId)) return;
        verifyNoIncompatibleMods(version, projectId, result.keySet(), visiting, installedProjectIds);
        if (!visiting.add(projectId)) return;
        JsonArray dependencies = version.has("dependencies") ? version.getAsJsonArray("dependencies") : new JsonArray();
        for (var value : dependencies) {
            JsonObject dependency = value.getAsJsonObject();
            if (!"required".equals(safeString(dependency, "dependency_type", ""))) continue;
            String dependencyVersionId = safeString(dependency, "version_id", "");
            String dependencyProjectId = safeString(dependency, "project_id", "");
            if (!dependencyVersionId.isEmpty()) resolveVersion(dependencyVersionId, gameVersion, loader, result, visiting, installedProjectIds, depth + 1);
            else if (!dependencyProjectId.isEmpty()) resolveProject(dependencyProjectId, gameVersion, loader, result, visiting, installedProjectIds, depth + 1);
            else throw new IOException("Обязательная зависимость Modrinth не содержит ID проекта или версии.");
        }
        result.put(projectId, new Resolved(projectId, getProjectTitle(projectId), requiredString(version, "version_number"), primaryFile(version)));
        visiting.remove(projectId);
    }

    private void verifyNoIncompatibleMods(JsonObject version, String projectId, Set<String> resolvedIds,
                                         Set<String> visiting, Set<String> installedProjectIds) throws IOException {
        JsonArray dependencies = version.has("dependencies") ? version.getAsJsonArray("dependencies") : new JsonArray();
        for (var value : dependencies) {
            JsonObject dependency = value.getAsJsonObject();
            if (!"incompatible".equals(safeString(dependency, "dependency_type", ""))) continue;
            String incompatibleId = safeString(dependency, "project_id", "");
            String versionId = safeString(dependency, "version_id", "");
            if (incompatibleId.isEmpty() && !versionId.isEmpty()) {
                JsonObject incompatibleVersion = getJson(API + "/version/" + encodePath(versionId), API);
                incompatibleId = requiredString(incompatibleVersion, "project_id");
            }
            if (!ID.matcher(incompatibleId).matches()) continue;
            if (installedProjectIds.contains(incompatibleId) || resolvedIds.contains(incompatibleId) || visiting.contains(incompatibleId)) {
                throw new IOException("Конфликт модов: " + projectId + " несовместим с установленным или выбранным модом " + incompatibleId + ".");
            }
        }
    }

    private Set<String> activeProjectIds(Path modsDirectory, Properties index) throws IOException {
        Set<String> projects = new HashSet<>();
        for (String key : index.stringPropertyNames()) {
            if (!key.startsWith("mod.") || !key.endsWith(".file")) continue;
            String projectId = key.substring(4, key.length() - 5);
            String fileName = index.getProperty(key, "");
            if (ID.matcher(projectId).matches() && safeFileName(fileName)
                    && Files.isRegularFile(modsDirectory.resolve(fileName), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(modsDirectory.resolve(fileName))) projects.add(projectId);
        }
        return projects;
    }

    private JsonArray getProjectVersions(String projectId, String gameVersion, String loader) throws IOException {
        JsonArray gameVersions = new JsonArray(); gameVersions.add(gameVersion);
        JsonArray loaders = new JsonArray(); loaders.add(loader);
        String url = API + "/project/" + encodePath(projectId) + "/version?game_versions="
                + URLEncoder.encode(gameVersions.toString(), StandardCharsets.UTF_8)
                + "&loaders=" + URLEncoder.encode(loaders.toString(), StandardCharsets.UTF_8);
        return getJsonElement(url, API).getAsJsonArray();
    }

    private JsonObject chooseVersion(JsonArray versions) {
        if (versions == null || versions.isEmpty()) return null;
        for (var value : versions) {
            JsonObject version = value.getAsJsonObject();
            if ("release".equals(safeString(version, "version_type", ""))
                    && "listed".equals(safeString(version, "status", "listed"))) return version;
        }
        for (var value : versions) if ("listed".equals(safeString(value.getAsJsonObject(), "status", "listed"))) return value.getAsJsonObject();
        return versions.get(0).getAsJsonObject();
    }

    private void verifyCompatibility(JsonObject version, String gameVersion, String loader) throws IOException {
        JsonArray gameVersions = version.getAsJsonArray("game_versions");
        JsonArray loaders = version.getAsJsonArray("loaders");
        if (gameVersions == null || !contains(gameVersions, gameVersion) || loaders == null || !contains(loaders, loader))
            throw new IOException("Версия мода несовместима с Minecraft или загрузчиком этой сборки.");
    }

    private FileInfo primaryFile(JsonObject version) throws IOException {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) throw new IOException("У версии Modrinth отсутствует файл для установки.");
        JsonObject selected = files.get(0).getAsJsonObject();
        for (var value : files) if (value.getAsJsonObject().has("primary") && value.getAsJsonObject().get("primary").getAsBoolean()) {
            selected = value.getAsJsonObject(); break;
        }
        String fileName = requiredString(selected, "filename");
        String url = requiredString(selected, "url");
        JsonObject hashes = selected.getAsJsonObject("hashes");
        String algorithm = hashes.has("sha512") ? "SHA-512" : hashes.has("sha1") ? "SHA-1" : "";
        String hash = algorithm.isEmpty() ? "" : hashes.get(algorithm.equals("SHA-512") ? "sha512" : "sha1").getAsString().toLowerCase(Locale.ROOT);
        if (!safeFileName(fileName) || !algorithm.isEmpty() && !hash.matches(algorithm.equals("SHA-512") ? "[a-f0-9]{128}" : "[a-f0-9]{40}"))
            throw new IOException("Файл Modrinth содержит некорректные метаданные.");
        if (algorithm.isEmpty()) throw new IOException("У файла Modrinth отсутствует контрольная сумма.");
        if (!isModrinthCdn(url)) throw new IOException("Файл мода размещён на неизвестном сервере.");
        long size = selected.has("size") ? selected.get("size").getAsLong() : -1;
        if (size < 1 || size > MAX_MOD_SIZE) throw new IOException("Размер файла мода недопустим.");
        return new FileInfo(fileName, url, algorithm, hash, size);
    }

    private void downloadVerified(FileInfo file, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(file.url())).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "CactusLauncher/2.0").GET().build();
        HttpResponse<InputStream> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Установка мода отменена."); }
        if (response.statusCode() != 200) { response.body().close(); throw new IOException("Modrinth не смог отдать файл мода (HTTP " + response.statusCode() + ")."); }
        try (var input = response.body(); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            MessageDigest digest = digest(file.algorithm());
            byte[] buffer = new byte[65536];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Установка мода отменена.");
                size += count;
                if (size > file.size() || size > MAX_MOD_SIZE) throw new IOException("Скачанный файл больше заявленного размера.");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            if (size != file.size() || !HexFormat.of().formatHex(digest.digest()).equals(file.hash()))
                throw new IOException("Контрольная сумма файла мода не совпала.");
        } catch (InterruptedIOException e) { throw e; }
        catch (IOException e) { Files.deleteIfExists(target); throw e; }
    }

    private String getProjectTitle(String projectId) throws IOException {
        return safeString(getJson(API + "/project/" + encodePath(projectId), API), "title", projectId);
    }

    private JsonObject getJson(String url, String allowedBase) throws IOException {
        return getJsonElement(url, allowedBase).getAsJsonObject();
    }

    private JsonElement getJsonElement(String url, String allowedBase) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !uri.getHost().equals(URI.create(allowedBase).getHost()))
            throw new IOException("Запрос направлен на неизвестный сервер.");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CactusLauncher/2.0").header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("Modrinth вернул ошибку (HTTP " + response.statusCode() + ").");
            return JsonParser.parseString(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Запрос к Modrinth отменён."); }
        catch (JsonParseException e) { throw new IOException("Modrinth вернул некорректные данные.", e); }
    }

    private static void requireSafeModsDirectory(Path directory, boolean create) throws IOException {
        Path mods = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(mods)) throw new IOException("Папка модов не должна быть символической ссылкой.");
        if (create) Files.createDirectories(mods);
        Path parent = mods.getParent();
        if (parent != null && Files.isSymbolicLink(parent)) throw new IOException("Путь к папке модов содержит символическую ссылку.");
    }

    private static Properties readIndex(Path dir) throws IOException {
        Properties properties = new Properties();
        Path index = dir.resolve("modrinth-index.properties");
        if (Files.isSymbolicLink(index)) throw new IOException("Индекс Modrinth не должен быть символической ссылкой.");
        if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) try (var input = Files.newInputStream(index)) { properties.load(input); }
        return properties;
    }

    private static void writeIndex(Path dir, Properties properties, Path stage) throws IOException {
        Path temp = stage.resolve("index-" + UUID.randomUUID() + ".tmp");
        try (var output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            properties.store(output, "Cactus Launcher Modrinth mods");
        }
        move(temp, dir.resolve("modrinth-index.properties"));
    }

    private static Properties copy(Properties source) {
        Properties next = new Properties(); next.putAll(source); return next;
    }

    private static void removeProject(Properties properties, String projectId) {
        String prefix = "mod." + projectId + ".";
        for (String key : properties.stringPropertyNames()) if (key.startsWith(prefix)) properties.remove(key);
    }

    private static boolean ownedBy(Properties index, String file, String projectId) {
        return file.equals(index.getProperty("mod." + projectId + ".file"));
    }

    private static boolean matches(Path target, FileInfo file) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var input = Files.newInputStream(target)) {
            MessageDigest digest = digest(file.algorithm());
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest()).equals(file.hash());
        }
    }

    private static MessageDigest digest(String algorithm) throws IOException {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException e) { throw new IOException("Алгоритм проверки файла не поддерживается.", e); }
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    private static boolean isModrinthCdn(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "cdn.modrinth.com".equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean safeFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) return false;
        try { return Path.of(fileName).getFileName().toString().equals(fileName); }
        catch (InvalidPathException e) { return false; }
    }

    private static String loaderId(String loader) throws IOException {
        if (loader == null) throw new IOException("У выбранной сборки не указан загрузчик модов.");
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "fabric";
            case "forge" -> "forge";
            case "quilt" -> "quilt";
            case "neoforge" -> "neoforge";
            default -> throw new IOException("Для Modrinth нужен Fabric, Forge, Quilt или NeoForge. Установите загрузчик для этой сборки.");
        };
    }

    private static JsonArray facet(String value) { JsonArray facet = new JsonArray(); facet.add(value); return facet; }
    private static String encodePath(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String requiredString(JsonObject object, String key) throws IOException {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IOException("Modrinth не вернул поле " + key + ".");
        return object.get(key).getAsString();
    }
    private static String safeString(JsonObject object, String key, String fallback) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean contains(JsonArray values, String expected) {
        for (var value : values) if (expected.equals(value.getAsString())) return true;
        return false;
    }
}
