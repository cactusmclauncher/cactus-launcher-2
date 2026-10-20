package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

public final class CrashDiagnosis {
    public record Result(String title, String detail, String evidence, String suspectMod, String confidence, boolean recoverable) {}

    private static final Pattern FRAME = Pattern.compile("\\bat ([\\w$]+(?:\\.[\\w$]+)+)\\(");
    public static final String UNKNOWN_TITLE = "Причина не определяется однозначно";
    public static final String NO_LOGS_TITLE = "Запусков для разбора пока нет";
    private static final List<String> LOGS = List.of("logs/latest.log", "logs/cactus-launch.log");

    public Result analyze(Path buildRoot, String version, String loader) throws IOException {
        String text = newestLog(buildRoot);
        if (text.isBlank()) return new Result(NO_LOGS_TITLE, "Когда игра вылетит, здесь появится причина и кусок лога.", "Нет ни crash-report, ни latest.log, ни лога лаунчера.", null, "", false);

        String lower = text.toLowerCase(Locale.ROOT);
        String title;
        String detail;
        boolean recoverable = false;
        if (lower.contains("outofmemoryerror") || lower.contains("java heap space")) {
            title = "Не хватило памяти Java";
            detail = "Игре не хватило выделенной памяти. Добавьте память в настройках сборки или закройте тяжёлые программы.";
        } else if (lower.contains("no supported graphics backend") || lower.contains("couldn't find matching glx visual")
                || lower.contains("vulkan is not supported") || lower.contains("glfw error 65542") || lower.contains("glfw error 65543")
                || lower.contains("pixel format not accelerated")) {
            title = "Видеокарта не поддерживает запуск игры";
            detail = "Minecraft не смог создать окно ни через OpenGL, ни через Vulkan. Обновите драйверы видеокарты: инструкция на aka.ms/mcdriver."
                    + " На ноутбуке с двумя видеокартами выберите для Java дискретную.";
        } else if (lower.contains("unsupportedclassversionerror")) {
            title = "Мод собран для другой Java";
            detail = "В логе UnsupportedClassVersionError: какой-то мод или библиотека собраны под другую версию Java.";
            recoverable = true;
        } else if (lower.contains("modresolutionexception") || lower.contains("incompatible mod set") || lower.contains("depends on")) {
            title = "Несовместимость загрузчика или модов";
            detail = "Загрузчик нашёл конфликт зависимостей или неподходящую версию. Сверьте версии Minecraft, " + loader + " и модов.";
            recoverable = true;
        } else if (lower.contains("mixin apply failed") || lower.contains("mixinapplyerror")) {
            title = "Ошибка применения Mixin";
            detail = "Мод не смог изменить класс игры или другого мода. Посмотрите на класс из стека: он подскажет, кто конфликтует.";
            recoverable = true;
        } else if (lower.contains("nosuchmethoderror") || lower.contains("noclassdeffounderror") || lower.contains("classnotfoundexception")) {
            title = "Не найдена совместимая версия класса или метода";
            detail = "Обычно это несовместимые версии модов, Minecraft или загрузчика. Проверьте класс из стека и его JAR.";
            recoverable = true;
        } else if (lower.contains("unsatisfiedlinkerror") || lower.contains("failed to locate library")) {
            title = "Не загрузилась системная библиотека";
            detail = "Ошибка в нативной библиотеке или архитектуре Java, моды тут, скорее всего, ни при чём.";
        } else if (lower.contains("duplicate mods") || lower.contains("duplicate mod")) {
            title = "Найдены дубли модов";
            detail = "Один и тот же мод установлен дважды. Удалите или отключите одну из копий.";
            recoverable = true;
        } else {
            title = UNKNOWN_TITLE;
            detail = "Лог есть, но знакомых ошибок в нём не нашлось.";
        }

        detail += " Профиль: Minecraft " + version + ", " + loader + ".";
        ClassMatch match = findModClass(buildRoot.resolve("mods"), text);
        String suspect = match == null ? null : match.fileName();
        String evidence = firstEvidence(text, lower, match);
        String confidence = suspect == null ? "" : "Класс из стека нашёлся в JAR этого мода. Скорее всего, дело в нём, но не наверняка.";
        return new Result(title, detail, evidence, suspect, confidence, recoverable && suspect != null);
    }

    private static String newestLog(Path root) throws IOException {
        var candidates = new ArrayList<Path>();
        for (String relative : LOGS) {
            Path file = root.resolve(relative);
            if (!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) candidates.add(file);
        }
        Path reports = root.resolve("crash-reports");
        if (!Files.isSymbolicLink(reports) && Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(reports)) {
                candidates.addAll(files.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().endsWith(".txt")).toList());
            }
        }
        Path newest = candidates.stream().max(Comparator.comparingLong(CrashDiagnosis::modified)).orElse(null);
        return newest == null ? "" : readBounded(newest);
    }

    private static String readBounded(Path file) throws IOException {
        long size = Files.size(file);
        try (var stream = Files.newInputStream(file)) {
            if (size > 2_000_000) stream.skip(size - 2_000_000);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException ignored) { return 0; }
    }

    private static List<String> candidateClasses(String text) {
        var result = new LinkedHashSet<String>();
        Matcher matcher = FRAME.matcher(text);
        while (matcher.find() && result.size() < 120) {
            String name = matcher.group(1);
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("net.minecraft.") || name.startsWith("net.fabricmc.") || name.startsWith("net.minecraftforge.")
                    || name.startsWith("cpw.mods.") || name.startsWith("org.spongepowered.asm.") || name.startsWith("org.lwjgl.")
                    || name.startsWith("com.mojang.")) continue;
            result.add(name.replace('.', '/') + ".class");
        }
        return List.copyOf(result);
    }

    private record ClassMatch(String fileName, String className) {}

    private static ClassMatch findModClass(Path mods, String text) throws IOException {
        List<String> classes = candidateClasses(text);
        if (classes.isEmpty() || Files.isSymbolicLink(mods) || !Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var jars = Files.list(mods)) {
            var jarFiles = jars.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList();
            ClassMatch best = null;
            int bestRank = Integer.MAX_VALUE;
            for (Path jar : jarFiles) {
                try (var zip = new ZipFile(jar.toFile())) {
                    for (int rank = 0; rank < Math.min(bestRank, classes.size()); rank++) {
                        String classEntry = classes.get(rank);
                        if (zip.getEntry(classEntry) != null) {
                            best = new ClassMatch(jar.getFileName().toString(), classEntry);
                            bestRank = rank;
                            break;
                        }
                    }
                    if (bestRank == 0) return best;
                } catch (IOException ignored) { }
            }
            return best;
        }
    }

    private static String firstEvidence(String text, String lower, ClassMatch match) {
        for (String token : List.of("No supported graphics backend", "Couldn't find matching GLX visual", "OutOfMemoryError", "UnsupportedClassVersionError", "ModResolutionException", "Mixin apply failed", "MixinApplyError", "NoSuchMethodError", "NoClassDefFoundError", "ClassNotFoundException", "UnsatisfiedLinkError", "Failed to locate library", "Duplicate mods")) {
            int index = lower.indexOf(token.toLowerCase(Locale.ROOT));
            if (index >= 0) return text.substring(Math.max(0, index - 80), Math.min(text.length(), index + token.length() + 180)).replace('\n', ' ').trim();
        }
        if (match != null) return "Класс " + match.className().replace('/', '.').replace(".class", "") + " найден в " + match.fileName() + ".";
        return text.lines().filter(line -> !line.isBlank()).reduce((a, b) -> b).orElse("Лог найден, маркер ошибки не распознан.").trim();
    }
}
