package com.cactuslauncher.model

data class BuildSnapshot(
    val id: String,
    val createdMillis: Long,
    val label: String,
    val files: Int,
    val uniqueBytes: Long,
)
