package com.cactuslauncher.model

data class ModrinthProject(
    val id: String,
    val title: String,
    val description: String,
    val author: String,
    val downloads: Long,
    val iconUrl: String,
)

data class InstalledMod(
    val fileName: String,
    val title: String,
    val projectId: String,
    val version: String,
    val enabled: Boolean = true,
)

data class InstalledResourcePack(
    val fileName: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
)
