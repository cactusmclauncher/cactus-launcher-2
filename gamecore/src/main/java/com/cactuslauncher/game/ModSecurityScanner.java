package com.cactuslauncher.game;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local heuristic checks for installed mod archives. This is not signature-based antivirus.
 *
 * <p>Signals come from the constant pool of each class file: exact class and method references
 * (for example {@code java/lang/Runtime.exec}) and string literals. Nested JARs (Jar-in-Jar under
 * {@code META-INF/jars} or anywhere else in the archive) are scanned recursively within limits.
 */
public final class ModSecurityScanner {
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_CLASS_BYTES = 128L * 1024 * 1024;
    private static final int MAX_SINGLE_CLASS_BYTES = 16 * 1024 * 1024;
    static final int MAX_NESTED_DEPTH = 3;
    private static final long MAX_NESTED_JAR_BYTES = 64L * 1024 * 1024;
    private static final long MAX_NESTED_TOTAL_BYTES = 256L * 1024 * 1024;

    /** Method references that start external processes. */
    private static final Set<String> PROCESS_METHODS = Set.of(
            "java/lang/Runtime.exec", "java/lang/ProcessBuilder.start");
    /** Method names that turn raw bytes into classes, whatever class they are called on. */
    private static final Set<String> DEFINE_CLASS_METHODS = Set.of("defineClass", "defineHiddenClass", "defineAnonymousClass");
    private static final Set<String> CLASS_LOADER_CLASSES = Set.of(
            "java/net/URLClassLoader", "java/security/SecureClassLoader", "jdk/internal/loader/BuiltinClassLoader");
    private static final Set<String> CRYPTO_CLASSES = Set.of("javax/crypto/Cipher", "javax/crypto/spec/SecretKeySpec");
    private static final Set<String> NETWORK_CLASSES = Set.of(
            "java/net/Socket", "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/http/HttpClient",
            "java/nio/channels/SocketChannel", "javax/net/ssl/HttpsURLConnection");
    private static final Set<String> NETWORK_METHODS = Set.of("java/net/URL.openConnection", "java/net/URL.openStream");
    /** Reflective access by name: a string literal naming the class plus Class.forName/Method.invoke. */
    private static final Set<String> REFLECTIVE_PROCESS_NAMES = Set.of("java.lang.Runtime", "java.lang.ProcessBuilder");

    public record Report(String level, List<String> findings, int scannedClasses) {
        public Report { findings = List.copyOf(findings); }
    }

    private static final class State {
        int entries;
        int classCount;
        int shortClassNames;
        int unreadableClasses;
        long totalClassBytes;
        long nestedBytes;
        boolean launchesProcesses;
        boolean persistence;
        boolean suspiciousCommands;
        boolean usesCrypto;
        boolean dynamicLoading;
        boolean networkAccess;
        boolean nestedTooDeep;
        final Set<String> riskyNestedJars = new LinkedHashSet<>();
    }

    public Report scan(Path jar) throws IOException {
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Файл мода отсутствует или является ссылкой.");
        if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Проверять можно только JAR-файлы модов.");

        State state = new State();
        scanArchive(jar, "", 0, state);

        List<String> findings = new ArrayList<>();
        int classCount = state.classCount;
        if (classCount == 0) findings.add("В JAR не найден байткод Java.");
        if (classCount >= 8 && state.shortClassNames >= Math.ceil(classCount * 0.55))
            findings.add("Много коротких имён классов: возможна обфускация.");
        if (state.persistence) findings.add("Найдены строки, похожие на настройку автозапуска или изменение SSH-ключей.");
        if (state.launchesProcesses && state.suspiciousCommands) findings.add("Найдены запуск системных процессов и команды оболочки.");
        else if (state.launchesProcesses) findings.add("Мод содержит вызовы запуска системных процессов.");
        if (state.usesCrypto && state.dynamicLoading && state.shortClassNames >= Math.max(5, classCount / 3))
            findings.add("Сочетание обфускации, криптографии и динамической загрузки кода.");
        else if (state.usesCrypto && state.dynamicLoading) findings.add("Криптография используется вместе с динамической загрузкой классов.");
        if (state.networkAccess && state.launchesProcesses) findings.add("Обнаружены сетевые вызовы в сочетании с запуском процессов.");
        if (!state.riskyNestedJars.isEmpty())
            findings.add("Признаки запуска процессов или загрузки кода найдены во вложенном JAR: "
                    + String.join(", ", state.riskyNestedJars) + ".");
        if (state.nestedTooDeep) findings.add("Вложенные JAR глубже " + MAX_NESTED_DEPTH + " уровней не проверены.");
        if (state.unreadableClasses > 0) findings.add("Не удалось разобрать class-файлов: " + state.unreadableClasses + ".");

        String level = findings.stream().anyMatch(s -> s.contains("запуск") || s.contains("автозапуска") || s.contains("динамической загрузки"))
                ? "warning" : findings.isEmpty() ? "clear" : "notice";
        return new Report(level, findings, classCount);
    }

