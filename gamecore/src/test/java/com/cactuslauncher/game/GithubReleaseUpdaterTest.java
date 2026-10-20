package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class GithubReleaseUpdaterTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "B".repeat(64);

    @Test
    void compareVersionsTreatsMissingPartsAsZero() throws Exception {
        assertEquals(0, GithubReleaseUpdater.compareVersions("2", "2.0.0"));
        assertTrue(GithubReleaseUpdater.compareVersions("2.0.10", "2.0.9") > 0);
        assertTrue(GithubReleaseUpdater.compareVersions("1.9.9", "2.0.0") < 0);
    }

    @Test
    void compareVersionsRejectsInvalidTags() {
        assertThrows(IOException.class, () -> GithubReleaseUpdater.compareVersions("", "2.0.0"));
        assertThrows(IOException.class, () -> GithubReleaseUpdater.compareVersions("2.0.0-beta", "2.0.0"));
        assertThrows(IOException.class, () -> GithubReleaseUpdater.compareVersions("99999999999.0.0", "2.0.0"));
    }

    @Test
    void checksumFindsExactAssetIncludingBinaryMarker() throws Exception {
        String sums = HASH_A + "  CactusLauncher-2.0.1-linux-x64.deb\r\n"
                + HASH_B + " *CactusLauncher-2.0.1-windows-x64.msi\n";
        assertEquals(HASH_A, GithubReleaseUpdater.checksum(sums, "CactusLauncher-2.0.1-linux-x64.deb"));
        assertEquals(HASH_B, GithubReleaseUpdater.checksum(sums, "CactusLauncher-2.0.1-windows-x64.msi"));
    }

    @Test
    void checksumRejectsMissingOrMalformedEntries() {
        String sums = "not-a-hash  CactusLauncher-2.0.1-macos-arm64.dmg\n"
                + HASH_A + "  CactusLauncher-2.0.1-macos-arm64.dmg.old\n";
        assertThrows(IOException.class, () -> GithubReleaseUpdater.checksum(sums, "CactusLauncher-2.0.1-macos-arm64.dmg"));
        assertThrows(IOException.class, () -> GithubReleaseUpdater.checksum("", "anything.msi"));
    }
}
