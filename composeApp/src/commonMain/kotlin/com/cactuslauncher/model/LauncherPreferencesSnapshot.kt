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
)
