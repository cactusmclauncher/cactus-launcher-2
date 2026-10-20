package com.cactuslauncher.model

data class JavaOptimizationInfo(
    val totalRamGb: Int,
    val availableRamGb: Int,
    val logicalProcessors: Int,
    val processorName: String,
    val graphicsName: String,
    val javaVersion: String,
    val maxMemoryGb: Int = 16,
)
