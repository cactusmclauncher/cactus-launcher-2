package com.cactuslauncher.model

/** A known security product that conflicts with the launcher and could be removed with consent. */
data class ConflictingSoftware(
    val name: String,
    val detail: String,
    val uninstallable: Boolean,
)