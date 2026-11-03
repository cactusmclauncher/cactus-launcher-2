package com.cactuslauncher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LauncherSettingsSeasonTest {
    @Test
    fun freshAndResetSettingsUseAutomaticSeasonWithoutPersistingResolvedMonth() {
        val settings = LauncherSettings("/games")
        assertEquals("auto", settings.seasonalTheme)
        assertEquals("winter", settings.resolvedSeasonalTheme("winter"))
        assertEquals("off", settings.resolvedSeasonalTheme("off"))
        assertEquals("auto", settings.snapshot(false).seasonalTheme)
        assertFalse(settings.snapshot(false).setupComplete)
        settings.seasonalTheme = "off"
        settings.reset("/games")
        assertEquals("auto", settings.seasonalTheme)
    }

    @Test
    fun restoringAllModesPreservesBaseThemeAndExplicitOverrides() {
        for (theme in listOf("system", "light", "dark")) {
            for (mode in listOf("auto", "off", "winter")) {
                val original = LauncherSettings("/games")
                original.theme = theme
                original.seasonalTheme = mode
                for (complete in listOf(false, true)) {
                    val saved = original.snapshot(complete)
                    val restored = LauncherSettings("/other")
                    restored.restore(saved)
                    assertEquals(saved, restored.snapshot(complete))
                    for (automatic in listOf("off", "winter")) {
                        assertEquals(if (mode == "auto") automatic else mode, restored.resolvedSeasonalTheme(automatic))
                        assertEquals(theme, restored.theme)
                        assertEquals(mode, restored.snapshot(complete).seasonalTheme)
                    }
                }
            }
        }
    }
}
