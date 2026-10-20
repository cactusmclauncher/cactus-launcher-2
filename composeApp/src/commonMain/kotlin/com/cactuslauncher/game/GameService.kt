package com.cactuslauncher.game
import com.cactuslauncher.model.ProfileSnapshot
import com.cactuslauncher.model.AudioMixerSnapshot
import com.cactuslauncher.model.TrackSnapshot
import com.cactuslauncher.model.LauncherUpdate

/** Everything the UI needs from the game core; implemented by GameServiceImpl. */
interface GameService {
    suspend fun installBuild(id: String, parallelDownloads: Int, onProgress: (com.cactuslauncher.model.InstallProgress) -> Unit)
    fun pauseInstallation(paused: Boolean)
    suspend fun launchBuild(id: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean, ghostMode: Boolean): Boolean
    suspend fun signInExternal(apiRoot: String, username: String, password: String): String
    fun signOutExternal()
    fun externalAccountName(): String?
    suspend fun recommendedMemoryGb(): Int
    suspend fun javaOptimizationInfo(): com.cactuslauncher.model.JavaOptimizationInfo
    suspend fun javaOptimizationPlan(buildId: String, memoryGb: Int, automaticHeap: Boolean, automaticOptimization: Boolean): com.cactuslauncher.model.JavaOptimizationPlan
    suspend fun runningBuildIds(): Set<String>
    /** Builds whose game process exited with an error since the last call: build id to a Russian message. */
    suspend fun takeLaunchFailures(): List<Pair<String, String>>
    suspend fun buildLogs(id: String): List<String>
    suspend fun diagnoseBuild(id: String): com.cactuslauncher.model.CrashDiagnosis
    suspend fun launchRecoveryBuild(id: String, excludedMod: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean): Unit
    suspend fun openBuildLogs(id: String)
    suspend fun builds(): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun createBuild(name: String, version: String, loader: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun renameBuild(id: String, name: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun setBuildIcon(id: String, icon: String): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun deleteBuild(id: String, deleteFiles: Boolean = false): List<com.cactuslauncher.model.LauncherBuild>
    suspend fun openBuildFolder(id: String)
    suspend fun installedMods(id: String): List<com.cactuslauncher.model.InstalledMod>
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
}

enum class TrackCommand { PLAY_PAUSE, NEXT, PREVIOUS }
