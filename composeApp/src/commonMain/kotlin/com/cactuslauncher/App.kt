package com.cactuslauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.cactuslauncher.model.ProfileSnapshot
import com.cactuslauncher.ui.ProfilesDialog
import com.cactuslauncher.ui.MotionOverlay
import com.cactuslauncher.ui.CactusMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.LauncherSettings
import androidx.compose.ui.Modifier
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.TrackSnapshot
import com.cactuslauncher.model.JavaOptimizationInfo
import com.cactuslauncher.game.TrackCommand
import com.cactuslauncher.model.Dest
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.ModrinthProject
import com.cactuslauncher.model.ModHelperIssue
import com.cactuslauncher.model.ModSecurityReport
import com.cactuslauncher.model.LauncherUpdate
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.cactuslauncher.ui.screens.BuildListScreen
import com.cactuslauncher.theme.AppFontProvider
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.ModHelperDialog
import com.cactuslauncher.ui.NewBuildDialog
import com.cactuslauncher.ui.SettingsDialog
import com.cactuslauncher.ui.InitialSetupDialog
import com.cactuslauncher.ui.Sidebar
import com.cactuslauncher.ui.screens.BuildManageScreen
import com.cactuslauncher.ui.screens.BuildLogsScreen
import com.cactuslauncher.ui.screens.HomeScreen
import com.cactuslauncher.ui.screens.ModsCatalogScreen
import com.cactuslauncher.ui.screens.NewsReader
import com.cactuslauncher.ui.screens.NewsScreen

