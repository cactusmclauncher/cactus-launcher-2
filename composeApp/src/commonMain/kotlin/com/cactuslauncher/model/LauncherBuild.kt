package com.cactuslauncher.model

data class LauncherBuild(
    val id: String,
    val name: String,
    val version: String,
    val loader: String,
    val lastPlayed: String,
    val installed: Boolean = false,
    val icon: String = "grass",
    val customIcon: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Total play time, e.g. "12 ч 40 мин"; empty before the first counted session. */
    val playtime: String = "",
    /** Build list line: "12 ч 40 мин · вчера", or [lastPlayed] for builds without counted time. */
    val playSummary: String = lastPlayed,
    val autoUpdateMods: Boolean = false,
)
