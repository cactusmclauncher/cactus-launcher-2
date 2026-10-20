package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRecoveryTest {
    @TempDir Path temp;

    private Path build() throws IOException {
        Path build = Files.createDirectories(temp.resolve("build"));
        Files.createDirectories(build.resolve("mods/1.21.1"));
        Files.writeString(build.resolve("mods/good.jar"), "good");
        Files.writeString(build.resolve("mods/bad.jar"), "bad");
        Files.writeString(build.resolve("mods/1.21.1/nested.jar"), "nested");
        Files.createDirectories(build.resolve("config/sodium/advanced"));
        Files.writeString(build.resolve("config/top.toml"), "top");
        Files.writeString(build.resolve("config/sodium/options.json"), "{}");
        Files.writeString(build.resolve("config/sodium/advanced/deep.json"), "deep");
        Files.writeString(build.resolve("options.txt"), "fov:1");
        return build;
    }

    @Test
    void copiesConfigRecursivelyAndExcludesSuspectMod() throws Exception {
        Path workspace = new CrashRecovery().createWorkspace(build(), temp.resolve("recovery"), "bad.jar");
        assertEquals("deep", Files.readString(workspace.resolve("config/sodium/advanced/deep.json")));
        assertEquals("{}", Files.readString(workspace.resolve("config/sodium/options.json")));
        assertEquals("top", Files.readString(workspace.resolve("config/top.toml")));
        assertTrue(Files.isRegularFile(workspace.resolve("mods/good.jar")));
        assertFalse(Files.exists(workspace.resolve("mods/bad.jar")));
        assertFalse(Files.exists(workspace.resolve("mods/1.21.1")));
        assertEquals("fov:1", Files.readString(workspace.resolve("options.txt")));
    }

    @Test
    void doesNotFollowSymlinksInConfig() throws Exception {
        Path build = build();
        Path secret = Files.createDirectories(temp.resolve("secret"));
        Files.writeString(secret.resolve("key.txt"), "secret");
        Files.createSymbolicLink(build.resolve("config/linked-dir"), secret);
        Files.createSymbolicLink(build.resolve("config/linked-file.txt"), secret.resolve("key.txt"));
        Path workspace = new CrashRecovery().createWorkspace(build, temp.resolve("recovery"), "bad.jar");
        assertFalse(Files.exists(workspace.resolve("config/linked-dir"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(workspace.resolve("config/linked-file.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void deleteWorkspaceRemovesNestedFolders() throws Exception {
        var recovery = new CrashRecovery();
        Path workspace = recovery.createWorkspace(build(), temp.resolve("recovery"), "bad.jar");
        recovery.deleteWorkspace(workspace);
        assertFalse(Files.exists(workspace));
        assertTrue(Files.exists(temp.resolve("build/config/sodium/advanced/deep.json")), "Original build must stay intact");
    }

    @Test
    void rejectsExcludedModOutsideModsFolder() {
        assertThrows(IOException.class, () -> new CrashRecovery().createWorkspace(build(), temp.resolve("recovery"), "../bad.jar"));
        assertThrows(IOException.class, () -> new CrashRecovery().createWorkspace(build(), temp.resolve("recovery"), "notes.txt"));
    }
}
