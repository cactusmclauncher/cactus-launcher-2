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

/** An available Modrinth update of an installed jar; [dependencies] are titles of mods it will add. */
data class ModUpdate(
    val fileName: String,
    val title: String,
    val currentVersion: String,
    val newVersion: String,
    val dependencies: List<String>,
)

data class ModUpdateResult(val updated: Int, val dependencies: Int)

/** The newest mod update that can be rolled back; [confirmed] once a game session ended normally after it. */
data class ModUpdateBackup(
    val createdText: String,
    val replaced: Int,
    val added: Int,
    val confirmed: Boolean,
    /** "19.10", for the build settings line. */
    val createdShort: String = createdText,
)
