package com.cactuslauncher.model

/** A known product that conflicts with the launcher and can be force-removed with consent. */
data class ConflictingSoftware(
    val id: String,
    val name: String,
    val detail: String,
)
