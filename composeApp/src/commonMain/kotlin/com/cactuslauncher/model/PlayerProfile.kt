package com.cactuslauncher.model

data class PlayerProfile(val id: String, val name: String)
data class ProfileSnapshot(val profiles: List<PlayerProfile>, val activeId: String) {
    val active: PlayerProfile? get() = profiles.find { it.id == activeId }
}

/** Same rule as ProfileStore.validate: Minecraft name, 3–16 of A–Z, a–z, 0–9 and _. */
fun isValidPlayerName(name: String) = name.trim().matches(Regex("[A-Za-z0-9_]{3,16}"))
