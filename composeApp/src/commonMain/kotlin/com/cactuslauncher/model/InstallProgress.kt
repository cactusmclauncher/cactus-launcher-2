package com.cactuslauncher.model

data class InstallProgress(
    val fraction: Float = 0f,
    val stage: String = "Подготовка к скачиванию",
    val lastFile: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val bytesPerSecond: Double = 0.0,
    val remainingSeconds: Long = -1,
    val phase: Int = 0,
)