@Composable
fun App(game: GameService, onGhostMode: () -> Unit) {
    val pageOffset = with(LocalDensity.current) { 6.dp.roundToPx() }
    var profiles by remember { mutableStateOf<ProfileSnapshot?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    var profilesBusy by remember { mutableStateOf(false) }
    var profilesError by remember { mutableStateOf<String?>(null) }
    var externalAccountName by remember { mutableStateOf(game.externalAccountName()) }
    var externalApiRoot by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun profileOperation(operation: suspend () -> ProfileSnapshot) {
        if (profilesBusy) return
        profilesBusy = true
        profilesError = null
        scope.launch {
            try { profiles = operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { profilesError = e.message ?: "Не удалось сохранить профили." }
            finally { profilesBusy = false }
        }
    }
    LaunchedEffect(Unit) { profileOperation { game.profiles() } }
    val preferences = remember { LauncherSettings(game.gameDir()) }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (preferences.theme) { "dark" -> true; "light" -> false; else -> systemDark }
    SideEffect { C.dark = darkTheme }
    var settingsReady by remember { mutableStateOf(false) }
    var firstSetupOpen by remember { mutableStateOf(false) }
    var settingsLoadFailed by remember { mutableStateOf(false) }
    var preferencesError by remember { mutableStateOf<String?>(null) }
    var launcherUpdate by remember { mutableStateOf<LauncherUpdate?>(null) }
    var launcherUpdateFile by remember { mutableStateOf<String?>(null) }
    var launcherUpdateStatus by remember { mutableStateOf<String?>(null) }
    var launcherUpdateBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val saved = game.loadPreferences()
            if (saved == null) firstSetupOpen = true
            else {
                preferences.restore(saved)
                firstSetupOpen = !saved.setupComplete
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settingsLoadFailed = true
            preferencesError = e.message ?: "Не удалось прочитать настройки."
            firstSetupOpen = true
        } finally {
            settingsReady = true
        }
    }
    LaunchedEffect(settingsReady, settingsLoadFailed, preferencesError, preferences.snapshot(!firstSetupOpen)) {
        if (!settingsReady || settingsLoadFailed) return@LaunchedEffect
        try {
            game.savePreferences(preferences.snapshot(!firstSetupOpen))
            preferencesError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preferencesError = e.message ?: "Не удалось сохранить настройки."
        }
    }
    suspend fun checkLauncherUpdates(download: Boolean) {
        if (launcherUpdateBusy) return
        launcherUpdateBusy = true
        launcherUpdateStatus = "Проверяем обновления…"
        try {
            val result = game.checkLauncherUpdate()
            launcherUpdate = result
            launcherUpdateFile = null
            if (!result.available) {
                launcherUpdateStatus = "Установлена последняя версия."
            } else if (download) {
                launcherUpdateStatus = "Скачиваем версию ${result.latestVersion}…"
                launcherUpdateFile = game.downloadLauncherUpdate()
                launcherUpdateStatus = "Версия ${result.latestVersion} готова к установке."
            } else {
                launcherUpdateStatus = "Доступна версия ${result.latestVersion}."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            launcherUpdateStatus = e.message ?: "Не удалось проверить обновления."
        } finally {
            launcherUpdateBusy = false
        }
    }
    LaunchedEffect(settingsReady, settingsLoadFailed, preferences.autoUpdate) {
        if (settingsReady && !settingsLoadFailed && preferences.autoUpdate) checkLauncherUpdates(download = true)
    }
    LaunchedEffect(settingsReady, preferences.automaticMemory) {
        if (!settingsReady) return@LaunchedEffect
        if (preferences.automaticMemory) {
            try {
                val recommended = game.recommendedMemoryGb()
                if (preferences.automaticMemory) preferences.memoryGb = recommended
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the safe default if the platform cannot report physical memory.
            }
        }
    }
    val builds = remember { mutableStateListOf<LauncherBuild>() }
    val javaPlanRevisions = remember { mutableStateMapOf<String, Int>() }
    var selectedModsBuildId by remember { mutableStateOf<String?>(null) }
    var modsCatalogActive by remember { mutableStateOf(false) }
    var installedMods by remember { mutableStateOf<List<com.cactuslauncher.model.InstalledMod>>(emptyList()) }
    var installedModsBuildId by remember { mutableStateOf<String?>(null) }
    var resourcePacks by remember { mutableStateOf<List<com.cactuslauncher.model.InstalledResourcePack>>(emptyList()) }
    var resourcePacksLoading by remember { mutableStateOf(false) }
    var resourcePacksError by remember { mutableStateOf<String?>(null) }
    var modrinthProjects by remember { mutableStateOf<List<com.cactuslauncher.model.ModrinthProject>>(emptyList()) }
    val modIcons = remember { mutableStateMapOf<String, ImageBitmap>() }
    val modSecurityReports = remember { mutableStateMapOf<String, ModSecurityReport>() }
    val scanningModFiles = remember { mutableStateListOf<String>() }
    val modScanSemaphore = remember { Semaphore(2) }
    val translatedModDescriptions = remember { mutableStateMapOf<String, String>() }
    val failedTranslationIds = remember { mutableStateListOf<String>() }
    var modSearch by remember { mutableStateOf("") }
    var modActionId by remember { mutableStateOf<String?>(null) }
    var modsLoading by remember { mutableStateOf(false) }
    var modCatalogLoading by remember { mutableStateOf(false) }
    var modsError by remember { mutableStateOf<String?>(null) }
    var modHelperIssue by remember { mutableStateOf<ModHelperIssue?>(null) }
    var modHelperOpen by remember { mutableStateOf(false) }
    var modsReloadKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var modCatalogReloadKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var modCatalogOffset by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var modCatalogHasMore by remember { mutableStateOf(false) }
    var modCatalogLoadingMore by remember { mutableStateOf(false) }
    var modCatalogMoreError by remember { mutableStateOf<String?>(null) }
    var modCatalogGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val modInstallQueue = remember { mutableStateListOf<Pair<String, ModrinthProject>>() }
    val modInstallErrors = remember { mutableStateMapOf<String, String>() }
    var activeModInstall by remember { mutableStateOf<Pair<String, ModrinthProject>?>(null) }
    var modInstallWorkerRunning by remember { mutableStateOf(false) }
    fun enqueueModInstall(buildId: String, project: ModrinthProject) {
        val key = "$buildId:${project.id}"
        if (installedModsBuildId == buildId && installedMods.any { it.projectId == project.id }) return
        if (activeModInstall?.let { it.first == buildId && it.second.id == project.id } == true ||
            modInstallQueue.any { it.first == buildId && it.second.id == project.id }) return
        modInstallErrors.remove(key)
        modInstallQueue.add(buildId to project)
        if (modInstallWorkerRunning) return
        modInstallWorkerRunning = true
        scope.launch {
            try {
                while (modInstallQueue.isNotEmpty()) {
                    val (targetBuildId, nextProject) = modInstallQueue.removeAt(0)
                    activeModInstall = targetBuildId to nextProject
                    val requestKey = "$targetBuildId:${nextProject.id}"
                    try {
                        game.installMod(targetBuildId, nextProject.id)
                        javaPlanRevisions[targetBuildId] = (javaPlanRevisions[targetBuildId] ?: 0) + 1
                        if (selectedModsBuildId == targetBuildId) {
                            installedMods = game.installedMods(targetBuildId)
                                            installedModsBuildId = targetBuildId
                            modsReloadKey++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: "Не удалось установить мод."
                        modInstallErrors[requestKey] = message
                        val normalized = message.lowercase()
                        val incompatible = normalized.contains("нет совместимой версии мода или зависимости") ||
                            normalized.contains("версия мода несовместима с minecraft или загрузчиком") ||
                            normalized.contains("конфликт модов:")
                        val build = builds.firstOrNull { it.id == targetBuildId }
                        if (incompatible && build != null) {
                            val dependencyId = Regex("(?:зависимости|зависимость):\\s*([A-Za-z0-9]{3,64})", RegexOption.IGNORE_CASE)
                                .find(message)?.groupValues?.getOrNull(1)?.takeUnless { it == nextProject.id }
                            modHelperIssue = ModHelperIssue(
                                projectTitle = nextProject.title,
                                buildName = build.name,
                                minecraftVersion = build.version,
                                loader = build.loader,
                                message = message,
                                dependencyId = dependencyId,
                                conflict = normalized.contains("конфликт модов:"),
                            )
                            modHelperOpen = true
                        }
                    }
                    activeModInstall = null
                }
            } finally {
                activeModInstall = null
                modInstallWorkerRunning = false
            }
        }
    }
    var selectedBuild by remember { mutableStateOf<LauncherBuild?>(null) }
    var selectedBuildJavaPlan by remember { mutableStateOf<com.cactuslauncher.model.JavaOptimizationPlan?>(null) }
    var selectedBuildJavaPlanLoading by remember { mutableStateOf(false) }
    LaunchedEffect(selectedBuild?.id) {
        val id = selectedBuild?.id
        if (id == null) {
            resourcePacks = emptyList()
            resourcePacksError = null
            return@LaunchedEffect
        }
        resourcePacks = emptyList()
        resourcePacksLoading = true
        resourcePacksError = null
        try { resourcePacks = game.resourcePacks(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось прочитать ресурспаки." }
        finally { resourcePacksLoading = false }
    }
    LaunchedEffect(selectedBuild?.id, selectedBuild?.version, selectedBuild?.loader,
        selectedBuild?.let { javaPlanRevisions[it.id] },
        preferences.memoryGb, preferences.automaticMemory, preferences.autoJavaOptimization) {
        val build = selectedBuild
        if (build == null) {
            selectedBuildJavaPlan = null
            selectedBuildJavaPlanLoading = false
        } else {
            delay(180)
            selectedBuildJavaPlan = null
            selectedBuildJavaPlanLoading = true
            try {
                selectedBuildJavaPlan = game.javaOptimizationPlan(
                    build.id, preferences.memoryGb, preferences.automaticMemory, preferences.autoJavaOptimization,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                selectedBuildJavaPlan = null
            } finally {
                selectedBuildJavaPlanLoading = false
            }
        }
    }
    var logsBuildId by remember { mutableStateOf<String?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logsLoading by remember { mutableStateOf(false) }
    var logsError by remember { mutableStateOf<String?>(null) }
    var crashDiagnosis by remember { mutableStateOf<com.cactuslauncher.model.CrashDiagnosis?>(null) }
    var diagnosisLoading by remember { mutableStateOf(false) }
    var recoveryBusy by remember { mutableStateOf(false) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var runningBuildIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var buildsBusy by remember { mutableStateOf(false) }
    var buildsError by remember { mutableStateOf<String?>(null) }
    var buildsLoaded by remember { mutableStateOf(false) }
    fun buildOperation(operation: suspend () -> List<LauncherBuild>, onSuccess: () -> Unit = {}) {
        if (buildsBusy) return
        buildsBusy = true
        buildsError = null
        scope.launch {
            try {
                val updated = operation()
                val selectedId = selectedBuild?.id
                builds.clear()
                builds.addAll(updated)
                selectedBuild = updated.find { it.id == selectedId }
                buildsLoaded = true
                buildsBusy = false
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                buildsError = e.message ?: "Не удалось сохранить сборки."
            } finally {
                buildsBusy = false
            }
        }
    }
    LaunchedEffect(Unit) { buildOperation({ game.builds() }) }
    var dest by remember { mutableStateOf(Dest.Home) }
    LaunchedEffect(dest, buildsLoaded, builds.map { it.id }) {
        if (dest == Dest.Mods && buildsLoaded && builds.none { it.id == selectedModsBuildId }) {
            selectedModsBuildId = builds.firstOrNull()?.id
        }
    }
    LaunchedEffect(dest, buildsLoaded, selectedModsBuildId, modsReloadKey) {
        if (dest != Dest.Mods || !buildsLoaded) return@LaunchedEffect
        val id = selectedModsBuildId
        if (id == null) {
            installedMods = emptyList()
            installedModsBuildId = null
            modrinthProjects = emptyList()
            modsLoading = false
            modsError = null
            return@LaunchedEffect
        }
        installedMods = emptyList()
        installedModsBuildId = null
        modsLoading = true
        modsError = null
        try {
            installedMods = game.installedMods(id)
            installedModsBuildId = id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modsError = e.message ?: "Не удалось прочитать папку модов."
        } finally {
            modsLoading = false
        }
    }
    LaunchedEffect(installedModsBuildId, installedMods.map { it.fileName }, modsReloadKey, preferences.modAntivirusEnabled) {
        val buildId = installedModsBuildId ?: return@LaunchedEffect
        if (buildId != selectedModsBuildId) return@LaunchedEffect
        if (!preferences.modAntivirusEnabled) return@LaunchedEffect
        val validKeys = installedMods.mapTo(hashSetOf()) { "$buildId:${it.fileName}" }
        modSecurityReports.keys.toList()
            .filter { it.startsWith("$buildId:") && it !in validKeys }
            .forEach { modSecurityReports.remove(it) }
        modSecurityReports.keys.toList()
            .filter { it.startsWith("$buildId:") && modSecurityReports[it]?.level == "error" }
            .forEach { modSecurityReports.remove(it) }
        // Scans run in the screen scope: installing a mod changes this effect's keys,
        // and a restart would cancel a scan halfway.
        installedMods.filter { it.enabled }.forEach { mod ->
                val key = "$buildId:${mod.fileName}"
                if (modSecurityReports.containsKey(key) || key in scanningModFiles) return@forEach
                scanningModFiles.add(key)
                scope.launch {
                    try {
                        modScanSemaphore.withPermit {
                            modSecurityReports[key] = game.scanInstalledMod(buildId, mod.fileName)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        modSecurityReports[key] = ModSecurityReport("error", listOf(e.message ?: "Не удалось проверить JAR."), 0)
                    } finally {
                        scanningModFiles.remove(key)
                    }
                }
            }
    }
    LaunchedEffect(dest, buildsLoaded, selectedModsBuildId, modSearch, modCatalogReloadKey, modsCatalogActive) {
        if (dest != Dest.Mods || !modsCatalogActive || !buildsLoaded) {
            modCatalogLoading = false
            return@LaunchedEffect
        }
        val id = selectedModsBuildId ?: return@LaunchedEffect
        val build = builds.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (build.loader.equals("Vanilla", ignoreCase = true)) {
            modrinthProjects = emptyList()
            modCatalogHasMore = false
            modCatalogLoading = false
            return@LaunchedEffect
        }
        val requestGeneration = modCatalogGeneration + 1
        modCatalogGeneration = requestGeneration
        modrinthProjects = emptyList()
        modCatalogOffset = 0
        modCatalogHasMore = false
        modCatalogLoadingMore = false
        modCatalogMoreError = null
        modCatalogLoading = true
        modsError = null
        try {
            delay(350)
            val page = game.searchMods(id, modSearch, 0)
            if (modCatalogGeneration == requestGeneration && selectedModsBuildId == id) {
                modrinthProjects = page
                modCatalogOffset = page.size
                modCatalogHasMore = page.size == 30 && modCatalogOffset < 10000
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modsError = e.message ?: "Не удалось загрузить каталог Modrinth."
        } finally {
            if (modCatalogGeneration == requestGeneration) modCatalogLoading = false
        }
    }
    fun loadMoreModProjects() {
        val id = selectedModsBuildId ?: return
        if (dest != Dest.Mods || !modsCatalogActive || modCatalogLoading || modCatalogLoadingMore ||
            !modCatalogHasMore) return
        val query = modSearch
        val offset = modCatalogOffset
        val generation = modCatalogGeneration
        modCatalogLoadingMore = true
        modCatalogMoreError = null
        scope.launch {
            try {
                val page = game.searchMods(id, query, offset)
                if (modCatalogGeneration == generation && selectedModsBuildId == id && modSearch == query &&
                    dest == Dest.Mods && modsCatalogActive) {
                    val knownIds = modrinthProjects.mapTo(hashSetOf()) { it.id }
                    modrinthProjects = modrinthProjects + page.filterNot { it.id in knownIds }
                    modCatalogOffset += page.size
                    modCatalogHasMore = page.size == 30 && modCatalogOffset < 10000
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (modCatalogGeneration == generation) modCatalogMoreError = e.message ?: "Не удалось загрузить следующую страницу."
            } finally {
                if (modCatalogGeneration == generation) modCatalogLoadingMore = false
            }
        }
    }
    LaunchedEffect(dest, modsCatalogActive, modrinthProjects.map { it.id to it.iconUrl }) {
        if (dest != Dest.Mods || !modsCatalogActive) return@LaunchedEffect
        val visibleIds = modrinthProjects.mapTo(hashSetOf()) { it.id }
        modIcons.keys.toList().filterNot { it in visibleIds }.forEach { modIcons.remove(it) }
        val icons = modrinthProjects.filter { it.iconUrl.isNotBlank() && !modIcons.containsKey(it.id) }
        val semaphore = Semaphore(4)
        coroutineScope {
            icons.forEach { project -> launch {
                semaphore.withPermit {
                    try { game.modIcon(project.iconUrl)?.let { modIcons[project.id] = it } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                }
            } }
        }
    }
    LaunchedEffect(dest, modsCatalogActive, preferences.translateModDescriptions, modrinthProjects.map { it.id to it.description }) {
        if (dest != Dest.Mods || !modsCatalogActive) return@LaunchedEffect
        if (!preferences.translateModDescriptions) {
            translatedModDescriptions.clear()
            failedTranslationIds.clear()
            return@LaunchedEffect
        }
        val visibleIds = modrinthProjects.mapTo(hashSetOf()) { it.id }
        translatedModDescriptions.keys.toList().filterNot { it in visibleIds }.forEach { translatedModDescriptions.remove(it) }
        val semaphore = Semaphore(3)
        coroutineScope {
            modrinthProjects.filter { it.description.isNotBlank() }.forEach { project -> launch {
                if (translatedModDescriptions.containsKey(project.id)) return@launch
                failedTranslationIds.remove(project.id)
                semaphore.withPermit {
                    try { translatedModDescriptions[project.id] = game.translateModDescription(project.description) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failedTranslationIds.add(project.id) }
                }
            } }
        }
    }
    LaunchedEffect(dest, buildsLoaded) {
        if (dest == Dest.Builds && buildsLoaded) {
            while (isActive) {
                try { runningBuildIds = game.runningBuildIds() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                delay(1000)
            }
        }
    }
    LaunchedEffect(logsBuildId) {
        val id = logsBuildId ?: return@LaunchedEffect
        logsLoading = true
        logsError = null
        diagnosisLoading = true
        crashDiagnosis = null
        recoveryMessage = null
        try { crashDiagnosis = game.diagnoseBuild(id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { crashDiagnosis = null }
        finally { diagnosisLoading = false }
        try {
            while (isActive && logsBuildId == id) {
                logLines = game.buildLogs(id)
                logsLoading = false
                delay(1500)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logsError = e.message ?: "Не удалось прочитать файл логов."
            logsLoading = false
        }
    }
    var readingNews by remember { mutableStateOf(false) }
    var readingBeta by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var javaOptimizationInfo by remember { mutableStateOf<JavaOptimizationInfo?>(null) }
    LaunchedEffect(settingsOpen, selectedBuild?.id) {
        if (settingsOpen || selectedBuild != null) {
            try {
                val info = game.javaOptimizationInfo()
                javaOptimizationInfo = info
                if (preferences.memoryGb > info.maxMemoryGb) preferences.memoryGb = info.maxMemoryGb
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { javaOptimizationInfo = null }
        }
    }
    var newBuildOpen by remember { mutableStateOf(false) }
    var playerExpanded by remember { mutableStateOf(false) }
    var currentTrack by remember { mutableStateOf<TrackSnapshot?>(null) }
    var trackError by remember { mutableStateOf<String?>(null) }
    var trackCommandBusy by remember { mutableStateOf(false) }
    LaunchedEffect(preferences.showPlayer) {
        if (!preferences.showPlayer) {
            currentTrack = null
            trackError = null
            playerExpanded = false
        }
        game.setPlayerEnabled(preferences.showPlayer)
    }
    LaunchedEffect(preferences.showPlayer) {
        if (preferences.showPlayer) {
            // Let the first frame and profile loading complete before starting Swift.
            delay(600)
            while (isActive) {
                try {
                    val track = game.currentTrack()
                    if (preferences.showPlayer && !trackCommandBusy) {
                        currentTrack = track
                        trackError = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    trackError = e.message ?: "Не удалось получить информацию о треке."
                }
                delay(if (currentTrack != null) 500 else 2000)
            }
        }
    }
    LaunchedEffect(currentTrack) {
        if (currentTrack == null) playerExpanded = false
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var launchFailure by remember { mutableStateOf<Pair<String, String>?>(null) }
    suspend fun refreshBuilds() {
        try {
            val updated = game.builds()
            val selectedId = selectedBuild?.id
            builds.clear()
            builds.addAll(updated)
            selectedBuild = updated.find { it.id == selectedId }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val failures = game.takeLaunchFailures()
                if (failures.isNotEmpty()) {
                    launchFailure = failures.last()
                    runningBuildIds = game.runningBuildIds()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            delay(1500)
        }
    }
    var installBuild by remember { mutableStateOf<LauncherBuild?>(null) }
    var installProgress by remember { mutableStateOf(com.cactuslauncher.model.InstallProgress()) }
    var installBusy by remember { mutableStateOf(false) }
    var installPaused by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun startInstall(build: LauncherBuild) {
        if (installBusy || buildsBusy) return
        installBuild = build
        installBusy = true
        installPaused = false
        buildsBusy = true
        installError = null
        installProgress = com.cactuslauncher.model.InstallProgress()
        installJob = scope.launch {
            val updates = kotlinx.coroutines.channels.Channel<com.cactuslauncher.model.InstallProgress>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val collector = launch { for (update in updates) installProgress = update }
            try {
                game.installBuild(build.id, preferences.parallelDownloads) { updates.trySend(it) }
                updates.close()
                collector.join()
                val refreshed = game.builds()
                builds.clear()
                builds.addAll(refreshed)
                selectedBuild = refreshed.find { it.id == selectedBuild?.id }
                installProgress = installProgress.copy(fraction = 1f)
            } catch (e: CancellationException) {
                installError = "Скачивание отменено. Уже скачанные файлы пригодятся в следующий раз."
                throw e
            } catch (e: Exception) {
                installError = e.message ?: "Не удалось скачать версию."
            } finally {
                updates.close()
                collector.cancel()
                installBusy = false
                buildsBusy = false
            }
        }
    }
    fun playBuild(build: LauncherBuild) {
        if (buildsBusy || build.id in runningBuildIds) return
        if (!build.installed) { startInstall(build); return }
        buildsBusy = true
        scope.launch {
            try {
                val enteredGhostMode = game.launchBuild(
                    build.id, preferences.memoryGb, externalApiRoot.takeIf { externalAccountName != null },
                    preferences.automaticMemory, preferences.autoJavaOptimization, preferences.ghostMode,
                )
                if (enteredGhostMode) {
                    onGhostMode()
                    return@launch
                }
                runningBuildIds = game.runningBuildIds()
                notice = "Процесс Minecraft запущен."
                launchFailure = null
                refreshBuilds()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                launchFailure = build.id to (e.message ?: "Не удалось запустить Minecraft.")
                refreshBuilds()
            }
            finally { buildsBusy = false }
        }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }

    val selectedInstalledMods = if (installedModsBuildId == selectedModsBuildId) installedMods else emptyList()
    val securityReportsByProject = selectedInstalledMods.mapNotNull { mod ->
        mod.projectId.takeIf(String::isNotBlank)?.let { projectId ->
            modSecurityReports["${selectedModsBuildId}:${mod.fileName}"]?.let { projectId to it }
        }
    }.toMap()
    val scanningSecurityProjects = selectedInstalledMods.filter {
        "${selectedModsBuildId}:${it.fileName}" in scanningModFiles
    }.mapNotNull { it.projectId.takeIf(String::isNotBlank) }.toSet()

    AppFontProvider {
        Box(Modifier.fillMaxSize().background(C.window)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    current = dest,
                    onSelect = { next ->
                        readingNews = false
                        dest = next
                        if (next == Dest.Builds) selectedBuild = null
                    },
                    onOpenSettings = { settingsOpen = true },
                    currentTrack = currentTrack.takeIf { preferences.showPlayer },
                    trackError = trackError.takeIf { preferences.showPlayer },
                    onTrackCommand = { command ->
                        if (preferences.showPlayer && !trackCommandBusy) {
                            trackCommandBusy = true
                            val previousTrack = currentTrack
                            if (command == TrackCommand.PLAY_PAUSE) {
                                currentTrack = currentTrack?.let { it.copy(isPlaying = !it.isPlaying) }
                            }
                            scope.launch {
                                try {
                                    game.controlTrack(command)
                                    delay(150)
                                    val track = game.currentTrack()
                                    if (preferences.showPlayer) currentTrack = track
                                    trackError = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    if (preferences.showPlayer) {
                                        currentTrack = previousTrack
                                        trackError = e.message ?: "Не удалось управлять текущим треком."
                                    }
                                } finally {
                                    trackCommandBusy = false
                                }
                            }
                        }
                    },
                    playerExpanded = playerExpanded,
                    onTogglePlayer = { playerExpanded = !playerExpanded },
                    profileName = profiles?.active?.name ?: "Профиль",
                    onOpenProfiles = { profilesOpen = true },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (readingNews) {
                        NewsReader(onBack = { readingNews = false }, beta = readingBeta)
                    } else {
                    AnimatedContent(
                        targetState = Triple(dest, selectedBuild, logsBuildId),
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(180, easing = CactusMotion.easeOut)) +
                                slideInVertically(tween(200, easing = CactusMotion.easeOut)) { pageOffset })
                                .togetherWith(fadeOut(tween(90)))
                        }, label = "navigation",
                    ) { (route, buildDetail, logsId) ->
                        when (route) {
                            Dest.Home -> HomeScreen(builds = builds, onOpenBuild = { dest = Dest.Builds })
                             Dest.Builds -> if (buildDetail == null) BuildListScreen(
                                  builds = builds,
                                  loading = !buildsLoaded && buildsBusy,
                                  error = buildsError,
                                  runningBuildIds = runningBuildIds,
                                  onRetry = { buildOperation({ game.builds() }) },
                                 onCreate = { newBuildOpen = true },
                                 onOpen = { logsBuildId = null; selectedBuild = it },
                                  onPlay = { build -> playBuild(build) },
                              ) else if (logsId == buildDetail.id) BuildLogsScreen(
                                  build = buildDetail,
                                  lines = logLines,
                                  loading = logsLoading,
                                  error = logsError,
                                  diagnosis = crashDiagnosis,
                                  diagnosisLoading = diagnosisLoading,
                                  recoveryBusy = recoveryBusy,
                                  recoveryMessage = recoveryMessage,
                                  onBack = { logsBuildId = null },
                                  onTryRecovery = { modFile ->
                                      if (recoveryBusy || buildDetail.id in runningBuildIds) return@BuildLogsScreen
                                      recoveryBusy = true
                                      recoveryMessage = null
                                      scope.launch {
                                          try {
                                              game.launchRecoveryBuild(
                                                  buildDetail.id, modFile, preferences.memoryGb,
                                                  externalApiRoot.takeIf { externalAccountName != null },
                                                  preferences.automaticMemory, preferences.autoJavaOptimization,
                                              )
                                              recoveryMessage = "Пробный запуск идёт без этого мода. Сборка и миры не менялись."
                                          } catch (e: CancellationException) { throw e }
                                          catch (e: Exception) { recoveryMessage = e.message ?: "Пробный запуск не удался." }
                                          finally { recoveryBusy = false }
                                      }
                                  },
                                  onOpenFile = {
                                      scope.launch {
                                          try { game.openBuildLogs(buildDetail.id) }
                                          catch (e: CancellationException) { throw e }
                                          catch (e: Exception) { logsError = e.message ?: "Не удалось открыть файл логов." }
                                      }
                                  },
                              ) else BuildManageScreen(
                                  build = buildDetail,
                                  busy = buildsBusy,
                                  running = buildDetail.id in runningBuildIds,
                                  memoryGb = preferences.memoryGb,
                                  automaticMemory = preferences.automaticMemory,
                                  javaPlan = selectedBuildJavaPlan,
                                  javaPlanLoading = selectedBuildJavaPlanLoading,
                                  javaOptimizationInfo = javaOptimizationInfo,
                                   resourcePacks = resourcePacks,
                                   resourcePacksLoading = resourcePacksLoading,
                                   resourcePacksError = resourcePacksError,
                                   onImportResourcePack = {
                                       if (!buildsBusy) scope.launch {
                                           buildsBusy = true
                                           resourcePacksError = null
                                           try {
                                               if (game.importResourcePack(buildDetail.id)) resourcePacks = game.resourcePacks(buildDetail.id)
                                           } catch (e: CancellationException) { throw e }
                                           catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось добавить ресурспак." }
                                           finally { buildsBusy = false }
                                       }
                                   },
                                   onToggleResourcePack = { pack, enabled ->
                                       if (!buildsBusy) scope.launch {
                                           buildsBusy = true
                                           resourcePacksError = null
                                           try {
                                               game.setResourcePackEnabled(buildDetail.id, pack.fileName, enabled)
                                               resourcePacks = game.resourcePacks(buildDetail.id)
                                           } catch (e: CancellationException) { throw e }
                                           catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось изменить ресурспак." }
                                           finally { buildsBusy = false }
                                       }
                                   },
                                   onOpenResourcePacks = {
                                       scope.launch {
                                           try { game.openResourcePacksFolder(buildDetail.id) }
                                           catch (e: CancellationException) { throw e }
                                           catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось открыть папку ресурспаков." }
                                       }
                                   },
                                    onRefreshResourcePacks = {
                                        scope.launch {
                                            resourcePacksLoading = true
                                            resourcePacksError = null
                                            try { resourcePacks = game.resourcePacks(buildDetail.id) }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось обновить список ресурспаков." }
                                            finally { resourcePacksLoading = false }
                                        }
                                    },
                                  onMemoryChange = { preferences.memoryGb = it; preferences.automaticMemory = false },
                                  onAutomaticMemoryChange = { preferences.automaticMemory = it },
                                  onIcon = { icon -> buildOperation({ game.setBuildIcon(buildDetail.id, icon) }) },
                                  error = buildsError,
                                  onRename = { name -> buildOperation({ game.renameBuild(buildDetail.id, name) }) },
                                  onDelete = { deleteFiles -> buildOperation({ game.deleteBuild(buildDetail.id, deleteFiles) }) },
                                  onOpenFolder = {
                                      scope.launch {
                                          try { game.openBuildFolder(buildDetail.id) }
                                          catch (e: CancellationException) { throw e }
                                          catch (e: Exception) { buildsError = e.message ?: "Не удалось открыть папку." }
                                      }
                                  },
                                 onBack = { selectedBuild = null },
                                 onOpenLogs = { logsBuildId = buildDetail.id },
                                 onOpenMods = { selectedModsBuildId = buildDetail.id; dest = Dest.Mods },
                                 onPlay = {
                                      playBuild(buildDetail)
                                },
                                onNewBuild = { newBuildOpen = true },
                            )
                            Dest.Mods -> ModsCatalogScreen(
                                builds = builds,
                                selectedBuildId = selectedModsBuildId,
                                installedMods = selectedInstalledMods,
                                projects = modrinthProjects,
                                query = modSearch,
                                actionId = modActionId,
                                loadingCatalog = modCatalogLoading,
                                icons = modIcons,
                                translatedDescriptions = translatedModDescriptions,
                                failedTranslationIds = failedTranslationIds.toSet(),
                                showTranslatedDescriptions = preferences.translateModDescriptions,
                                hasMore = modCatalogHasMore,
                                loadingMore = modCatalogLoadingMore,
                                loadMoreError = modCatalogMoreError,
                                installedProjectIds = (if (installedModsBuildId == selectedModsBuildId) installedMods else emptyList())
                                    .mapNotNull { it.projectId.takeIf(String::isNotBlank) }.toSet(),
                                securityEnabled = preferences.modAntivirusEnabled,
                                securityReportsByProject = securityReportsByProject,
                                scanningSecurityProjects = scanningSecurityProjects,
                                queuedProjectIds = modInstallQueue.filter { it.first == selectedModsBuildId }.map { it.second.id },
                                activeInstallProjectId = activeModInstall?.takeIf { it.first == selectedModsBuildId }?.second?.id,
                                installErrors = modInstallErrors.filterKeys { it.startsWith("$selectedModsBuildId:") }
                                    .mapKeys { it.key.substringAfter(':') },
                                installQueueCount = modInstallQueue.count { it.first == selectedModsBuildId } +
                                    if (activeModInstall?.first == selectedModsBuildId) 1 else 0,
                                onCatalogSelected = { modsCatalogActive = it },
                                loadingBuilds = !buildsLoaded && buildsBusy,
                                loadingMods = modsLoading,
                                error = modsError,
                                onSelectBuild = { selectedModsBuildId = it.id },
                                onQueryChange = { modSearch = it },
                                onLoadMore = { loadMoreModProjects() },
                                onInstall = { project ->
                                    val id = selectedModsBuildId
                                    if (id != null) enqueueModInstall(id, project)
                                },
                                onRemove = { mod ->
                                    val id = selectedModsBuildId
                                    if (id != null && modActionId == null) scope.launch {
                                        modActionId = mod.fileName
                                        modsError = null
                                        try {
                                            game.removeMod(id, mod.fileName)
                                            installedMods = game.installedMods(id)
                                            installedModsBuildId = id
                                            javaPlanRevisions[id] = (javaPlanRevisions[id] ?: 0) + 1
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { modsError = e.message ?: "Не удалось удалить мод." }
                                        finally { modActionId = null }
                                    }
                                },
                                onToggleMod = { mod, enabled ->
                                    val id = selectedModsBuildId
                                    if (id != null && modActionId == null) scope.launch {
                                        modActionId = mod.fileName
                                        modsError = null
                                        try {
                                            game.setModEnabled(id, mod.fileName, enabled)
                                            installedMods = game.installedMods(id)
                                            installedModsBuildId = id
                                            javaPlanRevisions[id] = (javaPlanRevisions[id] ?: 0) + 1
                                        } catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { modsError = e.message ?: "Не удалось изменить состояние мода." }
                                        finally { modActionId = null }
                                    }
                                },
                                onRefresh = {
                                    modsReloadKey++
                                    modCatalogReloadKey++
                                    selectedModsBuildId?.let { id -> javaPlanRevisions[id] = (javaPlanRevisions[id] ?: 0) + 1 }
                                },
                                onOpenFolder = {
                                    val id = selectedModsBuildId
                                    if (id != null) scope.launch {
                                        try { game.openModsFolder(id) }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { modsError = e.message ?: "Не удалось открыть папку модов." }
                                    }
                                },
                                onOpenBuilds = { dest = Dest.Builds },
                            )
                            Dest.News -> NewsScreen(
                                onOpenArticle = { readingBeta = false; readingNews = true },
                                onOpenBeta = { readingBeta = true; readingNews = true },
                            )
                        }
                    }
                    }
                }
            }

            MotionOverlay(newBuildOpen) {
                NewBuildDialog(
                    game = game,
                    busy = buildsBusy,
                    saveError = buildsError,
                    onDismiss = { newBuildOpen = false },
                    onCreate = { name, version, loader ->
                        buildOperation({ game.createBuild(name, version, loader) }, {
                            newBuildOpen = false
                            selectedBuild = null
                            dest = Dest.Builds
                            builds.lastOrNull()?.let { startInstall(it) }
                        })
                    },
                )
            }
            MotionOverlay(settingsOpen) {
                SettingsDialog(
                    preferences,
                    javaOptimizationInfo = javaOptimizationInfo,
                    updateStatus = launcherUpdateStatus,
                    update = launcherUpdate,
                    updateDownloaded = launcherUpdateFile != null,
                    updateBusy = launcherUpdateBusy,
                    onCheckUpdates = { scope.launch { checkLauncherUpdates(download = false) } },
                    onDownloadUpdate = { scope.launch { checkLauncherUpdates(download = true) } },
                    onOpenUpdate = { launcherUpdateFile?.let { path -> scope.launch {
                        try { game.openLauncherUpdate(path) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { launcherUpdateStatus = e.message ?: "Не удалось открыть установщик." }
                    } } },
                    onDismiss = { settingsOpen = false },
                    onReset = {
                        preferences.reset(game.gameDir())
                        settingsLoadFailed = false
                        preferencesError = null
                    },
                    onRestartSetup = {
                        settingsOpen = false
                        firstSetupOpen = true
                    },
                    error = preferencesError,
                )
            }
            MotionOverlay(firstSetupOpen) {
                InitialSetupDialog(
                    preferences,
                    javaOptimizationInfo,
                    preferencesError,
                    initialNickname = profiles?.active?.name ?: "Nick",
                    onChooseFolder = { current -> game.chooseDirectory(current) },
                    onComplete = { nickname ->
                        if (nickname != null) {
                            val snapshot = game.profiles()
                            val active = snapshot.active
                            profiles = when {
                                active == null -> game.addProfile(nickname)
                                active.name != nickname -> game.renameProfile(active.id, nickname)
                                else -> snapshot
                            }
                        }
                        firstSetupOpen = false
                    },
                )
            }
            MotionOverlay(profilesOpen) { ProfilesDialog(
                snapshot = profiles, busy = profilesBusy, error = profilesError,
                onDismiss = { profilesOpen = false },
                onRetry = { profileOperation { game.profiles() } },
                onAdd = { name -> profileOperation { game.addProfile(name) } },
                onRename = { id, name -> profileOperation { game.renameProfile(id, name) } },
                onSelect = { id -> profileOperation { game.selectProfile(id) } },
                onDelete = { id -> profileOperation { game.deleteProfile(id) } },
                externalAccountName = externalAccountName,
                advancedMode = preferences.advancedMode,
                onSignInExternal = { apiRoot, username, password ->
                    if (!profilesBusy) {
                        profilesBusy = true
                        profilesError = null
                        scope.launch {
                            try {
                                externalAccountName = game.signInExternal(apiRoot, username, password)
                                externalApiRoot = apiRoot.trim().trimEnd('/')
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                profilesError = e.message ?: "Не удалось войти на сервер авторизации."
                            } finally {
                                profilesBusy = false
                            }
                        }
                    }
                },
                onSignOutExternal = {
                    game.signOutExternal()
                    externalAccountName = null
                    externalApiRoot = ""
                },
            ) }
            MotionOverlay(modHelperOpen) {
                modHelperIssue?.let { issue ->
                    ModHelperDialog(
                        issue = issue,
                        onDismiss = { modHelperOpen = false },
                        onOpenBuilds = {
                            modHelperOpen = false
                            selectedBuild = null
                            dest = Dest.Builds
                        },
                    )
                }
            }
            MotionOverlay(installBuild != null) {
                installBuild?.let { build ->
                    com.cactuslauncher.ui.InstallDialog(build.name, installProgress, installBusy, installPaused, installError,
                        onPause = { installPaused = !installPaused; game.pauseInstallation(installPaused) },
                        onCancel = { installJob?.cancel() },
                        onRetry = { startInstall(build) },
                        onClose = { installBuild = null }, build = build)
                }
            }
            launchFailure?.let { (buildId, message) ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    T(message, 14, C.onToast, lineHeight = 20)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        builds.find { it.id == buildId }?.let { build ->
                            T("Открыть логи и диагностику", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                                launchFailure = null
                                readingNews = false
                                dest = Dest.Builds
                                selectedBuild = build
                                logsBuildId = build.id
                            })
                        }
                        T("Закрыть", 14, C.onToast, lineHeight = 18, modifier = Modifier.clickNoRipple { launchFailure = null })
                    }
                }
            }
            notice?.let { message ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast)
                        .clickNoRipple { notice = null }.padding(16.dp),
                ) { T(message, 14, C.onToast, lineHeight = 20) }
            }
        }
    }
}
