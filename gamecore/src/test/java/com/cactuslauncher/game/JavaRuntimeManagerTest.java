package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRuntimeManagerTest {
    @TempDir Path temp;

    private Path fakeRuntime(String folder, String javaVersion) throws Exception {
        Path runtime = Files.createDirectories(temp.resolve(folder).resolve("jdk/bin"));
        Path java = Files.writeString(runtime.resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"), "");
        java.toFile().setExecutable(true);
        if (javaVersion != null) Files.writeString(runtime.getParent().resolve("release"), "JAVA_VERSION=\"" + javaVersion + "\"\n");
        return temp.resolve(folder);
    }

    @Test
    void parsesJavaMajorVersions() {
        assertEquals(8, JavaRuntimeManager.javaMajor("1.8.0_402"));
        assertEquals(21, JavaRuntimeManager.javaMajor("21.0.12.1"));
        assertEquals(17, JavaRuntimeManager.javaMajor("17"));
        assertEquals(-1, JavaRuntimeManager.javaMajor("unknown"));
    }

    @Test
    void keepsValidRuntime() throws Exception {
        Path destination = fakeRuntime("21-linux-x64", "21.0.4");
        Path executable = JavaRuntimeManager.existingRuntime(destination, 21);
        assertNotNull(executable);
        assertTrue(executable.startsWith(destination));
    }

    @Test
    void removesBrokenOrMismatchedRuntime() throws Exception {
        Path missingRelease = fakeRuntime("17-linux-x64", null);
        assertNull(JavaRuntimeManager.existingRuntime(missingRelease, 17));
        assertFalse(Files.exists(missingRelease));

        Path wrongVersion = fakeRuntime("8-linux-x64", "17.0.1");
        assertNull(JavaRuntimeManager.existingRuntime(wrongVersion, 8));
        assertFalse(Files.exists(wrongVersion));

        Path empty = Files.createDirectories(temp.resolve("25-linux-x64"));
        assertNull(JavaRuntimeManager.existingRuntime(empty, 25));
        assertFalse(Files.exists(empty));
    }
}
