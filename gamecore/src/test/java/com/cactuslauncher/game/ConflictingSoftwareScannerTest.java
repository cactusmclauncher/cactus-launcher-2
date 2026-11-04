package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictingSoftwareScannerTest {
    @Test
    void quotedUninstallPathStaysOneArgument() {
        assertEquals(
                List.of("C:\\Program Files\\360\\uninst.exe", "/S"),
                ConflictingSoftwareScanner.splitCommand("\"C:\\Program Files\\360\\uninst.exe\" /S"));
    }

    @Test
    void msiUninstallCommandIsSplitOnSpaces() {
        assertEquals(
                List.of("MsiExec.exe", "/X{ABC-123}"),
                ConflictingSoftwareScanner.splitCommand("MsiExec.exe /X{ABC-123}"));
    }

    @Test
    void only360SecurityProductsMatch() {
        assertTrue(ConflictingSoftwareScanner.is360Security("360 Total Security"));
        assertTrue(ConflictingSoftwareScanner.is360Security("360安全卫士"));
        assertFalse(ConflictingSoftwareScanner.is360Security("360 Extreme Explorer"));
        assertFalse(ConflictingSoftwareScanner.is360Security("Google Chrome"));
        assertFalse(ConflictingSoftwareScanner.is360Security(null));
    }

    @Test
    void onlyYandexBrowserMatches() {
        assertTrue(ConflictingSoftwareScanner.isYandexBrowser("Yandex Browser"));
        assertTrue(ConflictingSoftwareScanner.isYandexBrowser("Яндекс Браузер"));
        assertTrue(ConflictingSoftwareScanner.isYandexBrowser("Яндекс"));
        assertFalse(ConflictingSoftwareScanner.isYandexBrowser("Yandex Music"));
        assertFalse(ConflictingSoftwareScanner.isYandexBrowser("360 Total Security"));
        assertFalse(ConflictingSoftwareScanner.isYandexBrowser(null));
    }
}