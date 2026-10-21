package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthlibInjectorTest {
    @TempDir Path temp;

    private static final byte[] JAR = "authlib-injector bytes".getBytes(StandardCharsets.UTF_8);

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private AuthlibInjector.Artifact artifact(String version) throws Exception {
        return new AuthlibInjector.Artifact(version, "https://authlib-injector.yushi.moe/artifact/" + version + ".jar", sha256(JAR));
    }

    private AuthlibInjector online(String version) throws Exception {
        var artifact = artifact(version);
        return new AuthlibInjector(temp, () -> artifact, (url, target) -> Files.write(target, JAR));
    }

    private AuthlibInjector offline() {
        return new AuthlibInjector(temp, () -> { throw new IOException("catalog unreachable"); },
                (url, target) -> { throw new IOException("download unreachable"); });
    }

    @Test
    void verifiedDownloadIsCachedWithChecksumSidecar() throws Exception {
        Path jar = online("1.2.5").ensure();
        assertArrayEquals(JAR, Files.readAllBytes(jar));
        assertEquals(sha256(JAR), Files.readString(jar.resolveSibling(jar.getFileName() + ".sha256")).trim());
    }

    @Test
    void offlineLaunchUsesVerifiedCache() throws Exception {
        Path cached = online("1.2.5").ensure();
        assertEquals(cached, offline().ensure());
    }

    @Test
    void offlineWithoutCacheReportsNetworkError() {
        var error = assertThrows(IOException.class, () -> offline().ensure());
        assertEquals("catalog unreachable", error.getMessage());
    }

    @Test
    void tamperedCacheIsNotUsed() throws Exception {
        Path cached = online("1.2.5").ensure();
        Files.writeString(cached, "modified");
        assertThrows(IOException.class, () -> offline().ensure());
    }

    @Test
    void failedDownloadFallsBackToOlderCache() throws Exception {
        Path cached = online("1.2.5").ensure();
        var newer = artifact("1.2.6");
        var injector = new AuthlibInjector(temp, () -> newer, (url, target) -> { throw new IOException("download failed"); });
        assertEquals(cached, injector.ensure());
    }

    @Test
    void checksumMismatchIsRejectedWithoutCache() throws Exception {
        var artifact = artifact("1.2.5");
        var injector = new AuthlibInjector(temp, () -> artifact, (url, target) -> Files.writeString(target, "evil"));
        var error = assertThrows(IOException.class, injector::ensure);
        assertTrue(error.getMessage().contains("Контрольная сумма"));
        assertFalse(Files.exists(temp.resolve("authlib-injector-1.2.5.jar")));
    }
}
