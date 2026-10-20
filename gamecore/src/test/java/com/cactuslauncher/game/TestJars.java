package com.cactuslauncher.game;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** Builds small mod JARs for tests by compiling Java sources with the JDK compiler. */
final class TestJars {
    private TestJars() {}

    /** Compiles {@code className -> source} pairs and returns {@code entry name -> class bytes}. */
    static Map<String, byte[]> compile(Path work, Map<String, String> sources) throws IOException {
        Path src = Files.createDirectories(work.resolve("src-" + System.nanoTime()));
        Path out = Files.createDirectories(work.resolve("out-" + System.nanoTime()));
        var files = new java.util.ArrayList<String>();
        for (var source : sources.entrySet()) {
            Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Tests need a JDK with javax.tools");
        var arguments = new java.util.ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
        arguments.addAll(files);
        int result = compiler.run(null, null, null, arguments.toArray(String[]::new));
        if (result != 0) throw new IllegalStateException("Compilation failed");
        var classes = new LinkedHashMap<String, byte[]>();
        try (var paths = Files.walk(out)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                classes.put(out.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return classes;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static Path write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }
}
