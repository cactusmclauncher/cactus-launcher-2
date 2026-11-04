package com.cactuslauncher.game

data class HorizonExit(val exitCode: Int, val logPath: String)

data class HorizonInstallation(
    val release: String,
    val minecraft: String,
    val javaMajor: Int,
    val path: String,
    val minecraftPath: String,
)

data class HorizonProgress(val message: String, val fraction: Float)

data class HorizonUpdate(val installation: HorizonInstallation?, val latest: String, val available: Boolean, val status: String)
