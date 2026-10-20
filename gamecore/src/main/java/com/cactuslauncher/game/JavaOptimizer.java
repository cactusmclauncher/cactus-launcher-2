package com.cactuslauncher.game;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class JavaOptimizer {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;
    private static final int MAX_MOD_FILES = 10_000;
    private static volatile String cachedProcessorName;
    private static volatile String cachedGraphicsName;

    public record Machine(long totalMemoryBytes, long availableMemoryBytes, int processors,
                          String processorName, String graphicsName, String javaVersion) {}

    public record Plan(int heapGb, String collector, List<String> arguments,
                       int modJarCount, long modJarBytes, Machine machine) {
        public Plan { arguments = List.copyOf(arguments); }
    }

    public record CompatibleArguments(List<String> arguments, List<String> rejected) {
        public CompatibleArguments {
            arguments = List.copyOf(arguments);
            rejected = List.copyOf(rejected);
        }
    }

    public record DaemonMemoryPlan(int initialHeapMb, int maximumHeapMb, int maximumMetaspaceMb,
                                   int codeCacheMb, List<String> jvmArguments) {
        public DaemonMemoryPlan { jvmArguments = List.copyOf(jvmArguments); }
    }

    public record FlagDefinition(String argumentTemplate, String category, int minimumJava,
                                 int maximumJava, String collector, boolean experimental,
                                 boolean diagnostic, boolean automatic) {}

    private static final List<FlagDefinition> FLAG_CATALOG = List.of(
            flag("-Xms{value}", "memory", 8, 0, false, false, true),
            flag("-Xmx{value}", "memory", 8, 0, false, false, true),
            flag("-Xss{value}", "memory", 8, 0, false, false, false),
            flag("-XX:+UseG1GC", "collector", 8, 0, false, false, true),
            flag("-XX:+UseSerialGC", "collector", 8, 0, false, false, false),
            flag("-XX:+ExitOnOutOfMemoryError", "general", 8, 0, false, false, true),
            flag("-XX:MaxGCPauseMillis={value}", "G1", 8, 0, false, false, true),
            flag("-XX:+ParallelRefProcEnabled", "G1", 8, 0, false, false, true),
            flag("-XX:+DisableExplicitGC", "general", 8, 0, false, false, true),
            flag("-XX:+UseStringDeduplication", "G1", 8, 0, false, false, true),
            flag("-XX:G1ReservePercent={value}", "G1", 8, 0, false, false, true),
            flag("-XX:InitiatingHeapOccupancyPercent={value}", "G1", 8, 0, false, false, false),
            flag("-XX:G1NewSizePercent={value}", "G1", 8, 0, true, false, false),
            flag("-XX:G1MaxNewSizePercent={value}", "G1", 8, 0, true, false, false),
            flag("-XX:G1HeapRegionSize={value}", "G1", 8, 0, false, false, false),
            flag("-XX:MaxTenuringThreshold={value}", "G1/Parallel", 8, 0, false, false, false),
            flag("-XX:G1MixedGCCountTarget={value}", "G1", 8, 0, false, false, false),
            flag("-XX:G1MixedGCLiveThresholdPercent={value}", "G1", 8, 0, false, false, false),
            flag("-XX:G1RSetUpdatingPauseTimePercent={value}", "G1", 8, 0, false, false, false),
            flag("-XX:G1HeapWastePercent={value}", "G1", 8, 0, false, false, false),
            flag("-XX:+UseParallelGC", "collector", 8, 0, false, false, false),
            flag("-XX:+UseAdaptiveSizePolicy", "Parallel", 8, 0, false, false, false),
            flag("-XX:+UseZGC", "collector", 15, 0, false, false, false),
            flag("-XX:+ZGenerational", "ZGC", 21, 23, true, false, false),
            flag("-XX:+ShenandoahUncommit", "Shenandoah", 12, 0, false, false, false),
            flag("-XX:ShenandoahUncommitDelay={value}", "Shenandoah", 12, 0, false, false, false),
            flag("-XX:+UseShenandoahGC", "collector", 12, 0, false, false, false),
            flag("-XX:+UseConcMarkSweepGC", "collector", 8, 13, false, false, false),
            flag("-XX:+UseParNewGC", "CMS", 8, 13, false, false, false),
            flag("-XX:CMSInitiatingOccupancyFraction={value}", "CMS", 8, 13, false, false, false),
            flag("-XX:+UseCMSInitiatingOccupancyOnly", "CMS", 8, 13, false, false, false),
            flag("-XX:+CMSParallelRemarkEnabled", "CMS", 8, 13, false, false, false),
            flag("-XX:+CMSScavengeBeforeRemark", "CMS", 8, 13, false, false, false),
            flag("-XX:+UnlockExperimentalVMOptions", "unlock", 8, 0, false, false, false),
            flag("-XX:+UnlockDiagnosticVMOptions", "unlock", 8, 0, false, false, false),
            flag("-XX:+AlwaysPreTouch", "memory", 8, 0, false, false, false),
            flag("-XX:+UseLargePages", "platform", 8, 0, false, false, false),
            flag("-XX:+UseNUMA", "platform", 8, 0, false, false, false),
            flag("-XX:+PerfDisableSharedMem", "diagnostics", 8, 0, false, false, false),
            flag("-XX:+TieredCompilation", "compiler", 8, 0, false, false, false),
            flag("-XX:-TieredCompilation", "compiler", 8, 0, false, false, false),
            flag("-XX:TieredStopAtLevel={value}", "compiler", 8, 0, false, false, false),
            flag("-XX:CICompilerCount={value}", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseDynamicNumberOfGCThreads", "GC threads", 8, 0, false, false, false),
            flag("-XX:-UseDynamicNumberOfGCThreads", "GC threads", 8, 0, false, false, false),
            flag("-XX:ActiveProcessorCount={value}", "CPU", 10, 0, false, false, false),
            flag("-XX:+UseCompressedOops", "memory", 8, 0, false, false, false),
            flag("-XX:-UseCompressedOops", "memory", 8, 0, false, false, false),
            flag("-XX:+UseCompressedClassPointers", "memory", 8, 0, false, false, false),
            flag("-XX:-UseCompressedClassPointers", "memory", 8, 0, false, false, false),
            flag("-XX:+OmitStackTraceInFastThrow", "runtime", 8, 0, false, false, false),
            flag("-XX:+UseFastUnorderedTimeStamps", "runtime", 8, 0, false, false, false),
            flag("-XX:+UseFPUForSpilling", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseBiasedLocking", "runtime", 8, 14, false, false, false),
            flag("-XX:-UseBiasedLocking", "runtime", 8, 14, false, false, false),
            flag("-XX:+DoEscapeAnalysis", "compiler", 8, 0, false, false, false),
            flag("-XX:-DoEscapeAnalysis", "compiler", 8, 0, false, false, false),
            flag("-XX:+EliminateLocks", "compiler", 8, 0, false, false, false),
            flag("-XX:-EliminateLocks", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseLoopPredicate", "compiler", 8, 0, false, false, false),
            flag("-XX:-UseLoopPredicate", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseCodeCacheFlushing", "compiler", 8, 0, false, false, false),
            flag("-XX:+SegmentedCodeCache", "compiler", 8, 0, false, false, false),
            flag("-XX:ReservedCodeCacheSize={value}", "memory", 8, 0, false, false, false),
            flag("-XX:InitialCodeCacheSize={value}", "memory", 8, 0, false, false, false),
            flag("-XX:MaxInlineSize={value}", "compiler", 8, 0, false, false, false),
            flag("-XX:FreqInlineSize={value}", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseFastEmptyMethods", "compiler", 8, 0, false, false, false),
            flag("-XX:+UseFastAccessorMethods", "compiler", 8, 0, false, false, false),
            flag("-XX:MaxRAMPercentage={value}", "memory", 10, 0, false, false, false)
    );

    private static FlagDefinition flag(String argument, String category, int minimumJava,
                                       int maximumJava, boolean experimental, boolean diagnostic,
                                       boolean automatic) {
        String collector = switch (category) {
            case "collector" -> argument.substring("-XX:+Use".length());
            case "G1" -> "G1GC";
            case "Parallel" -> "ParallelGC";
            case "ZGC" -> "ZGC";
            case "Shenandoah" -> "ShenandoahGC";
            case "CMS" -> "CMS";
            default -> "";
        };
        return new FlagDefinition(argument, category, minimumJava, maximumJava, collector,
                experimental, diagnostic, automatic);
    }

    public static List<FlagDefinition> flagCatalog() {
        return FLAG_CATALOG;
    }

    public static boolean isCatalogArgument(String argument) {
        for (FlagDefinition definition : FLAG_CATALOG) {
            String template = definition.argumentTemplate();
            int placeholder = template.indexOf("{value}");
            if (placeholder < 0 ? argument.equals(template) : argument.startsWith(template.substring(0, placeholder)))
                return true;
        }
        return false;
    }

    public Machine inspect() {
        long total = 0;
        long available = 0;
        try {
            var bean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            if (bean != null) {
                total = Math.max(0, bean.getTotalMemorySize());
                available = Math.max(0, bean.getFreeMemorySize());
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (total == 0 || available == 0) {
            long[] fallback = systemMemory(os);
            if (total == 0) total = fallback[0];
            if (available == 0) available = fallback[1];
        }
        if (total > 0 && available > total) available = total;
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        String cpu = cachedProcessorName;
        if (cpu == null) synchronized (JavaOptimizer.class) {
            cpu = cachedProcessorName;
            if (cpu == null) cachedProcessorName = cpu = processorName(os);
        }
        String gpu = cachedGraphicsName;
        if (gpu == null) synchronized (JavaOptimizer.class) {
            gpu = cachedGraphicsName;
            if (gpu == null) cachedGraphicsName = gpu = graphicsName(os);
        }
        return new Machine(total, available, processors, cpu, gpu,
                System.getProperty("java.version", "unknown"));
    }

    public Plan plan(Path modsDirectory, int javaMajor, int requestedHeapGb,
                     boolean automaticHeap, boolean automaticOptimization) {
        Machine machine = inspect();
        long[] modStats = inspectMods(modsDirectory);
        int count = (int) modStats[0];
        long bytes = modStats[1];
        int recommendedHeap = recommendHeap(machine, count, bytes);
        int heap = automaticHeap ? recommendedHeap : Math.max(2, Math.min(maxSafeHeapGb(machine), requestedHeapGb));
        String collector = "G1GC";
        List<String> args = automaticOptimization ? arguments(javaMajor, heap, count, machine.processors()) : List.of();
        return new Plan(heap, automaticOptimization ? collector : "JVM по умолчанию", args, count, bytes, machine);
    }

    public static CompatibleArguments compatibleArguments(Path javaExecutable, List<String> requested) {
        var accepted = new ArrayList<>(requested);
        var rejected = new ArrayList<String>();
        if (supports(javaExecutable, accepted)) return new CompatibleArguments(accepted, rejected);
        for (int index = accepted.size() - 1; index >= 0; index--) {
            String candidate = accepted.remove(index);
            rejected.add(0, candidate);
            if (supports(javaExecutable, accepted)) return new CompatibleArguments(accepted, rejected);
        }
        return new CompatibleArguments(List.of(), rejected);
    }

    public int recommendedHeapGb() {
        return recommendHeap(inspect(), 0, 0);
    }

    /** Largest heap the user may pick: physical RAM minus the OS reserve, within 2–16 GB. */
    public static int maxSafeHeapGb(Machine machine) {
        long total = machine.totalMemoryBytes();
        if (total <= 0) return 16;
        return (int) Math.max(2, Math.min(16, (total - memoryReserve(machine)) / GIB));
    }

    public DaemonMemoryPlan daemonMemoryPlan(Machine machine) {
        long totalMb = machine.totalMemoryBytes() / MIB;
        long availableMb = machine.availableMemoryBytes() / MIB;
        int heapMb = (int) Math.max(12, Math.min(20, totalMb / 1024));
        if (availableMb > 0 && totalMb > 0 && availableMb * 10 < totalMb) heapMb = Math.min(heapMb, 12);
        int initialMb = Math.max(4, heapMb / 4);
        int metaspaceMb = Math.max(12, heapMb);
        int codeCacheMb = Math.max(8, heapMb / 2);
        List<String> args = List.of(
                "-Xms" + initialMb + "m",
                "-Xmx" + heapMb + "m",
                "-XX:+UseSerialGC",
                "-XX:MaxMetaspaceSize=" + metaspaceMb + "m",
                "-XX:ReservedCodeCacheSize=" + codeCacheMb + "m"
        );
        return new DaemonMemoryPlan(initialMb, heapMb, metaspaceMb, codeCacheMb, args);
    }

    public static int javaMajorForMinecraftVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            int first = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")) : 0;
            if (first >= 26) return 25;
            if (minor > 20 || minor == 20 && patch >= 5) return 21;
            if (minor >= 18) return 17;
            if (minor == 17) return 16;
        } catch (RuntimeException ignored) {
        }
        return 8;
    }

    private static long[] systemMemory(String os) {
        if (os.contains("win")) {
            String output = runCommand("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$m=Get-CimInstance Win32_OperatingSystem; [long]$m.TotalVisibleMemorySize*1024; [long]$m.FreePhysicalMemory*1024");
            long[] values = parseLongLines(output);
            return values.length >= 2 ? new long[]{values[0], values[1]} : new long[]{0, 0};
        }
        if (os.contains("linux")) {
            try {
                long total = 0;
                long available = 0;
                for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                    String[] fields = line.split("\\s+");
                    if (fields.length < 2) continue;
                    long bytes = Long.parseLong(fields[1]) * 1024;
                    if (fields[0].equals("MemTotal:")) total = bytes;
                    if (fields[0].equals("MemAvailable:")) available = bytes;
                }
                return new long[]{total, available};
            } catch (IOException | NumberFormatException ignored) {
                return new long[]{0, 0};
            }
        }
        if (os.contains("mac")) {
            long total = parseLong(firstLine(runCommand("/usr/sbin/sysctl", "-n", "hw.memsize")));
            String vmStat = runCommand("/usr/bin/vm_stat");
            long pageSize = 4096;
            var pageSizeMatcher = java.util.regex.Pattern.compile("page size of (\\d+) bytes").matcher(vmStat);
            if (pageSizeMatcher.find()) pageSize = parseLong(pageSizeMatcher.group(1));
            long availablePages = 0;
            for (String line : vmStat.split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("pages free:") || lower.startsWith("pages inactive:") || lower.startsWith("pages speculative:")) {
                    String digits = line.replaceAll("[^0-9]", "");
                    availablePages += parseLong(digits);
                }
            }
            return new long[]{total, availablePages * pageSize};
        }
        return new long[]{0, 0};
    }

    private static long[] parseLongLines(String value) {
        return value.lines().map(String::trim).filter(line -> line.matches("\\d+"))
                .mapToLong(JavaOptimizer::parseLong).toArray();
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static int recommendHeap(Machine machine, int modCount, long modBytes) {
        long total = machine.totalMemoryBytes();
        if (total <= 0) return 4;
        boolean largeModpack = modCount >= 100 || modBytes >= 1_000 * MIB;
        boolean mediumModpack = modCount >= 45 || modBytes >= 400 * MIB;
        boolean hugeModpack = modCount >= 220 || modBytes >= 2_000 * MIB;
        int workloadTarget;
        if (total <= 4 * GIB) workloadTarget = 2;
        else if (total <= 8 * GIB) workloadTarget = mediumModpack ? 4 : 3;
        else if (total <= 16 * GIB) workloadTarget = hugeModpack ? 8 : largeModpack ? 7 : mediumModpack ? 6 : 4;
        else if (total <= 32 * GIB) workloadTarget = hugeModpack ? 12 : largeModpack ? 10 : mediumModpack ? 8 : 6;
        else workloadTarget = hugeModpack ? 16 : largeModpack ? 12 : mediumModpack ? 10 : 8;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        int totalBudget = (int) Math.max(2, (total - memoryReserve(machine)) / GIB);
        long free = machine.availableMemoryBytes() > 0 ? machine.availableMemoryBytes() : total;
        int currentBudget = (int) Math.max(2, (free - (windows ? 2 : 1) * GIB) / GIB);
        return Math.max(2, Math.min(16, Math.min(workloadTarget, Math.min(totalBudget, currentBudget))));
    }

    private static long memoryReserve(Machine machine) {
        long total = machine.totalMemoryBytes();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        long reserve = Math.max((windows ? 3 : 2) * GIB, Math.min((windows ? 8 : 6) * GIB, total / 4));
        if (usesSharedGraphicsMemory(machine.graphicsName())) reserve = Math.min(10 * GIB, reserve + GIB);
        return reserve;
    }

    private static boolean usesSharedGraphicsMemory(String graphicsName) {
        String gpu = graphicsName.toLowerCase(Locale.ROOT);
        return gpu.contains("apple m") || gpu.contains("intel hd") || gpu.contains("intel uhd")
                || gpu.contains("intel iris") || gpu.contains("radeon graphics")
                || gpu.contains("radeon 680m") || gpu.contains("radeon 780m");
    }

    private static List<String> arguments(int javaMajor, int heapGb, int modCount, int processors) {
        List<String> args = new ArrayList<>();
        int initialHeap = Math.min(heapGb, heapGb <= 2 ? heapGb : Math.max(2, heapGb / 2));
        args.add("-Xms" + initialHeap + "G");
        int pauseTarget = modCount >= 100 ? 200 : processors <= 4 ? 180 : 150;
        args.add("-XX:MaxGCPauseMillis=" + pauseTarget);
        for (FlagDefinition definition : FLAG_CATALOG) {
            if (!definition.automatic() || definition.argumentTemplate().contains("{value}")
                    || definition.minimumJava() > javaMajor
                    || definition.maximumJava() > 0 && definition.maximumJava() < javaMajor
                    || !definition.collector().isEmpty()
                    && !definition.collector().equals("G1GC")) continue;
            String argument = definition.argumentTemplate();
            if (argument.equals("-XX:+UseStringDeduplication") && (heapGb < 6 || modCount < 25)) continue;
            args.add(argument);
        }
        if (heapGb >= 8 && modCount >= 100) args.add("-XX:G1ReservePercent=20");
        return args;
    }

    private static boolean supports(Path javaExecutable, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(javaExecutable.toString());
        command.addAll(arguments);
        command.add("-version");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    private static long[] inspectMods(Path directory) {
        if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return new long[]{0, 0};
        long count = 0;
        long bytes = 0;
        try (var files = Files.list(directory)) {
            for (Path file : files.limit(MAX_MOD_FILES).toList()) {
                if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                count++;
                try { bytes += Files.size(file); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
        return new long[]{count, bytes};
    }

    private static String processorName(String os) {
        String fromEnvironment = System.getenv("PROCESSOR_IDENTIFIER");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.trim();
        if (os.contains("mac")) return firstLine(runCommand("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"));
        if (os.contains("win")) return firstLine(runCommand("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)"));
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("model name") || lower.startsWith("hardware")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0 && !line.substring(colon + 1).isBlank()) return line.substring(colon + 1).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return "Не определён";
    }

    private static String graphicsName(String os) {
        if (os.contains("mac")) {
            String output = runCommand("/usr/sbin/system_profiler", "SPDisplaysDataType", "-detailLevel", "mini");
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Chipset Model:")) return trimmed.substring("Chipset Model:".length()).trim();
            }
            return "Не определена";
        }
        if (os.contains("win")) {
            String output = runCommand("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_VideoController | Select-Object -First 1 -ExpandProperty Name");
            return firstLine(output);
        }
        String output = runCommand("lspci", "-mm");
        for (String line : output.split("\\R")) {
            if (line.contains("VGA compatible controller") || line.contains("3D controller") || line.contains("Display controller")) {
                String cleaned = line.replaceAll("^.*?: ", "").replaceAll(" \\[.*$", "").replaceAll("\\\"", "").trim();
                if (!cleaned.isBlank()) return cleaned;
            }
        }
        return "Не определена";
    }

    private static String firstLine(String value) {
        if (value == null) return "Не определён";
        for (String line : value.split("\\R")) if (!line.isBlank()) return line.trim();
        return "Не определён";
    }

    private static String runCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(Duration.ofSeconds(4).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) return "";
            byte[] output = process.getInputStream().readNBytes(32 * 1024);
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return "";
        }
    }
}
