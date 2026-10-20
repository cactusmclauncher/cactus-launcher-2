package com.cactuslauncher.model

data class JavaOptimizationPlan(
    val heapGb: Int,
    val collector: String,
    val arguments: List<String>,
    val modJarCount: Int,
)
