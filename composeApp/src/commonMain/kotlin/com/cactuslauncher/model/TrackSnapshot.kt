package com.cactuslauncher.model

import androidx.compose.ui.graphics.ImageBitmap

data class TrackSnapshot(
    val title: String,
    val artist: String,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val isPlaying: Boolean,
    val artwork: ImageBitmap? = null,
    val supported: Boolean = true,
    /** False when the player reports that it cannot jump to a position (MPRIS CanSeek, GSMTC controls). */
    val canSeek: Boolean = true,
    val canSkip: Boolean = false,
    val canPause: Boolean = false,
)
