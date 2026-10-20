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
)
