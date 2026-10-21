package com.cactuslauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Profile picture of [size] with the same 12 dp rounding as [BuildArtwork];
 * the Minecraft release icon when the profile has no picture.
 */
@Composable
fun ProfileAvatar(name: String, avatar: androidx.compose.ui.graphics.ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (avatar != null) Image(avatar, "Картинка профиля $name", modifier.size(size).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    else BuildArtwork("release", modifier.size(size))
}
