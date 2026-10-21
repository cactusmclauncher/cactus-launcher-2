package com.cactuslauncher.model

import androidx.compose.ui.graphics.ImageBitmap

/** An entry of the build's servers.dat; [index] is its position in the file, [iconData] the stored base64 PNG. */
data class MinecraftServer(
    val index: Int,
    val name: String,
    val ip: String,
    val iconData: String?,
    val icon: ImageBitmap?,
    val hidden: Boolean,
)

/** What a server answered to the status request; [motd] is plain text, [favicon] base64 PNG. */
data class ServerStatus(
    val motd: String,
    val online: Int,
    val max: Int,
    val version: String,
    val pingMillis: Long,
    val favicon: String?,
    val icon: ImageBitmap?,
)
