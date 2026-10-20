package com.cactuslauncher.model

data class CrashDiagnosis(
    val title: String,
    val detail: String,
    val evidence: String,
    val suspectMod: String? = null,
    val confidence: String = "",
    val recoverable: Boolean = false,
)
