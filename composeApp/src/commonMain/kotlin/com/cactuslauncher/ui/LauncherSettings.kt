package com.cactuslauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.model.LauncherPreferencesSnapshot

/** UI preferences retained while navigating and reopening dialogs. */
class LauncherSettings(gameDir: String) {
    var showPlayer by mutableStateOf(true)
    var parallelDownloads by mutableIntStateOf(4)
    var memoryGb by mutableIntStateOf(4)
    var automaticMemory by mutableStateOf(true)
    var autoJavaOptimization by mutableStateOf(true)
    var ghostMode by mutableStateOf(true)
    var fullscreen by mutableStateOf(true)
    var autoUpdate by mutableStateOf(true)
    var advancedMode by mutableStateOf(false)
    var translateModDescriptions by mutableStateOf(false)
    var modAntivirusEnabled by mutableStateOf(true)
    var resolution by mutableStateOf("1920 × 1080")
    var folder by mutableStateOf(gameDir)
    var theme by mutableStateOf("system")

    fun snapshot(setupComplete: Boolean) = LauncherPreferencesSnapshot(
        setupComplete, showPlayer, parallelDownloads, memoryGb, automaticMemory,
        autoJavaOptimization, ghostMode, fullscreen, autoUpdate, advancedMode,
        translateModDescriptions, modAntivirusEnabled, resolution, folder, theme,
    )

    fun restore(value: LauncherPreferencesSnapshot) {
        showPlayer = value.showPlayer
        parallelDownloads = value.parallelDownloads.coerceIn(1, 16)
        memoryGb = value.memoryGb.coerceIn(2, 16)
        automaticMemory = value.automaticMemory
        autoJavaOptimization = value.autoJavaOptimization
        ghostMode = value.ghostMode
        fullscreen = value.fullscreen
        autoUpdate = value.autoUpdate
        advancedMode = value.advancedMode
        translateModDescriptions = value.translateModDescriptions
        modAntivirusEnabled = value.modAntivirusEnabled
        resolution = value.resolution
        folder = value.folder
        theme = value.theme
    }

    fun reset(gameDir: String) {
        showPlayer = true
        parallelDownloads = 4
        memoryGb = 4
        automaticMemory = true
        autoJavaOptimization = true
        ghostMode = true
        fullscreen = true
        autoUpdate = true
        advancedMode = false
        translateModDescriptions = false
        modAntivirusEnabled = true
        resolution = "1920 × 1080"
        folder = gameDir
        theme = "system"
    }
}
