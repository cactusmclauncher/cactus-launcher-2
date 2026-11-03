package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherPreferencesStoreTest {
    @TempDir Path temp;

    @Test
    void automaticSeasonRoundTripsAndOldExplicitChoicesStayExplicit() throws Exception {
        var file = temp.resolve("seasons.properties");
        var store = new LauncherPreferencesStore(file);
        assertNull(store.load());
        for (String season : LauncherPreferencesStore.SEASONAL_THEMES) {
            for (String theme : LauncherPreferencesStore.THEMES) {
                for (boolean complete : new boolean[] {false, true}) {
                    var preferences = new LauncherPreferencesStore.Preferences(complete, true, 4, 4, true, true, true, true, true, false, false, true,
                            "1920 × 1080", "/games", theme, season);
                    store.save(preferences);
                    assertEquals(preferences, store.load());
                }
            }
        }
    }

    @Test
    void missingSeasonMigratesToAutoButInvalidSeasonKeepsSafeOff() throws Exception {
        var file = temp.resolve("migration.properties");
        var store = new LauncherPreferencesStore(file);
        store.save(new LauncherPreferencesStore.Preferences(false, true, 4, 4, true, true, true, true, true, false, false, true,
                "1920 × 1080", "/games", "light", "off"));
        Files.writeString(file, Files.readString(file).replace("seasonalTheme=off", ""));
        assertEquals("auto", store.load().seasonalTheme());
        assertEquals("light", store.load().theme());
        assertFalse(store.load().setupComplete());
        store.save(store.load());
        assertEquals("auto", store.load().seasonalTheme());
        Files.writeString(file, Files.readString(file).replace("seasonalTheme=auto", "seasonalTheme=unknown"));
        assertEquals("off", store.load().seasonalTheme());
    }

    @Test
    void winterThemeRoundTripsWithoutChangingBaseTheme() throws Exception {
        var store = new LauncherPreferencesStore(temp.resolve("winter.properties"));
        for (String theme : LauncherPreferencesStore.THEMES) {
            var preferences = new LauncherPreferencesStore.Preferences(true, true, 4, 4, true, true, true, true, true, false, false, true,
                    "1920 × 1080", "/games", theme, "winter");
            store.save(preferences);
            assertEquals(preferences, store.load());
            assertEquals("winter", store.load().seasonalTheme());
            assertEquals(theme, store.load().theme());
        }
        store.save(new LauncherPreferencesStore.Preferences(true, true, 4, 4, true, true, true, true, true, false, false, true,
                "1920 × 1080", "/games", "dark", "off"));
        assertEquals("off", store.load().seasonalTheme());
    }

    /** A test build briefly remembered the open section of "Остальное"; such files must still load. */
    @Test
    void settingsWithTheOldOtherSectionKeyStillLoad() throws Exception {
        Path file = temp.resolve("settings.properties");
        var store = new LauncherPreferencesStore(file);
        store.save(new LauncherPreferencesStore.Preferences(true, true, 4, 4, true, true, true, true, true, false, false, true,
                "1920 × 1080", "/games", "dark", "off"));
        Files.writeString(file, Files.readString(file) + "otherSection=servers\n");
        var loaded = store.load();
        assertEquals("dark", loaded.theme());
        assertEquals(4, loaded.memoryGb());
        store.save(loaded);
        assertFalse(Files.readString(file).contains("otherSection"), "the old key is dropped on the next save");
        assertFalse(loaded.ghostModeIntroSeen());
    }
}
