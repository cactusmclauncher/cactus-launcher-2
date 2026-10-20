package com.cactuslauncher.model

data class AudioSession(
    val id: String,
    val name: String,
    val volume: Float,
    val muted: Boolean,
)

data class AudioMixerSnapshot(
    val sessions: List<AudioSession>,
    val supported: Boolean,
    val message: String? = null,
)
