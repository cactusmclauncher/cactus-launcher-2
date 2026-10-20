package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModSecurityScannerTest {
    @TempDir Path temp;
    private final ModSecurityScanner scanner = new ModSecurityScanner();

    private static final String BENIGN = """
            package demo;
            import java.util.concurrent.*;
            public class Mod {
                public static void init() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.submit(() -> { if (System.nanoTime() < 0) throw new RuntimeException("execute"); });
                    Runnable task = Mod::tick;
                    executor.execute(task);
                }
                static void tick() { }
            }
            """;

    private static final String RUNTIME_EXEC = """
            package evil;
            public class Payload {
                public static void run() throws Exception { Runtime.getRuntime().exec(new String[] {"powershell", "-c", "x"}); }
            }
            """;

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        return TestJars.write(temp.resolve(name), TestJars.zip(entries));
    }

    @Test
    void benignModWithLambdasExecutorsAndRuntimeExceptionIsClear() throws Exception {
        var report = scanner.scan(jar("benign.jar", TestJars.compile(temp, Map.of("demo.Mod", BENIGN))));
        assertEquals("clear", report.level(), report.findings().toString());
        assertTrue(report.findings().isEmpty());
        assertTrue(report.scannedClasses() >= 1);
    }

    @Test
    void runtimeExecWithShellCommandIsWarning() throws Exception {
        var report = scanner.scan(jar("exec.jar", TestJars.compile(temp, Map.of("evil.Payload", RUNTIME_EXEC))));
        assertEquals("warning", report.level());
        assertTrue(report.findings().stream().anyMatch(f -> f.contains("команды оболочки")), report.findings().toString());
    }

    @Test
    void processBuilderStartIsWarning() throws Exception {
        String source = """
                package evil;
                public class Spawn { public static void run() throws Exception { new ProcessBuilder("calc").start(); } }
                """;
        var report = scanner.scan(jar("pb.jar", TestJars.compile(temp, Map.of("evil.Spawn", source))));
        assertEquals("warning", report.level());
        assertTrue(report.findings().contains("Мод содержит вызовы запуска системных процессов."));
    }

    @Test
    void reflectiveRuntimeLookupIsWarning() throws Exception {
        String source = """
                package evil;
                public class Reflect {
                    public static Object run() throws Exception {
                        Class<?> type = Class.forName("java.lang.Runtime");
                        return type.getMethod("getRuntime").invoke(null);
                    }
                }
                """;
        var report = scanner.scan(jar("reflect.jar", TestJars.compile(temp, Map.of("evil.Reflect", source))));
        assertEquals("warning", report.level(), report.findings().toString());
    }

    @Test
    void cryptoWithCustomDefineClassIsReported() throws Exception {
        String source = """
                package evil;
                import javax.crypto.Cipher;
                public class Loader extends ClassLoader {
                    public Class<?> load(byte[] data) throws Exception {
                        Cipher cipher = Cipher.getInstance("AES");
                        return defineClass(null, data, 0, data.length);
                    }
                }
                """;
        var report = scanner.scan(jar("loader.jar", TestJars.compile(temp, Map.of("evil.Loader", source))));
        // Without obfuscation this combination stays a notice, as before; with short class names it becomes a warning.
        assertEquals("notice", report.level());
        assertTrue(report.findings().stream().anyMatch(f -> f.contains("динамической загрузкой")), report.findings().toString());
    }

    @Test
    void persistenceStringsAreWarning() throws Exception {
        String source = """
                package evil;
                public class Persist { public static String path() { return System.getProperty("user.home") + "/.ssh/authorized_keys"; } }
                """;
        var report = scanner.scan(jar("persist.jar", TestJars.compile(temp, Map.of("evil.Persist", source))));
        assertEquals("warning", report.level());
    }

    @Test
    void execInsideNestedJarIsFlagged() throws Exception {
        byte[] inner = TestJars.zip(TestJars.compile(temp, Map.of("evil.Payload", RUNTIME_EXEC)));
        var outer = new LinkedHashMap<>(TestJars.compile(temp, Map.of("demo.Mod", BENIGN)));
        outer.put("META-INF/jars/helper.jar", inner);
        var report = scanner.scan(jar("outer.jar", outer));
        assertEquals("warning", report.level());
        assertTrue(report.findings().stream().anyMatch(f -> f.contains("вложенном JAR") && f.contains("META-INF/jars/helper.jar")),
                report.findings().toString());
    }

    @Test
    void nestingDeeperThanLimitIsReportedNotScanned() throws Exception {
        byte[] current = TestJars.zip(TestJars.compile(temp, Map.of("evil.Payload", RUNTIME_EXEC)));
        for (int level = 0; level < ModSecurityScanner.MAX_NESTED_DEPTH + 1; level++)
            current = TestJars.zip(Map.of("META-INF/jars/level" + level + ".jar", current));
        var report = scanner.scan(jar("deep.jar", Map.of("META-INF/jars/top.jar", current)));
        assertTrue(report.findings().stream().anyMatch(f -> f.contains("не проверены")), report.findings().toString());
        assertFalse(report.findings().stream().anyMatch(f -> f.contains("вложенном JAR")));
    }

    @Test
    void corruptClassFileIsNoticeNotCrash() throws Exception {
        var report = scanner.scan(jar("broken.jar", Map.of("a/Broken.class", new byte[] {1, 2, 3, 4})));
        assertEquals("notice", report.level());
        assertTrue(report.findings().stream().anyMatch(f -> f.contains("Не удалось разобрать")));
    }

    @Test
    void rejectsNonJarAndSymlink() throws Exception {
        Path zip = TestJars.write(temp.resolve("pack.zip"), TestJars.zip(Map.of("x.txt", new byte[] {1})));
        assertThrows(java.io.IOException.class, () -> scanner.scan(zip));
        Path real = jar("real.jar", Map.of("x.txt", new byte[] {1}));
        Path link = temp.resolve("link.jar");
        Files.createSymbolicLink(link, real);
        assertThrows(java.io.IOException.class, () -> scanner.scan(link));
    }
}
