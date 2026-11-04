package com.cactuslauncher.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Detects Yandex Browser and 360 Total Security on Windows and force-removes them without the
 * vendor uninstallers: processes are killed first, then folders, services, shortcuts and registry
 * uninstall entries are deleted. Only runs after explicit user consent.
 */
public final class ConflictingSoftwareScanner {
    public record Detection(String id, String name, String detail) {}
    private record Entry(String key, String displayName) {}

    public static final String YANDEX_BROWSER = "yandex-browser";
    public static final String TOTAL_SECURITY = "360-total-security";

    private static final List<String> UNINSTALL_ROOTS = List.of(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

    private static final List<String> YANDEX_INSTALL_PATHS = List.of(
            "%LOCALAPPDATA%\\Yandex\\YandexBrowser",
            "%PROGRAMFILES%\\Yandex\\YandexBrowser",
            "%PROGRAMFILES(X86)%\\Yandex\\YandexBrowser");

    private static final List<String> YANDEX_PROCESS_MARKERS = List.of(
            "yandexbrowser", "yandex\\browser", "yandex/browser");

    private static final List<String> SECURITY_PROCESS_NAMES = List.of(
            "360tray.exe", "360sd.exe", "360safe.exe", "360rp.exe",
            "zhudongfangyu.exe", "360leakfixer.exe", "qhsafe.exe");

    private static final List<String> SECURITY_INSTALL_PATHS = List.of(
            "%PROGRAMFILES(X86)%\\360\\360Safe",
            "%PROGRAMFILES%\\360\\360Safe");

    private static final List<String> SECURITY_DATA_PATHS = List.of(
            "%PROGRAMDATA%\\360safe",
            "%PROGRAMDATA%\\360",
            "%APPDATA%\\360safe",
            "%LOCALAPPDATA%\\360safe");

    private static final List<String> SECURITY_SERVICES = List.of(
            "360rp", "360sd", "360Safe");

    private static final List<String> YANDEX_DATA_PATHS = List.of(
            "%APPDATA%\\Yandex\\YandexBrowser");

    private ConflictingSoftwareScanner() {}

    public static List<Detection> detectAll() {
        List<Detection> found = new ArrayList<>();
        if (!isWindows()) return found;
        List<Entry> entries = uninstallEntries();
        Detection yandex = detectYandex(entries);
        if (yandex != null) found.add(yandex);
        Detection security = detect360(entries);
        if (security != null) found.add(security);
        return found;
    }

    public static Detection detectYandex() {
        if (!isWindows()) return null;
        return detectYandex(uninstallEntries());
    }

    public static Detection detect360() {
        if (!isWindows()) return null;
        return detect360(uninstallEntries());
    }

    public static boolean forceRemove(String id) {
        if (!isWindows()) return false;
        if (YANDEX_BROWSER.equals(id)) return removeYandex();
        if (TOTAL_SECURITY.equals(id)) return remove360();
        return false;
    }

    private static Detection detectYandex(List<Entry> entries) {
        Entry entry = first(entries, e -> isYandexBrowser(e.displayName()));
        String process = runningProcess(YANDEX_PROCESS_MARKERS, false);
        Path path = installPath(YANDEX_INSTALL_PATHS);
        if (process == null && entry == null && path == null) return null;

        String detail;
        if (process != null) detail = "Запущен браузер " + process + ".";
        else if (entry != null) detail = "Установлена программа: " + entry.displayName() + ".";
        else detail = "Найдена папка " + path + ".";
        return new Detection(YANDEX_BROWSER, "Яндекс Браузер", detail);
    }

    private static Detection detect360(List<Entry> entries) {
        Entry entry = first(entries, e -> is360Security(e.displayName()));
        String process = runningProcess(SECURITY_PROCESS_NAMES, true);
        Path path = installPath(SECURITY_INSTALL_PATHS);
        if (process == null && entry == null && path == null) return null;

        String detail;
        if (process != null) {
            detail = entry != null
                    ? "Запущен процесс " + process + ". Установлена программа: " + entry.displayName() + "."
                    : "Запущен процесс " + process + ".";
        } else if (entry != null) detail = "Установлена программа: " + entry.displayName() + ".";
        else detail = "Найдена папка " + path + ".";
        return new Detection(TOTAL_SECURITY, "360 Total Security", detail);
    }

    private static boolean removeYandex() {
        boolean changed = false;
        changed |= killProcesses(command -> {
            String normalized = command.replace('\\', '/').toLowerCase(Locale.ROOT);
            return YANDEX_PROCESS_MARKERS.stream().anyMatch(normalized::contains);
        });
        changed |= deletePaths(YANDEX_INSTALL_PATHS);
        changed |= deletePaths(YANDEX_DATA_PATHS);
        changed |= deleteShortcuts(name -> {
            boolean yandex = name.contains("yandex") || name.contains("яндекс");
            return yandex && (name.contains("browser") || name.contains("браузер"));
        });
        changed |= deleteRegistryEntries(isYandexBrowserText());
        return changed;
    }

    private static boolean remove360() {
        boolean changed = false;
        changed |= killProcesses(command -> {
            String name = command.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            return SECURITY_PROCESS_NAMES.contains(name);
        });
        for (String service : SECURITY_SERVICES) {
            sc("stop", service);
            sc("delete", service);
        }
        changed |= deletePaths(SECURITY_INSTALL_PATHS);
        changed |= deletePaths(SECURITY_DATA_PATHS);
        changed |= deleteShortcuts(name -> name.contains("360"));
        changed |= deleteRegistryEntries(is360SecurityText());
        return changed;
    }

    private static Predicate<String> isYandexBrowserText() {
        return ConflictingSoftwareScanner::isYandexBrowser;
    }

    private static Predicate<String> is360SecurityText() {
        return ConflictingSoftwareScanner::is360Security;
    }

    static boolean is360Security(String displayName) {
        if (displayName == null) return false;
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (!lower.contains("360")) return false;
        return lower.contains("total security") || lower.contains("security") || lower.contains("safe")
                || lower.contains("安全") || lower.contains("卫士");
    }

    static boolean isYandexBrowser(String displayName) {
        if (displayName == null) return false;
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (!(lower.contains("yandex") || lower.contains("яндекс"))) return false;
        return lower.contains("browser") || lower.contains("браузер")
                || lower.equals("yandex") || lower.equals("яндекс");
    }

    static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        if (command == null) return parts;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else current.append(c);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String runningProcess(List<String> markers, boolean processName) {
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            String command = handle.info().command().orElse("");
            if (command.isBlank()) continue;
            String lower = command.toLowerCase(Locale.ROOT);
            if (processName) {
                String name = lower.replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1);
                if (markers.contains(name)) return command;
            } else {
                String normalized = lower.replace('\\', '/');
                if (markers.stream().anyMatch(normalized::contains)) return command;
            }
        }
        return null;
    }

    private static boolean killProcesses(Predicate<String> matchesCommand) {
        boolean killed = false;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            String command = handle.info().command().orElse("");
            if (command.isBlank() || !matchesCommand.test(command)) continue;
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
            killed = true;
        }
        if (killed) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return killed;
    }

    private static boolean deletePaths(List<String> candidates) {
        boolean deleted = false;
        for (String candidate : candidates) {
            Path path = expand(candidate);
            if (!Files.exists(path)) continue;
            deleted |= deleteTree(path);
        }
        return deleted;
    }

    private static boolean deleteTree(Path path) {
        boolean deleted = false;
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path current : paths) {
                try {
                    current.toFile().setWritable(true);
                    deleted |= Files.deleteIfExists(current);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return deleted;
    }

    private static boolean deleteShortcuts(Predicate<String> matchesName) {
        boolean deleted = false;
        List<Path> directories = new ArrayList<>();
        addIfPresent(directories, "%USERPROFILE%\\Desktop");
        addIfPresent(directories, "%PUBLIC%\\Desktop");
        addIfPresent(directories, "%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs");
        addIfPresent(directories, "%PROGRAMDATA%\\Microsoft\\Windows\\Start Menu\\Programs");
        for (Path directory : directories) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".lnk") || name.endsWith(".url"))) continue;
                    if (!matchesName.test(name)) continue;
                    try {
                        deleted |= Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return deleted;
    }

    private static void addIfPresent(List<Path> directories, String value) {
        Path path = expand(value);
        if (Files.isDirectory(path)) directories.add(path);
    }

    private static boolean deleteRegistryEntries(Predicate<String> matchesName) {
        boolean deleted = false;
        for (String root : UNINSTALL_ROOTS) {
            for (Entry entry : query(root)) {
                if (!matchesName.test(entry.displayName())) continue;
                deleted |= regDelete(entry.key());
            }
        }
        return deleted;
    }

    private static boolean regDelete(String key) {
        try {
            Process process = new ProcessBuilder("reg", "delete", key, "/f")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (IOException | RuntimeException ignored) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sc(String action, String service) {
        try {
            Process process = new ProcessBuilder("sc", action, service)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (IOException | RuntimeException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path installPath(List<String> candidates) {
        for (String candidate : candidates) {
            Path path = expand(candidate);
            if (Files.isDirectory(path)) return path;
        }
        return null;
    }

    private static Path expand(String value) {
        String result = value;
        for (String key : List.of("LOCALAPPDATA", "APPDATA", "PROGRAMDATA", "USERPROFILE", "PUBLIC",
                "PROGRAMFILES(X86)", "PROGRAMFILES")) {
            String env = System.getenv(key);
            if (env != null) result = result.replace("%" + key + "%", env);
        }
        return Path.of(result);
    }

    private static Entry first(List<Entry> entries, Predicate<Entry> predicate) {
        for (Entry entry : entries) if (predicate.test(entry)) return entry;
        return null;
    }

    private static List<Entry> uninstallEntries() {
        List<Entry> entries = new ArrayList<>();
        for (String root : UNINSTALL_ROOTS) entries.addAll(query(root));
        return entries;
    }

    private static List<Entry> query(String root) {
        List<Entry> entries = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", root, "/s").redirectErrorStream(true).start();
            String key = null;
            String displayName = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("HKEY_")) {
                        if (displayName != null) entries.add(new Entry(key, displayName));
                        key = trimmed;
                        displayName = null;
                    } else if (trimmed.startsWith("DisplayName")) {
                        displayName = value(trimmed);
                    }
                }
            }
            if (displayName != null) entries.add(new Entry(key, displayName));
        } catch (IOException | RuntimeException ignored) {
        } finally {
            if (process != null) {
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                process.destroy();
            }
        }
        return entries;
    }

    private static String value(String line) {
        int reg = line.indexOf("REG_");
        if (reg < 0) return null;
        int space = line.indexOf(' ', reg);
        if (space < 0) return null;
        String value = line.substring(space + 1).trim();
        return value.isEmpty() ? null : value;
    }
}
