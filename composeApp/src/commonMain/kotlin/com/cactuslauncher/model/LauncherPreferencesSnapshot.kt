package com.cactuslauncher.model

data class LauncherPreferencesSnapshot(
    val setupComplete: Boolean,
    val showPlayer: Boolean,
    val parallelDownloads: Int,
    val memoryGb: Int,
    val automaticMemory: Boolean,
    val autoJavaOptimization: Boolean,
    val ghostMode: Boolean,
    val fullscreen: Boolean,
    val autoUpdate: Boolean,
    val advancedMode: Boolean,
    val translateModDescriptions: Boolean,
    val modAntivirusEnabled: Boolean,
    val resolution: String,
    val folder: String,
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** C418 music when the launcher opens. */
    val launcherMusic: Boolean = true,
    /** The resource pack that puts the C418 table into the game's title-screen music. */
    val menuMusicPack: Boolean = true,
    val playerMode: String = "external",
    val radioEnabled: Boolean = false,
)
