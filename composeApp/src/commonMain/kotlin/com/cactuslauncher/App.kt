package com.cactuslauncher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.Dest
import com.cactuslauncher.state.AppMessages
import com.cactuslauncher.state.BuildsState
import com.cactuslauncher.state.LogsState
import com.cactuslauncher.state.ModsState
import com.cactuslauncher.state.NavigationState
import com.cactuslauncher.state.PlayerState
import com.cactuslauncher.state.PreferencesState
import com.cactuslauncher.state.ProfilesState
import com.cactuslauncher.state.ScreenshotsState
import com.cactuslauncher.state.UpdatesState
import com.cactuslauncher.theme.AppFontProvider
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.CactusMotion
import com.cactuslauncher.ui.InitialSetupDialog
import com.cactuslauncher.ui.ModHelperDialog
import com.cactuslauncher.ui.MotionOverlay
import com.cactuslauncher.ui.NewBuildDialog
import com.cactuslauncher.ui.ProfilesDialog
import com.cactuslauncher.ui.SettingsDialog
import com.cactuslauncher.ui.Sidebar
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.screens.OtherScreen
import com.cactuslauncher.model.OtherSection
import com.cactuslauncher.ui.screens.ServersSectionState
import com.cactuslauncher.state.ServersState
import com.cactuslauncher.ui.screens.ScreenshotViewer
import com.cactuslauncher.ui.screens.ScreenshotsSectionState
import com.cactuslauncher.ui.screens.BuildListScreen
import com.cactuslauncher.ui.screens.BuildLogsScreen
import com.cactuslauncher.ui.screens.BuildManageScreen
import com.cactuslauncher.ui.screens.HomeScreen
import com.cactuslauncher.ui.screens.ModsCatalogScreen
import com.cactuslauncher.ui.screens.NewsReader
import com.cactuslauncher.ui.screens.NewsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root composable: wires the per-area state holders (package [com.cactuslauncher.state]) to screens and dialogs.
 * Effects stay here because their keys are Compose state; the work itself lives in the holders.
 */
