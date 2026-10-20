package com.cactuslauncher.model

data class ModHelperIssue(
    val projectTitle: String,
    val buildName: String,
    val minecraftVersion: String,
    val loader: String,
    val message: String,
    val dependencyId: String? = null,
    val conflict: Boolean = false,
)
