package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.InstallProgress
import com.cactuslauncher.model.InstalledResourcePack
import com.cactuslauncher.model.JavaOptimizationPlan
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.ui.LauncherSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Builds list and detail: create/rename/delete, Java plan, resource packs, install and launch. */
class BuildsState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val preferences: LauncherSettings,
    private val profiles: ProfilesState,
    private val messages: AppMessages,
) {
    val builds = mutableStateListOf<LauncherBuild>()
    /** Bumped when a build's mods change, so its Java plan is recalculated. */
    val javaPlanRevisions = mutableStateMapOf<String, Int>()
    var selectedBuild by mutableStateOf<LauncherBuild?>(null)
    var javaPlan by mutableStateOf<JavaOptimizationPlan?>(null)
    var javaPlanLoading by mutableStateOf(false)
    var runningBuildIds by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    var resourcePacks by mutableStateOf<List<InstalledResourcePack>>(emptyList())
    var resourcePacksLoading by mutableStateOf(false)
    var resourcePacksError by mutableStateOf<String?>(null)

    var installBuild by mutableStateOf<LauncherBuild?>(null)
    var installProgress by mutableStateOf(InstallProgress())
    var installBusy by mutableStateOf(false)
    var installPaused by mutableStateOf(false)
    var installError by mutableStateOf<String?>(null)
    private var installJob: Job? = null

    fun bumpJavaPlan(id: String) {
        javaPlanRevisions[id] = (javaPlanRevisions[id] ?: 0) + 1
    }

    private fun replaceBuilds(updated: List<LauncherBuild>) {
        val selectedId = selectedBuild?.id
        builds.clear()
        builds.addAll(updated)
        selectedBuild = updated.find { it.id == selectedId }
    }

    fun operation(operation: suspend () -> List<LauncherBuild>, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                replaceBuilds(operation())
                loaded = true
                busy = false
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось сохранить сборки."
            } finally {
                busy = false
            }
        }
    }

    private suspend fun refresh() {
        try {
            replaceBuilds(game.builds())
            messages.backgroundRecovered("refresh-builds")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { messages.backgroundFailure("refresh-builds", "Не удалось обновить список сборок.", e) }
    }

    suspend fun loadResourcePacks(id: String?) {
        if (id == null) {
            resourcePacks = emptyList()
            resourcePacksError = null
            return
        }
        resourcePacks = emptyList()
        resourcePacksLoading = true
        resourcePacksError = null
        try { resourcePacks = game.resourcePacks(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось прочитать ресурспаки." }
        finally { resourcePacksLoading = false }
    }

    suspend fun loadJavaPlan(build: LauncherBuild?) {
        if (build == null) {
            javaPlan = null
            javaPlanLoading = false
            return
        }
        delay(180)
        javaPlan = null
        javaPlanLoading = true
        try {
            javaPlan = game.javaOptimizationPlan(
                build.id, preferences.memoryGb, preferences.automaticMemory, preferences.autoJavaOptimization,
            )
            messages.backgroundRecovered("java-plan")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            javaPlan = null
            messages.backgroundFailure("java-plan", "Не удалось рассчитать параметры Java для сборки.", e)
        } finally {
            javaPlanLoading = false
        }
    }

    suspend fun pollRunning() {
        while (currentCoroutineContext().isActive) {
            try {
                runningBuildIds = game.runningBuildIds()
                messages.backgroundRecovered("running-builds")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.backgroundFailure("running-builds", "Не удалось проверить запущенные сборки.", e) }
            delay(1000)
        }
    }

    suspend fun pollLaunchFailures() {
        while (currentCoroutineContext().isActive) {
            try {
                val failures = game.takeLaunchFailures()
                if (failures.isNotEmpty()) {
                    val failure = failures.last()
                    // A crash after a mod update that never had a normal session: offer to undo it.
                    messages.launchFailureRollback = try {
                        game.lastModUpdate(failure.first)?.confirmed == false
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { game.reportError("mod-backups", e); false }
                    messages.launchFailure = failure
                    runningBuildIds = game.runningBuildIds()
                }
                messages.backgroundRecovered("launch-failures")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.backgroundFailure("launch-failures", "Не удалось получить результат запуска игры.", e) }
            delay(1500)
        }
    }

    fun startInstall(build: LauncherBuild) {
        if (installBusy || busy) return
        installBuild = build
        installBusy = true
        installPaused = false
        busy = true
        installError = null
        installProgress = InstallProgress()
        installJob = scope.launch {
            val updates = Channel<InstallProgress>(Channel.CONFLATED)
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
                busy = false
            }
        }
    }

    fun togglePause() {
        installPaused = !installPaused
        game.pauseInstallation(installPaused)
    }

    fun cancelInstall() { installJob?.cancel() }

    /**
     * With [checkMods], mods that would stop the game are reported first and the launch waits for confirmation.
     * [server] is an address from the build's server list to join right after start.
     */
    fun play(build: LauncherBuild, onGhostMode: () -> Unit, checkMods: Boolean = true, server: String? = null) {
        if (busy || build.id in runningBuildIds) return
        if (!build.installed) { startInstall(build); return }
        busy = true
        messages.launchWarning = null
        scope.launch {
            try {
                if (checkMods && !build.loader.equals("Vanilla", ignoreCase = true)) {
                    val warning = launchWarning(build)?.copy(server = server)
                    if (warning != null) {
                        messages.launchFailure = null
                        messages.launchWarning = warning
                        return@launch
                    }
                }
                val enteredGhostMode = game.launchBuild(
                    build.id, preferences.memoryGb, profiles.launchAuthServer,
                    preferences.automaticMemory, preferences.autoJavaOptimization, preferences.ghostMode, server,
                )
                if (enteredGhostMode) {
                    onGhostMode()
                    return@launch
                }
                runningBuildIds = game.runningBuildIds()
                messages.notice = if (server == null) "Процесс Minecraft запущен." else "Процесс Minecraft запущен, игра подключится к $server."
                messages.launchFailure = null
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                messages.launchFailure = build.id to (e.message ?: "Не удалось запустить Minecraft.")
                refresh()
            }
            finally {
                profiles.syncExternalAccount()
                busy = false
            }
        }
    }

    /** A failed check never blocks the launch: the loader reports real problems itself. */
    private suspend fun launchWarning(build: LauncherBuild): LaunchWarning? {
        val blocking = try { game.modProblems(build.id).filter { it.error } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { game.reportError("mod-problems-launch", e); return null }
        if (blocking.isEmpty()) return null
        val first = blocking.first()
        val more = if (blocking.size > 1) " И ещё ${blocking.size - 1}." else ""
        val text = "Сборка «${build.name}» может не запуститься. ${first.modName}: ${first.message.replaceFirstChar { it.lowercase() }}.$more"
        val install = blocking.firstNotNullOfOrNull { problem ->
            problem.installId?.let { slug ->
                try { kotlinx.coroutines.withTimeoutOrNull(5000) { game.findModrinthProject(build.id, slug) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        }
        return LaunchWarning(build.id, text, install)
    }

    fun importResourcePack(id: String) {
        if (busy) return
        scope.launch {
            busy = true
            resourcePacksError = null
            try {
                if (game.importResourcePack(id)) resourcePacks = game.resourcePacks(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось добавить ресурспак." }
            finally { busy = false }
        }
    }

    fun setResourcePackEnabled(id: String, pack: InstalledResourcePack, enabled: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            resourcePacksError = null
            try {
                game.setResourcePackEnabled(id, pack.fileName, enabled)
                resourcePacks = game.resourcePacks(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось изменить ресурспак." }
            finally { busy = false }
        }
    }

    fun openResourcePacksFolder(id: String) {
        scope.launch {
            try { game.openResourcePacksFolder(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось открыть папку ресурспаков." }
        }
    }

    fun refreshResourcePacks(id: String) {
        scope.launch {
            resourcePacksLoading = true
            resourcePacksError = null
            try { resourcePacks = game.resourcePacks(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { resourcePacksError = e.message ?: "Не удалось обновить список ресурспаков." }
            finally { resourcePacksLoading = false }
        }
    }

    fun openFolder(id: String) {
        scope.launch {
            try { game.openBuildFolder(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось открыть папку." }
        }
    }
}