@Composable
fun App(game: GameService, onGhostMode: () -> Unit) {
    val pageOffset = with(LocalDensity.current) { 6.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    val messages = remember { AppMessages(game) }
    val navigation = remember { NavigationState() }
    val settings = remember { PreferencesState(game, messages) }
    val preferences = settings.preferences
    val profiles = remember { ProfilesState(game, scope, messages) }
    val updates = remember { UpdatesState(game) }
    val builds = remember { BuildsState(game, scope, preferences, profiles, messages) }
    val mods = remember { ModsState(game, scope, preferences, builds, navigation, messages) }
    val logs = remember { LogsState(game, scope, preferences, profiles, messages) }
    val player = remember { PlayerState(game, scope) { preferences.showPlayer } }
    val screenshots = remember { ScreenshotsState(game, scope, builds, messages) }
    val servers = remember { ServersState(game, scope, builds, messages) }
    val otherSection = navigation.otherSection
    var otherOverview by remember { mutableStateOf<com.cactuslauncher.model.OtherOverview?>(null) }

    LaunchedEffect(Unit) { profiles.operation { game.profiles() } }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (preferences.theme) { "dark" -> true; "light" -> false; else -> systemDark }
    SideEffect { C.dark = darkTheme }
    LaunchedEffect(Unit) { settings.load() }
    LaunchedEffect(settings.settingsReady, settings.settingsLoadFailed, settings.preferencesError,
        preferences.snapshot(!settings.firstSetupOpen)) { settings.save() }
    LaunchedEffect(settings.settingsReady, settings.settingsLoadFailed, preferences.autoUpdate) {
        if (settings.settingsReady && !settings.settingsLoadFailed && preferences.autoUpdate) updates.check(download = true)
    }
    LaunchedEffect(settings.settingsReady, preferences.automaticMemory) { settings.applyRecommendedMemory() }

    LaunchedEffect(builds.selectedBuild?.id) { builds.loadResourcePacks(builds.selectedBuild?.id) }
    LaunchedEffect(builds.selectedBuild?.id, builds.selectedBuild?.version, builds.selectedBuild?.loader,
        builds.selectedBuild?.let { builds.javaPlanRevisions[it.id] },
        preferences.memoryGb, preferences.automaticMemory, preferences.autoJavaOptimization) {
        builds.loadJavaPlan(builds.selectedBuild)
    }
    LaunchedEffect(Unit) { builds.operation({ game.builds() }) }

    LaunchedEffect(navigation.dest, builds.loaded, builds.builds.map { it.id }) {
        if (navigation.dest == Dest.Mods && builds.loaded) mods.ensureSelection()
    }
    LaunchedEffect(navigation.dest, builds.loaded, mods.selectedBuildId, mods.reloadKey) {
        if (navigation.dest == Dest.Mods && builds.loaded) mods.loadInstalled()
    }
    LaunchedEffect(mods.installedModsBuildId, mods.installedMods.map { it.fileName }, mods.reloadKey, preferences.modAntivirusEnabled) {
        mods.scanSecurity()
    }
    LaunchedEffect(mods.installedModsBuildId, mods.installedMods, mods.reloadKey) { mods.checkProblems() }
    LaunchedEffect(navigation.dest, builds.loaded, builds.builds.map { it.id }) {
        if (navigation.dest == Dest.Other && builds.loaded) {
            screenshots.ensureSelection()
            servers.ensureSelection()
        }
    }
    LaunchedEffect(navigation.dest, otherSection, builds.loaded, screenshots.selectedBuildId) {
        if (navigation.dest == Dest.Other && otherSection == OtherSection.Screenshots && builds.loaded) screenshots.watch()
    }
    // The menu's numbers are re-counted each time it is shown.
    LaunchedEffect(navigation.dest, otherSection, builds.loaded, builds.builds.map { it.id }) {
        if (navigation.dest == Dest.Other && otherSection == null && builds.loaded) {
            try { otherOverview = game.otherOverview() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { game.reportError("other-overview", e) }
        }
    }
    // Servers are re-read and pinged each time the section opens or the build changes.
    LaunchedEffect(navigation.dest, otherSection, builds.loaded, servers.selectedBuildId) {
        if (navigation.dest == Dest.Other && otherSection == OtherSection.Servers && builds.loaded) servers.refresh()
    }
    LaunchedEffect(navigation.dest, builds.loaded, mods.selectedBuildId, mods.search, mods.catalogReloadKey, mods.catalogActive) {
        mods.searchCatalog()
    }
    LaunchedEffect(navigation.dest, mods.catalogActive, mods.projects.map { it.id to it.iconUrl }) { mods.loadIcons() }
    LaunchedEffect(navigation.dest, mods.catalogActive, preferences.translateModDescriptions,
        mods.projects.map { it.id to it.description }) { mods.translateDescriptions() }

    LaunchedEffect(navigation.dest, builds.loaded) {
        if (navigation.dest == Dest.Builds && builds.loaded) builds.pollRunning()
    }
    LaunchedEffect(logs.buildId) { logs.buildId?.let { logs.follow(it) } }
    LaunchedEffect(navigation.settingsOpen, builds.selectedBuild?.id) {
        if (navigation.settingsOpen || builds.selectedBuild != null) settings.loadJavaOptimizationInfo()
    }
    LaunchedEffect(preferences.showPlayer) { player.applyEnabled(preferences.showPlayer) }
    LaunchedEffect(preferences.showPlayer) { if (preferences.showPlayer) player.poll() }
    LaunchedEffect(player.currentTrack) { if (player.currentTrack == null) player.expanded = false }
    LaunchedEffect(Unit) { builds.pollLaunchFailures() }
    LaunchedEffect(messages.notice) { if (messages.notice != null) { delay(4000); messages.notice = null } }
    LaunchedEffect(messages.undoModUpdate) { if (messages.undoModUpdate != null) { delay(8000); messages.undoModUpdate = null } }

    val selectedInstalledMods = mods.selectedInstalledMods
    val securityReportsByProject = selectedInstalledMods.mapNotNull { mod ->
        mod.projectId.takeIf(String::isNotBlank)?.let { projectId ->
            mods.securityReports["${mods.selectedBuildId}:${mod.fileName}"]?.let { projectId to it }
        }
    }.toMap()
    val scanningSecurityProjects = selectedInstalledMods.filter {
        "${mods.selectedBuildId}:${it.fileName}" in mods.scanningFiles
    }.mapNotNull { it.projectId.takeIf(String::isNotBlank) }.toSet()

    AppFontProvider {
        Box(Modifier.fillMaxSize().background(C.window)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(
                    current = navigation.dest,
                    onSelect = { next ->
                        navigation.readingNews = false
                        navigation.dest = next
                        // "Остальное" in the sidebar always leads to its menu, also from inside a section.
                        if (next == Dest.Other) { navigation.otherSection = null; screenshots.close() }
                        if (next == Dest.Builds) builds.selectedBuild = null
                    },
                    onOpenSettings = { navigation.settingsOpen = true },
                    currentTrack = player.currentTrack.takeIf { preferences.showPlayer },
                    trackError = player.trackError.takeIf { preferences.showPlayer },
                    onTrackCommand = { command -> player.command(command) },
                    playerExpanded = player.expanded,
                    onTogglePlayer = { player.expanded = !player.expanded },
                    profileName = profiles.profiles?.active?.name ?: "Профиль",
                    profileAvatar = profiles.profiles?.active?.avatar,
                    onOpenProfiles = { profiles.open = true },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (navigation.readingNews) {
                        NewsReader(onBack = { navigation.readingNews = false }, beta = navigation.readingBeta)
                    } else {
                    AnimatedContent(
                        targetState = Triple(navigation.dest, builds.selectedBuild, logs.buildId),
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn(tween(180, easing = CactusMotion.easeOut)) +
                                slideInVertically(tween(200, easing = CactusMotion.easeOut)) { pageOffset })
                                .togetherWith(fadeOut(tween(90)))
                        }, label = "navigation",
                    ) { (route, buildDetail, logsId) ->
                        when (route) {
                            Dest.Home -> HomeScreen(builds = builds.builds, onOpenBuild = { navigation.dest = Dest.Builds })
                            Dest.Builds -> if (buildDetail == null) BuildListScreen(
                                builds = builds.builds,
                                loading = !builds.loaded && builds.busy,
                                error = builds.error,
                                runningBuildIds = builds.runningBuildIds,
                                onRetry = { builds.operation({ game.builds() }) },
                                onCreate = { navigation.newBuildOpen = true },
                                onOpen = { logs.buildId = null; builds.selectedBuild = it },
                                onPlay = { build -> builds.play(build, onGhostMode) },
                            ) else if (logsId == buildDetail.id) BuildLogsScreen(
                                build = buildDetail,
                                lines = logs.lines,
                                loading = logs.loading,
                                error = logs.error,
                                diagnosis = logs.diagnosis,
                                diagnosisLoading = logs.diagnosisLoading,
                                recoveryBusy = logs.recoveryBusy,
                                recoveryMessage = logs.recoveryMessage,
                                onBack = { logs.buildId = null },
                                onTryRecovery = { modFile ->
                                    logs.tryRecovery(buildDetail.id, modFile, running = buildDetail.id in builds.runningBuildIds)
                                },
                                onOpenFile = { logs.openFile(buildDetail.id) },
                            ) else {
                            LaunchedEffect(buildDetail.id, buildDetail.playtime) { mods.loadLastUpdate(buildDetail.id) }
                            BuildManageScreen(
                                build = buildDetail,
                                busy = builds.busy,
                                running = buildDetail.id in builds.runningBuildIds,
                                memoryGb = preferences.memoryGb,
                                automaticMemory = preferences.automaticMemory,
                                javaPlan = builds.javaPlan,
                                javaPlanLoading = builds.javaPlanLoading,
                                javaOptimizationInfo = settings.javaOptimizationInfo,
                                resourcePacks = builds.resourcePacks,
                                resourcePacksLoading = builds.resourcePacksLoading,
                                resourcePacksError = builds.resourcePacksError,
                                onImportResourcePack = { builds.importResourcePack(buildDetail.id) },
                                onToggleResourcePack = { pack, enabled -> builds.setResourcePackEnabled(buildDetail.id, pack, enabled) },
                                onOpenResourcePacks = { builds.openResourcePacksFolder(buildDetail.id) },
                                onRefreshResourcePacks = { builds.refreshResourcePacks(buildDetail.id) },
                                onMemoryChange = { preferences.memoryGb = it; preferences.automaticMemory = false },
                                onAutomaticMemoryChange = { preferences.automaticMemory = it },
                                onIcon = { icon -> builds.operation({ game.setBuildIcon(buildDetail.id, icon) }) },
                                onAutoUpdateMods = { enabled -> builds.operation({ game.setAutoUpdateMods(buildDetail.id, enabled) }) },
                                lastModUpdate = mods.lastUpdate.takeIf { mods.lastUpdateBuildId == buildDetail.id },
                                rollingBackMods = mods.actionId == ModsState.ROLLBACK,
                                onRollbackMods = { mods.rollback(buildDetail.id) },
                                error = builds.error,
                                onRename = { name -> builds.operation({ game.renameBuild(buildDetail.id, name) }) },
                                onDelete = { deleteFiles -> builds.operation({ game.deleteBuild(buildDetail.id, deleteFiles) }) },
                                onOpenFolder = { builds.openFolder(buildDetail.id) },
                                onBack = { builds.selectedBuild = null },
                                onOpenLogs = { logs.buildId = buildDetail.id },
                                onOpenMods = { mods.selectedBuildId = buildDetail.id; navigation.dest = Dest.Mods },
                                onPlay = { builds.play(buildDetail, onGhostMode) },
                                onNewBuild = { navigation.newBuildOpen = true },
                            )
                            }
                            Dest.Mods -> ModsCatalogScreen(
                                builds = builds.builds,
                                selectedBuildId = mods.selectedBuildId,
                                installedMods = selectedInstalledMods,
                                projects = mods.projects,
                                query = mods.search,
                                actionId = mods.actionId,
                                loadingCatalog = mods.catalogLoading,
                                icons = mods.icons,
                                translatedDescriptions = mods.translatedDescriptions,
                                failedTranslationIds = mods.failedTranslationIds.toSet(),
                                showTranslatedDescriptions = preferences.translateModDescriptions,
                                hasMore = mods.catalogHasMore,
                                loadingMore = mods.catalogLoadingMore,
                                loadMoreError = mods.catalogMoreError,
                                installedProjectIds = selectedInstalledMods.mapNotNull { it.projectId.takeIf(String::isNotBlank) }.toSet(),
                                securityEnabled = preferences.modAntivirusEnabled,
                                securityReportsByProject = securityReportsByProject,
                                scanningSecurityProjects = scanningSecurityProjects,
                                queuedProjectIds = mods.installQueue.filter { it.first == mods.selectedBuildId }.map { it.second.id },
                                activeInstallProjectId = mods.activeInstall?.takeIf { it.first == mods.selectedBuildId }?.second?.id,
                                installErrors = mods.installErrors.filterKeys { it.startsWith("${mods.selectedBuildId}:") }
                                    .mapKeys { it.key.substringAfter(':') },
                                installQueueCount = mods.installQueue.count { it.first == mods.selectedBuildId } +
                                    if (mods.activeInstall?.first == mods.selectedBuildId) 1 else 0,
                                onCatalogSelected = { mods.catalogActive = it },
                                loadingBuilds = !builds.loaded && builds.busy,
                                loadingMods = mods.loading,
                                error = mods.error,
                                onSelectBuild = { mods.selectedBuildId = it.id },
                                onQueryChange = { mods.search = it },
                                onLoadMore = { mods.loadMore() },
                                onInstall = { project -> mods.selectedBuildId?.let { mods.enqueueInstall(it, project) } },
                                onRemove = { mod -> mods.remove(mod) },
                                onToggleMod = { mod, enabled -> mods.setEnabled(mod, enabled) },
                                onRefresh = { mods.refresh() },
                                updates = mods.selectedUpdates,
                                checkingUpdates = mods.checkingUpdates,
                                onCheckUpdates = { mods.refreshAndCheck() },
                                onUpdate = { mod -> mods.update(mod) },
                                onUpdateAll = { mods.update(null) },
                                problems = mods.selectedProblems,
                                missingProjects = mods.selectedMissingProjects,
                                onInstallMissing = { problem -> mods.installMissing(problem) },
                                onOpenFolder = { mods.openFolder() },
                                onOpenBuilds = { navigation.dest = Dest.Builds },
                            )
                            Dest.Other -> OtherScreen(
                                builds = builds.builds,
                                loadingBuilds = !builds.loaded && builds.busy,
                                section = otherSection,
                                onOpenSection = { screenshots.close(); navigation.otherSection = it },
                                overview = otherOverview,
                                shotThumbnail = { buildId, shot -> game.screenshotThumbnail(buildId, shot) },
                                overlayOpen = otherSection == OtherSection.Screenshots && screenshots.viewingShot != null,
                                screenshots = ScreenshotsSectionState(
                                    selectedBuildId = screenshots.selectedBuildId,
                                    shots = screenshots.selectedShots,
                                    loading = screenshots.loading,
                                    error = screenshots.error,
                                    thumbnail = { shot -> screenshots.selectedBuildId?.let { game.screenshotThumbnail(it, shot) } },
                                    // Both sections follow one build choice.
                                    onSelectBuild = { build -> screenshots.selectedBuildId = build.id; servers.select(build.id) },
                                    onOpen = { shot -> screenshots.open(shot) },
                                    onOpenFolder = { screenshots.openFolder() },
                                ),
                                servers = ServersSectionState(
                                    selectedBuildId = servers.selectedBuildId,
                                    servers = servers.visibleServers,
                                    pings = servers.pings,
                                    loading = servers.loading,
                                    refreshing = servers.refreshing,
                                    error = servers.error,
                                    form = servers.form,
                                    busy = servers.busy,
                                    playBlocked = builds.busy || servers.selectedBuildId in builds.runningBuildIds,
                                    onSelectBuild = { build -> servers.select(build.id); screenshots.selectedBuildId = build.id },
                                    onRefresh = { servers.refresh() },
                                    onAdd = { servers.startAdd() },
                                    onEdit = { servers.startEdit(it) },
                                    onFormChange = { name, address -> servers.updateForm(name, address) },
                                    onFormSave = { servers.saveForm() },
                                    onFormCancel = { servers.cancelForm() },
                                    onRemove = { servers.remove(it) },
                                    onPlay = { servers.play(it, onGhostMode) },
                                ),
                                onOpenBuilds = { navigation.dest = Dest.Builds },
                            )
                            Dest.News -> NewsScreen(
                                onOpenArticle = { navigation.readingBeta = false; navigation.readingNews = true },
                                onOpenBeta = { navigation.readingBeta = true; navigation.readingNews = true },
                            )
                        }
                    }
                    }
                }
            }

            MotionOverlay(navigation.newBuildOpen) {
                NewBuildDialog(
                    game = game,
                    busy = builds.busy,
                    saveError = builds.error,
                    onDismiss = { navigation.newBuildOpen = false },
                    onCreate = { name, version, loader ->
                        builds.operation({ game.createBuild(name, version, loader) }, {
                            navigation.newBuildOpen = false
                            builds.selectedBuild = null
                            navigation.dest = Dest.Builds
                            builds.builds.lastOrNull()?.let { builds.startInstall(it) }
                        })
                    },
                )
            }
            MotionOverlay(navigation.settingsOpen) {
                SettingsDialog(
                    preferences,
                    javaOptimizationInfo = settings.javaOptimizationInfo,
                    updateStatus = updates.status,
                    update = updates.update,
                    updateDownloaded = updates.downloadedFile != null,
                    updateBusy = updates.busy,
                    onCheckUpdates = { scope.launch { updates.check(download = false) } },
                    onDownloadUpdate = { scope.launch { updates.check(download = true) } },
                    onOpenUpdate = { scope.launch { updates.openDownloaded() } },
                    onDismiss = { navigation.settingsOpen = false },
                    onReset = { settings.reset() },
                    onRestartSetup = {
                        navigation.settingsOpen = false
                        settings.firstSetupOpen = true
                    },
                    error = settings.preferencesError,
                )
            }
            MotionOverlay(settings.firstSetupOpen) {
                InitialSetupDialog(
                    preferences,
                    settings.javaOptimizationInfo,
                    settings.preferencesError,
                    initialNickname = profiles.profiles?.active?.name ?: "Nick",
                    onChooseFolder = { current -> game.chooseDirectory(current) },
                    onComplete = { nickname ->
                        if (nickname != null) profiles.applySetupNickname(nickname)
                        settings.firstSetupOpen = false
                    },
                )
            }
            MotionOverlay(profiles.open) { ProfilesDialog(
                snapshot = profiles.profiles, busy = profiles.busy, error = profiles.error,
                onDismiss = { profiles.open = false },
                onRetry = { profiles.operation { game.profiles() } },
                onAdd = { name -> profiles.operation { game.addProfile(name) } },
                onRename = { id, name -> profiles.operation { game.renameProfile(id, name) } },
                onSelect = { id -> profiles.operation { game.selectProfile(id) } },
                onDelete = { id -> profiles.operation { game.deleteProfile(id) } },
                onChooseAvatar = { id -> profiles.chooseAvatar(id) },
                onRemoveAvatar = { id -> profiles.removeAvatar(id) },
                externalAccountName = profiles.externalAccountName,
                advancedMode = preferences.advancedMode,
                onSignInExternal = { apiRoot, username, password -> profiles.signIn(apiRoot, username, password) },
                onSignOutExternal = { profiles.signOut() },
            ) }
            MotionOverlay(mods.helperOpen) {
                mods.helperIssue?.let { issue ->
                    ModHelperDialog(
                        issue = issue,
                        onDismiss = { mods.helperOpen = false },
                        onOpenBuilds = {
                            mods.helperOpen = false
                            builds.selectedBuild = null
                            navigation.dest = Dest.Builds
                        },
                    )
                }
            }
            MotionOverlay(builds.installBuild != null) {
                builds.installBuild?.let { build ->
                    com.cactuslauncher.ui.InstallDialog(build.name, builds.installProgress, builds.installBusy, builds.installPaused, builds.installError,
                        onPause = { builds.togglePause() },
                        onCancel = { builds.cancelInstall() },
                        onRetry = { builds.startInstall(build) },
                        onClose = { builds.installBuild = null }, build = build)
                }
            }
            MotionOverlay(navigation.dest == Dest.Other && otherSection == OtherSection.Screenshots && screenshots.viewingShot != null) {
                screenshots.viewingShot?.let { shot ->
                    val buildId = screenshots.selectedBuildId
                    ScreenshotViewer(
                        shot = shot,
                        position = screenshots.viewingIndex + 1,
                        count = screenshots.selectedShots.size,
                        busy = screenshots.busy,
                        image = { current -> buildId?.let { game.screenshotImage(it, current, 2560) } },
                        onMove = { screenshots.move(it) },
                        onClose = { screenshots.close() },
                        onOpenInSystem = { screenshots.openInSystem(shot) },
                        onReveal = { screenshots.reveal(shot) },
                        onCopy = { screenshots.copy(shot) },
                        onDelete = { screenshots.delete(shot) },
                    )
                }
            }
            messages.launchWarning?.let { warning ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    T(warning.message, 14, C.onToast, lineHeight = 20)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        warning.install?.let { project ->
                            T("Установить ${project.title}", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                                messages.launchWarning = null
                                mods.enqueueInstall(warning.buildId, project)
                                messages.notice = "${project.title} добавлен в очередь установки."
                            })
                        }
                        T("Всё равно запустить", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                            messages.launchWarning = null
                            builds.builds.find { it.id == warning.buildId }?.let { builds.play(it, onGhostMode, checkMods = false, server = warning.server) }
                        })
                        T("Отмена", 14, C.onToast, lineHeight = 18, modifier = Modifier.clickNoRipple { messages.launchWarning = null })
                    }
                }
            }
            messages.launchFailure?.let { (buildId, message) ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    T(message, 14, C.onToast, lineHeight = 20)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (messages.launchFailureRollback) T("Откатить обновление модов", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                            messages.launchFailure = null
                            messages.launchFailureRollback = false
                            mods.rollback(buildId)
                        })
                        builds.builds.find { it.id == buildId }?.let { build ->
                            T("Открыть логи и диагностику", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                                messages.launchFailure = null
                                navigation.readingNews = false
                                navigation.dest = Dest.Builds
                                builds.selectedBuild = build
                                logs.buildId = build.id
                            })
                        }
                        T("Закрыть", 14, C.onToast, lineHeight = 18, modifier = Modifier.clickNoRipple { messages.launchFailure = null })
                    }
                }
            }
            if (messages.notice == null) messages.undoModUpdate?.let { (buildId, message) ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(message, 14, C.onToast, lineHeight = 20)
                    T("Отменить", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple {
                        messages.undoModUpdate = null
                        mods.rollback(buildId)
                    })
                }
            }
            messages.notice?.let { message ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        .clip(RoundedCornerShape(12.dp)).background(C.toast)
                        .clickNoRipple { messages.notice = null }.padding(16.dp),
                ) { T(message, 14, C.onToast, lineHeight = 20) }
            }
        }
    }
}
