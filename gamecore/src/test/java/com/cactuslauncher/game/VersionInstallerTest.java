package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionInstallerTest {
    @TempDir Path temp;

    private VersionInstaller installer() { return new VersionInstaller(temp.resolve("build"), progress -> { }); }

    @Test
    void safeAcceptsPathsInsideRoot() throws Exception {
        Path path = installer().safe("versions/1.21.1/1.21.1.jar");
        assertEquals(temp.resolve("build/versions/1.21.1/1.21.1.jar").toAbsolutePath().normalize(), path);
    }

    @Test
    void safeRejectsTraversalAndRoot() {
        var installer = installer();
        assertThrows(IOException.class, () -> installer.safe("../outside.jar"));
        assertThrows(IOException.class, () -> installer.safe("assets/../../outside"));
        assertThrows(IOException.class, () -> installer.safe("versions/.."));
    }

    @Test
    void safeRejectsSymlinkInPath() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createDirectories(temp.resolve("build"));
        Files.createSymbolicLink(temp.resolve("build/libraries"), outside);
        assertThrows(IOException.class, () -> installer().safe("libraries/org/lib.jar"));
    }

    @Test
    void libraryRulesFollowLastMatchingRule() {
        String os = System.getProperty("os.name", "").toLowerCase().contains("win") ? "windows"
                : System.getProperty("os.name", "").toLowerCase().contains("mac") ? "osx" : "linux";
        var allowHereOnly = JsonParser.parseString("{\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"" + os + "\"}}]}").getAsJsonObject();
        var disallowHere = JsonParser.parseString("{\"rules\":[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"" + os + "\"}}]}").getAsJsonObject();
        var featureOnly = JsonParser.parseString("{\"rules\":[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]}").getAsJsonObject();
        assertTrue(VersionInstaller.allowed(JsonParser.parseString("{}").getAsJsonObject()));
        assertTrue(VersionInstaller.allowed(allowHereOnly));
        assertFalse(VersionInstaller.allowed(disallowHere));
        assertFalse(VersionInstaller.allowed(featureOnly));
    }
}
