package com.cactuslauncher.model

/** [avatar]: the profile picture, or null to show the default artwork. */
data class PlayerProfile(val id: String, val name: String, val avatar: androidx.compose.ui.graphics.ImageBitmap? = null)
data class ProfileSnapshot(val profiles: List<PlayerProfile>, val activeId: String) {
    val active: PlayerProfile? get() = profiles.find { it.id == activeId }
}

/** Same rule as ProfileStore.validate: Minecraft name, 3–16 of A–Z, a–z, 0–9 and _. */
fun isValidPlayerName(name: String) = name.trim().matches(Regex("[A-Za-z0-9_]{3,16}"))