    private void scanArchive(Path file, String label, int depth, State state) throws IOException {
        try (ZipFile archive = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Проверка мода отменена.");
                ZipEntry entry = entries.nextElement();
                if (++state.entries > MAX_ENTRIES) throw new IOException("В архиве слишком много записей.");
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar")) {
                    scanNested(archive, entry, label.isEmpty() ? name : label + " → " + name, depth, state);
                    continue;
                }
                if (!lower.endsWith(".class")) continue;
                if (++state.classCount > MAX_ENTRIES) throw new IOException("В архиве слишком много классов.");
                String className = name.substring(name.lastIndexOf('/') + 1);
                int innerClass = className.indexOf('$');
                if (innerClass >= 0) className = className.substring(0, innerClass);
                if (className.endsWith(".class")) className = className.substring(0, className.length() - 6);
                if (className.matches("[a-zA-Z]{1,2}")) state.shortClassNames++;

                byte[] bytes;
                try (InputStream input = archive.getInputStream(entry)) {
                    bytes = readBounded(input, MAX_SINGLE_CLASS_BYTES);
                }
                state.totalClassBytes += bytes.length;
                if (state.totalClassBytes > MAX_CLASS_BYTES) throw new IOException("Объём байткода слишком велик для безопасной проверки.");
                ConstantPool pool;
                try { pool = ConstantPool.parse(bytes); }
                catch (IOException | RuntimeException malformed) { state.unreadableClasses++; continue; }
                if (analyze(pool, state) && depth > 0) state.riskyNestedJars.add(label);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("JAR повреждён или имеет неверный формат" + (label.isEmpty() ? "" : ": " + label) + ".", e);
        }
    }

    private void scanNested(ZipFile archive, ZipEntry entry, String label, int depth, State state) throws IOException {
        if (depth + 1 > MAX_NESTED_DEPTH) { state.nestedTooDeep = true; return; }
        Path temp = Files.createTempFile("cactus-nested-", ".jar");
        try {
            long size;
            try (InputStream input = archive.getInputStream(entry); var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                size = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Проверка мода отменена.");
                    size += count;
                    state.nestedBytes += count;
                    if (size > MAX_NESTED_JAR_BYTES) throw new IOException("Вложенный JAR слишком большой: " + label + ".");
                    if (state.nestedBytes > MAX_NESTED_TOTAL_BYTES) throw new IOException("Вложенные JAR слишком велики для безопасной проверки.");
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) return;
            scanArchive(temp, label, depth + 1, state);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Adds the class's signals to the state; returns true if this class itself launches processes, defines classes or persists. */
    private static boolean analyze(ConstantPool pool, State state) {
        boolean reflection = false;
        boolean reflectiveProcessName = false;
        boolean launches = false;
        boolean defines = false;
        boolean persists = false;
        for (String reference : pool.methodReferences()) {
            int dot = reference.indexOf('.');
            String owner = reference.substring(0, dot);
            String method = reference.substring(dot + 1);
            if (PROCESS_METHODS.contains(reference)) launches = true;
            if (DEFINE_CLASS_METHODS.contains(method)) defines = true;
            if (CLASS_LOADER_CLASSES.contains(owner) && method.equals("<init>")) defines = true;
            if (CRYPTO_CLASSES.contains(owner)) state.usesCrypto = true;
            if (NETWORK_CLASSES.contains(owner) || NETWORK_METHODS.contains(reference)) state.networkAccess = true;
            if (reference.equals("java/lang/Class.forName") || reference.equals("java/lang/reflect/Method.invoke")) reflection = true;
        }
        for (String type : pool.classReferences()) {
            if (CRYPTO_CLASSES.contains(type)) state.usesCrypto = true;
            if (NETWORK_CLASSES.contains(type)) state.networkAccess = true;
        }
        for (String literal : pool.strings()) {
            if (REFLECTIVE_PROCESS_NAMES.contains(literal)) reflectiveProcessName = true;
            String value = literal.toLowerCase(Locale.ROOT);
            state.suspiciousCommands |= value.contains("powershell") || value.contains("cmd.exe")
                    || value.contains("bash -c") || value.contains("/bin/sh") || value.contains("/bin/bash");
            persists |= value.contains("authorized_keys") || value.contains("launchagents")
                    || value.contains("currentversion\\run") || value.contains("start menu\\programs\\startup")
                    || value.contains("crontab") || value.contains("systemd/user");
        }
        if (reflection && reflectiveProcessName) launches = true;
        state.launchesProcesses |= launches;
        state.dynamicLoading |= defines;
        state.persistence |= persists;
        return launches || defines || persists;
    }

    /** Minimal class-file constant pool reader: class names, Owner.method references and string literals. */
    record ConstantPool(List<String> classReferences, List<String> methodReferences, List<String> strings) {
        static ConstantPool parse(byte[] bytes) throws IOException {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Не class-файл.");
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] tags = new int[count];
            int[] first = new int[count];
            int[] second = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                tags[index] = tag;
                switch (tag) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> { input.readLong(); index++; }
                    case 7, 8, 16, 19, 20 -> first[index] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> { first[index] = input.readUnsignedShort(); second[index] = input.readUnsignedShort(); }
                    case 15 -> { input.readUnsignedByte(); first[index] = input.readUnsignedShort(); }
                    default -> throw new IOException("Неизвестный тег constant pool: " + tag);
                }
            }
            List<String> classes = new ArrayList<>();
            List<String> methods = new ArrayList<>();
            List<String> strings = new ArrayList<>();
            for (int index = 1; index < count; index++) {
                switch (tags[index]) {
                    case 7 -> classes.add(text(utf8, first[index]));
                    case 8 -> strings.add(text(utf8, first[index]));
                    case 10, 11 -> {
                        int owner = first[index];
                        int nameAndType = second[index];
                        if (owner <= 0 || owner >= count || tags[owner] != 7 || nameAndType <= 0 || nameAndType >= count || tags[nameAndType] != 12)
                            throw new IOException("Повреждённая ссылка на метод.");
                        methods.add(text(utf8, first[owner]) + "." + text(utf8, first[nameAndType]));
                    }
                    default -> { }
                }
            }
            return new ConstantPool(classes, methods, strings);
        }

        private static String text(String[] utf8, int index) throws IOException {
            if (index <= 0 || index >= utf8.length || utf8[index] == null) throw new IOException("Повреждённая строка constant pool.");
            return utf8[index];
        }
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
