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
)
