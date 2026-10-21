package com.cactuslauncher.model

/** Sections of "Остальное"; the menu lists them in this order. */
enum class OtherSection(val title: String, val subtitle: String) {
    Screenshots("Скриншоты", "Снимки из игры, отдельно для каждой сборки"),
    Servers("Серверы", "Список серверов из игры, отдельно для каждой сборки"),
}

/** Live numbers for the "Остальное" menu, across all builds. */
data class OtherOverview(
    val screenshots: Int,
    /** The newest screenshot of any build: build id and the shot, for the menu image. */
    val latestShot: Pair<String, Screenshot>?,
    val servers: Int,
    val serverBuilds: Int,
)
