package com.cactuslauncher.model

data class LauncherUpdate(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val sha256: String,
    val available: Boolean,
)
