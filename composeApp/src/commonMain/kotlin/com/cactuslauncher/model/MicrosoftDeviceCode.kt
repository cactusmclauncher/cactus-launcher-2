package com.cactuslauncher.model

data class MicrosoftDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val message: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)
