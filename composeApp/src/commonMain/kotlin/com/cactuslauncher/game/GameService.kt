package com.cactuslauncher.game
import com.cactuslauncher.model.ProfileSnapshot
import com.cactuslauncher.model.AudioMixerSnapshot
import com.cactuslauncher.model.TrackSnapshot
import com.cactuslauncher.model.LauncherUpdate

/** Everything the UI needs from the game core; implemented by GameServiceImpl. */
interface GameService {
    suspend fun installBuild(id: String, parallelDownloads: Int, onProgress: (com.cactuslauncher.model.InstallProgress) -> Unit)
    fun pauseInstallation(paused: Boolean)
    /** [authlibUrl]: the third-party auth server the user signed in to, or null for an offline profile. */
    /** [server]: an address from the build's server list to join right after start, or null for the main menu. */
    suspend fun launchBuild(id: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean, ghostMode: Boolean, server: String? = null): Boolean
    suspend fun signInExternal(apiRoot: String, username: String, password: String): String
    fun signOutExternal()
    fun externalAccountName(): String?
    suspend fun recommendedMemoryGb(): Int
    suspend fun javaOptimizationInfo(): com.cactuslauncher.model.JavaOptimizationInfo
    suspend fun javaOptimizationPlan(buildId: String, memoryGb: Int, automaticHeap: Boolean, automaticOptimization: Boolean): com.cactuslauncher.model.JavaOptimizationPlan
    suspend fun runningBuildIds(): Set<String>
    /** Builds whose game process exited with an error since the last call: build id to a Russian message. */
    suspend fun takeLaunchFailures(): List<Pair<String, String>>
    /** Complete log lines appended after [offset]; pass the returned nextOffset on the next call. */
    suspend fun buildLogChunk(id: String, offset: Long): com.cactuslauncher.model.LogChunk
    /** Records a background failure (stderr and launcher-errors.log); never throws. */
    fun reportError(context: String, error: Throwable)
    suspend fun diagnoseBuild(id: String): com.cactuslauncher.model.CrashDiagnosis
    suspend fun launchRecoveryBuild(id: String, excludedMod: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean): Unit
    suspend fun openBuildLogs(id: String)
    suspend fun builds(): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun createBuild(name: String, version: String, loader: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun renameBuild(id: String, name: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun setBuildIcon(id: String, icon: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun setAutoUpdateMods(id: String, enabled: Boolean): List<com.cactuslauncher.model.LauncherBuild>
    /** Updates available on Modrinth for the build's mods (its loader and Minecraft version). */
    suspend fun modUpdates(id: String): List<com.cactuslauncher.model.ModUpdate>
    /** Installs updates of [fileNames] (null: all) with their new required dependencies; replaced jars are backed up. */
    suspend fun applyModUpdates(id: String, fileNames: Set<String>?): com.cactuslauncher.model.ModUpdateResult
    suspend fun lastModUpdate(id: String): com.cactuslauncher.model.ModUpdateBackup?
    /** Restores the jars replaced by the newest update; returns how many were restored. */
    suspend fun rollbackModUpdate(id: String): Int
    suspend fun deleteBuild(id: String, deleteFiles: Boolean = false): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun openBuildFolder(id: String)
    suspend fun installedMods(id: String): List<com.cactuslauncher.model.InstalledMod>
    /** Offline compatibility check of the build's enabled mods (duplicates, dependencies, loader, Minecraft). */
    suspend fun modProblems(id: String): List<com.cactuslauncher.model.ModProblem>
    /** The Modrinth project for a missing mod (slug from [ModProblem.installId]) if it has a version for the build. */
    suspend fun findModrinthProject(id: String, slug: String): com.cactuslauncher.model.ModrinthProject?
    suspend fun screenshots(id: String): List<com.cactuslauncher.model.Screenshot>
    /** Screenshot and server counts of all builds for the "Остальное" menu. */
    suspend fun otherOverview(): com.cactuslauncher.model.OtherOverview
    /** Downscaled thumbnail, cached in memory and on disk. */
    suspend fun screenshotThumbnail(id: String, shot: com.cactuslauncher.model.Screenshot): androidx.compose.ui.graphics.ImageBitmap?
    /** The screenshot scaled to at most [maxSize] px for the viewer. */
    suspend fun screenshotImage(id: String, shot: com.cactuslauncher.model.Screenshot, maxSize: Int): androidx.compose.ui.graphics.ImageBitmap?
    suspend fun openScreenshot(id: String, fileName: String)
    suspend fun revealScreenshot(id: String, fileName: String)
    suspend fun copyScreenshot(id: String, fileName: String)
    /** Moves the screenshot to the trash when the system has one; returns true if it went to the trash. */
    suspend fun deleteScreenshot(id: String, fileName: String): Boolean
    suspend fun openScreenshotsFolder(id: String)
    /** The build's servers.dat as the game shows it, hidden entries included. */
    suspend fun servers(id: String): List<com.cactuslauncher.model.MinecraftServer>
    /** A Russian message when the name or address cannot be saved, else null. */
    fun validateServer(name: String, address: String): String?
    suspend fun addServer(id: String, name: String, address: String): List<com.cactuslauncher.model.MinecraftServer>
    /** [expectedIp] guards against the game having changed the list since it was read. */
    suspend fun editServer(id: String, index: Int, expectedIp: String, name: String, address: String): List<com.cactuslauncher.model.MinecraftServer>
    suspend fun removeServer(id: String, index: Int, expectedIp: String): List<com.cactuslauncher.model.MinecraftServer>
    /** Saves the icon the server sent in its status, as the game does. */
    suspend fun setServerIcon(id: String, index: Int, expectedIp: String, favicon: String)
    /** Server List Ping with a short timeout; throws when the server does not answer. */
    suspend fun pingServer(address: String): com.cactuslauncher.model.ServerStatus
    suspend fun scanInstalledMod(id: String, fileName: String): com.cactuslauncher.model.ModSecurityReport
    suspend fun searchMods(id: String, query: String, offset: Int): List<com.cactuslauncher.model.ModrinthProject>
    suspend fun installMod(id: String, projectId: String)
    suspend fun removeMod(id: String, fileName: String)
    suspend fun setModEnabled(id: String, fileName: String, enabled: Boolean)
    suspend fun resourcePacks(id: String): List<com.cactuslauncher.model.InstalledResourcePack>
    suspend fun importResourcePack(id: String): Boolean
    suspend fun setResourcePackEnabled(id: String, fileName: String, enabled: Boolean)
    suspend fun openResourcePacksFolder(id: String)
    suspend fun loadPreferences(): com.cactuslauncher.model.LauncherPreferencesSnapshot?
    suspend fun savePreferences(value: com.cactuslauncher.model.LauncherPreferencesSnapshot)
    suspend fun checkLauncherUpdate(): LauncherUpdate
    suspend fun downloadLauncherUpdate(): String
    suspend fun openLauncherUpdate(path: String)
    suspend fun chooseDirectory(current: String): String?
    suspend fun modIcon(url: String): androidx.compose.ui.graphics.ImageBitmap?
    suspend fun translateModDescription(text: String): String
    suspend fun openModsFolder(id: String)
    suspend fun minecraftVersions(): List<com.cactuslauncher.model.MinecraftVersion>
    fun gameDir(): String
    suspend fun audioSessions(): AudioMixerSnapshot
    suspend fun setAudioSessionVolume(id: String, volume: Float)
    suspend fun setAudioSessionMuted(id: String, muted: Boolean)
    suspend fun controlTrack(command: TrackCommand)
    suspend fun currentTrack(): TrackSnapshot?
    suspend fun setPlayerEnabled(enabled: Boolean)
    suspend fun profiles(): ProfileSnapshot
    suspend fun addProfile(name: String): ProfileSnapshot
    suspend fun renameProfile(id: String, name: String): ProfileSnapshot
    suspend fun selectProfile(id: String): ProfileSnapshot
    suspend fun deleteProfile(id: String): ProfileSnapshot
    /** Opens a file dialog and sets the chosen PNG/JPG as the picture of profile [id]; unchanged on cancel. */
    suspend fun chooseProfileAvatar(id: String): ProfileSnapshot
    suspend fun removeProfileAvatar(id: String): ProfileSnapshot
}

enum class TrackCommand { PLAY_PAUSE, NEXT, PREVIOUS }
