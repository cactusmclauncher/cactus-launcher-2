package com.cactuslauncher.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects 360 Total Security on Windows and, only after explicit user consent, starts its
 * official uninstaller. It never deletes files or kills processes itself.
 */
public final class ConflictingSoftwareScanner {
    public record Detection(String name, String detail, boolean uninstallable) {}
    private record Entry(String displayName, String uninstallCommand) {}

    private static final String NAME = "360 Total Security";

    private static final List<String> PROCESS_NAMES = List.of(
            "360tray.exe", "360sd.exe", "360safe.exe", "360rp.exe",
            "zhudongfangyu.exe", "360leakfixer.exe", "qhsafe.exe");

    private static final List<String> UNINSTALL_ROOTS = List.of(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

    private static final List<String> INSTALL_PATHS = List.of(
            "C:\\Program Files (x86)\\360\\360Safe",
            "C:\\Program Files\\360\\360Safe");

    private ConflictingSoftwareScanner() {}

    public static Detection detect() {
        if (!isWindows()) return null;

        String process = runningProcess();
        Entry entry = uninstallEntry();
        if (process != null) {
            String detail = entry != null
                    ? "Запущен процесс " + process + ". Установлена программа: " + entry.displayName() + "."
                    : "Запущен процесс " + process + ".";
            return new Detection(NAME, detail, entry != null && entry.uninstallCommand() != null);
        }
        if (entry != null)
            return new Detection(NAME, "Установлена программа: " + entry.displayName() + ".", entry.uninstallCommand() != null);

        Path path = installPath();
        if (path != null) return new Detection(NAME, "Найдена папка " + path + ".", false);

        return null;
    }

    public static boolean uninstall() throws IOException {
        if (!isWindows()) return false;
        Entry entry = uninstallEntry();
        if (entry == null || entry.uninstallCommand() == null) return false;
        return start(entry.uninstallCommand());
    }

    static boolean start(String uninstallCommand) throws IOException {
        List<String> command = splitCommand(uninstallCommand);
        if (command.isEmpty()) return false;
        new ProcessBuilder(command).start();
        return true;
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

    static boolean is360Security(String displayName) {
        if (displayName == null) return false;
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (!lower.contains("360")) return false;
        return lower.contains("total security") || lower.contains("security") || lower.contains("safe")
                || lower.contains("安全") || lower.contains("卫士");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String runningProcess() {
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            String command = handle.info().command().orElse("");
            if (command.isBlank()) continue;
            String name = command.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (PROCESS_NAMES.contains(name)) return command;
        }
        return null;
    }

    private static Path installPath() {
        for (String candidate : INSTALL_PATHS) {
            try {
                Path path = Path.of(candidate);
                if (Files.isDirectory(path)) return path;
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Entry uninstallEntry() {
        for (String root : UNINSTALL_ROOTS) {
            for (Entry entry : query(root)) {
                if (is360Security(entry.displayName())) return entry;
            }
        }
        return null;
    }

    private static List<Entry> query(String root) {
        List<Entry> entries = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", root, "/s").redirectErrorStream(true).start();
            String displayName = null;
            String uninstall = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("HKEY_")) {
                        if (displayName != null) entries.add(new Entry(displayName, uninstall));
                        displayName = null;
                        uninstall = null;
                    } else if (trimmed.startsWith("DisplayName")) {
                        displayName = value(trimmed);
                    } else if (trimmed.startsWith("UninstallString")) {
                        uninstall = value(trimmed);
                    }
                }
            }
            if (displayName != null) entries.add(new Entry(displayName, uninstall));
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