package com.cactuslauncher.model

data class MinecraftVersion(
    val id: String,
    val type: String,
    val releasedAt: String,
    val latestRelease: Boolean,
    val latestSnapshot: Boolean,
)

enum class VersionFilter(val label: String) {
    ALL("Все"), NEW("Новые"), POPULAR("Популярные"), RELEASE("Релизы"), SNAPSHOT("Снапшоты"),
    OLD("Старые"), BETA("Beta"), ALPHA("Alpha");

    fun matches(version: MinecraftVersion): Boolean = when (this) {
        ALL -> true
        NEW -> version.latestRelease || version.latestSnapshot
        POPULAR -> version.type == "release" && (version.latestRelease || version.id in setOf("1.21.1", "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.8.9", "1.7.10"))
        RELEASE -> version.type == "release"
        SNAPSHOT -> version.type == "snapshot"
        OLD -> version.type.startsWith("old_") || version.releasedAt.take(10) < "2017-01-01"
        BETA -> version.type == "old_beta"
        ALPHA -> version.type == "old_alpha"
    }
}
