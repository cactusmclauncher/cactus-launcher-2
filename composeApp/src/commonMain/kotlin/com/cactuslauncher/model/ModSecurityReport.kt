package com.cactuslauncher.model

data class ModSecurityReport(
    val level: String,
    val findings: List<String>,
    val scannedClasses: Int,
)
