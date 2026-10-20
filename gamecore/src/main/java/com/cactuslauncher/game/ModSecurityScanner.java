package com.cactuslauncher.game;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Local heuristic checks for installed mod archives. This is not signature-based antivirus. */
public final class ModSecurityScanner {
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_CLASS_BYTES = 128L * 1024 * 1024;
    private static final int MAX_SINGLE_CLASS_BYTES = 16 * 1024 * 1024;

    public record Report(String level, List<String> findings, int scannedClasses) {
        public Report { findings = List.copyOf(findings); }
    }

    public Report scan(Path jar) throws IOException {
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Файл мода отсутствует или является ссылкой.");
        if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Проверять можно только JAR-файлы модов.");

        List<String> findings = new ArrayList<>();
        int classCount = 0;
        int shortClassNames = 0;
        long totalClassBytes = 0;
        boolean launchesProcesses = false;
        boolean persistence = false;
        boolean suspiciousCommands = false;
        boolean usesCrypto = false;
        boolean dynamicLoading = false;
        boolean networkAccess = false;

        try (ZipFile archive = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Проверка мода отменена.");
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ENTRIES) throw new IOException("В архиве слишком много записей.");
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (++classCount > MAX_ENTRIES) throw new IOException("В архиве слишком много классов.");
                String className = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                int innerClass = className.indexOf('$');
                if (innerClass >= 0) className = className.substring(0, innerClass);
                if (className.endsWith(".class")) className = className.substring(0, className.length() - 6);
                if (className.matches("[a-zA-Z]{1,2}")) shortClassNames++;

                byte[] bytes;
                try (InputStream input = archive.getInputStream(entry)) {
                    bytes = readBounded(input, MAX_SINGLE_CLASS_BYTES);
                }
                totalClassBytes += bytes.length;
                if (totalClassBytes > MAX_CLASS_BYTES) throw new IOException("Объём байткода слишком велик для безопасной проверки.");
                String constants = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                launchesProcesses |= constants.contains("java/lang/processbuilder") || constants.contains("java/lang/runtime") && constants.contains("exec");
                suspiciousCommands |= constants.contains("powershell") || constants.contains("cmd.exe")
                        || constants.contains("bash -c") || constants.contains("/bin/sh -c");
                persistence |= constants.contains("authorized_keys") || constants.contains("launchagents")
                        || constants.contains("currentversion\\run") || constants.contains("start menu\\programs\\startup");
                usesCrypto |= constants.contains("javax/crypto/cipher") || constants.contains("javax/crypto/spec/secretkeyspec");
                dynamicLoading |= constants.contains("java/lang/invoke/methodhandles")
                        || constants.contains("java/lang/classloader") && constants.contains("defineclass");
                networkAccess |= constants.contains("java/net/socket") || constants.contains("java/net/http")
                        || constants.contains("java/net/urlconnection");
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("JAR повреждён или имеет неверный формат.", e);
        }

        if (classCount == 0) findings.add("В JAR не найден байткод Java.");
        if (classCount >= 8 && shortClassNames >= Math.ceil(classCount * 0.55))
            findings.add("Много коротких имён классов: возможна обфускация.");
        if (persistence) findings.add("Найдены строки, похожие на настройку автозапуска или изменение SSH-ключей.");
        if (launchesProcesses && suspiciousCommands) findings.add("Найдены запуск системных процессов и команды оболочки.");
        else if (launchesProcesses) findings.add("Мод содержит вызовы запуска системных процессов.");
        if (usesCrypto && dynamicLoading && shortClassNames >= Math.max(5, classCount / 3))
            findings.add("Сочетание обфускации, криптографии и динамической загрузки кода.");
        else if (usesCrypto && dynamicLoading) findings.add("Криптография используется вместе с динамической загрузкой классов.");
        if (networkAccess && launchesProcesses) findings.add("Обнаружены сетевые вызовы в сочетании с запуском процессов.");

        String level = findings.stream().anyMatch(s -> s.contains("запуск") || s.contains("автозапуска") || s.contains("динамической загрузки"))
                ? "warning" : findings.isEmpty() ? "clear" : "notice";
        return new Report(level, findings, classCount);
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Проверка мода отменена.");
            if (output.size() + count > maxBytes) throw new IOException("Отдельный class-файл превышает лимит проверки.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
