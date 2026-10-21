package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.Dest
import com.cactuslauncher.model.InstalledMod
import com.cactuslauncher.model.ModHelperIssue
import com.cactuslauncher.model.ModProblem
import com.cactuslauncher.model.ModSecurityReport
import com.cactuslauncher.model.ModUpdate
import com.cactuslauncher.model.ModUpdateBackup
import com.cactuslauncher.model.ModrinthProject
import com.cactuslauncher.ui.LauncherSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Mods section: installed mods of the selected build, Modrinth catalog, install queue, security scans, translations. */
class ModsState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val preferences: LauncherSettings,
    private val builds: BuildsState,
    private val navigation: NavigationState,
    private val messages: AppMessages,
) {
    var selectedBuildId by mutableStateOf<String?>(null)
    var catalogActive by mutableStateOf(false)
    var installedMods by mutableStateOf<List<InstalledMod>>(emptyList())
    var installedModsBuildId by mutableStateOf<String?>(null)
    var projects by mutableStateOf<List<ModrinthProject>>(emptyList())
    val icons = mutableStateMapOf<String, ImageBitmap>()
    val securityReports = mutableStateMapOf<String, ModSecurityReport>()
    val scanningFiles = mutableStateListOf<String>()
    private val scanSemaphore = Semaphore(2)
    val translatedDescriptions = mutableStateMapOf<String, String>()
    val failedTranslationIds = mutableStateListOf<String>()
    var search by mutableStateOf("")
    var actionId by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var catalogLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var helperIssue by mutableStateOf<ModHelperIssue?>(null)
    var helperOpen by mutableStateOf(false)
    var reloadKey by mutableIntStateOf(0)
    var catalogReloadKey by mutableIntStateOf(0)
    private var catalogOffset by mutableIntStateOf(0)
    var catalogHasMore by mutableStateOf(false)
    var catalogLoadingMore by mutableStateOf(false)
    var catalogMoreError by mutableStateOf<String?>(null)
    private var catalogGeneration by mutableIntStateOf(0)
    val installQueue = mutableStateListOf<Pair<String, ModrinthProject>>()
    val installErrors = mutableStateMapOf<String, String>()
    var activeInstall by mutableStateOf<Pair<String, ModrinthProject>?>(null)
    private var installWorkerRunning by mutableStateOf(false)

    /** Modrinth updates of [updatesBuildId]'s mods, by installed file name. */
    var updates by mutableStateOf<Map<String, ModUpdate>>(emptyMap())
    var updatesBuildId by mutableStateOf<String?>(null)
    var checkingUpdates by mutableStateOf(false)
    /** Compatibility problems of [problemsBuildId]'s enabled mods (see GameService.modProblems). */
    var problems by mutableStateOf<List<ModProblem>>(emptyList())
    var problemsBuildId by mutableStateOf<String?>(null)
    /** Modrinth projects for missing dependencies, by "buildId:slug"; looked up once per build and slug. */
    val missingProjects = mutableStateMapOf<String, ModrinthProject>()
    private val lookedUpMissing = mutableSetOf<String>()

    /** Set by [refreshAndCheck]: the next check reports its failure as a user action. */
    private var manualCheckPending = false
    var lastUpdate by mutableStateOf<ModUpdateBackup?>(null)
    var lastUpdateBuildId by mutableStateOf<String?>(null)

    private val catalogVisible get() = navigation.dest == Dest.Mods && catalogActive

    /** Updates of the selected build, or none while another build's result is shown. */
    val selectedUpdates: Map<String, ModUpdate>
        get() = if (updatesBuildId == selectedBuildId) updates else emptyMap()

    val selectedProblems: List<ModProblem>
        get() = if (problemsBuildId == selectedBuildId) problems else emptyList()
    /** Slug to Modrinth project for the selected build's missing dependencies. */
    val selectedMissingProjects: Map<String, ModrinthProject>
        get() = selectedBuildId?.let { id -> missingProjects.filterKeys { it.startsWith("$id:") }.mapKeys { it.key.removePrefix("$id:") } } ?: emptyMap()

    /** Re-checks the loaded mods of the selected build; called whenever the installed list changes. */
    suspend fun checkProblems() {
        val id = installedModsBuildId ?: run { problems = emptyList(); problemsBuildId = null; return }
        try {
            problems = game.modProblems(id)
            problemsBuildId = id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            problems = emptyList()
            problemsBuildId = id
            messages.backgroundFailure("mod-problems", "Не удалось проверить совместимость модов.", e, visible = false)
            return
        }
        messages.backgroundRecovered("mod-problems")
        for (slug in problems.mapNotNull { it.installId }.distinct()) {
            val key = "$id:$slug"
            if (!lookedUpMissing.add(key)) continue
            try { game.findModrinthProject(id, slug)?.let { missingProjects[key] = it } }
            catch (e: CancellationException) { lookedUpMissing.remove(key); throw e }
            catch (e: Exception) { lookedUpMissing.remove(key) } // offline: try again on the next check
        }
    }

    /** Installs the missing dependency of [problem] through the normal Modrinth queue. */
    fun installMissing(problem: ModProblem) {
        val id = selectedBuildId ?: return
        val project = problem.installId?.let { missingProjects["$id:$it"] } ?: return
        enqueueInstall(id, project)
    }

    /** Installed mods of [selectedBuildId], or empty while another build's list is still shown. */
    val selectedInstalledMods: List<InstalledMod>
        get() = if (installedModsBuildId == selectedBuildId) installedMods else emptyList()

    fun enqueueInstall(buildId: String, project: ModrinthProject) {
        val key = "$buildId:${project.id}"
        if (installedModsBuildId == buildId && installedMods.any { it.projectId == project.id }) return
        if (activeInstall?.let { it.first == buildId && it.second.id == project.id } == true ||
            installQueue.any { it.first == buildId && it.second.id == project.id }) return
        installErrors.remove(key)
        installQueue.add(buildId to project)
        if (installWorkerRunning) return
        installWorkerRunning = true
        scope.launch {
            try {
                while (installQueue.isNotEmpty()) {
                    val (targetBuildId, nextProject) = installQueue.removeAt(0)
                    activeInstall = targetBuildId to nextProject
                    val requestKey = "$targetBuildId:${nextProject.id}"
                    try {
                        game.installMod(targetBuildId, nextProject.id)
                        builds.bumpJavaPlan(targetBuildId)
                        if (selectedBuildId == targetBuildId) {
                            installedMods = game.installedMods(targetBuildId)
                            installedModsBuildId = targetBuildId
                            reloadKey++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: "Не удалось установить мод."
                        installErrors[requestKey] = message
                        showHelperIfIncompatible(targetBuildId, nextProject, message)
                    }
                    activeInstall = null
                }
            } finally {
                activeInstall = null
                installWorkerRunning = false
            }
        }
    }

    private fun showHelperIfIncompatible(buildId: String, project: ModrinthProject, message: String) {
        val normalized = message.lowercase()
        val incompatible = normalized.contains("нет совместимой версии мода или зависимости") ||
            normalized.contains("версия мода несовместима с minecraft или загрузчиком") ||
            normalized.contains("конфликт модов:")
        val build = builds.builds.firstOrNull { it.id == buildId }
        if (!incompatible || build == null) return
        val dependencyId = Regex("(?:зависимости|зависимость):\\s*([A-Za-z0-9]{3,64})", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.takeUnless { it == project.id }
        helperIssue = ModHelperIssue(
            projectTitle = project.title,
            buildName = build.name,
            minecraftVersion = build.version,
            loader = build.loader,
            message = message,
            dependencyId = dependencyId,
            conflict = normalized.contains("конфликт модов:"),
        )
        helperOpen = true
    }

    /** Keeps a valid build selected when the Mods section opens or builds change. */
    fun ensureSelection() {
        if (builds.builds.none { it.id == selectedBuildId }) selectedBuildId = builds.builds.firstOrNull()?.id
    }

    suspend fun loadInstalled() {
        val id = selectedBuildId
        if (id == null) {
            installedMods = emptyList()
            installedModsBuildId = null
            projects = emptyList()
            loading = false
            error = null
            return
        }
        installedMods = emptyList()
        installedModsBuildId = null
        loading = true
        error = null
        try {
            installedMods = game.installedMods(id)
            installedModsBuildId = id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось прочитать папку модов."
        } finally {
            loading = false
        }
        loadLastUpdate(id)
        val manual = manualCheckPending
        manualCheckPending = false
        // Once per build while the launcher runs; "Проверить обновления" checks again.
        if (error == null && updatesBuildId != id && installedMods.isNotEmpty()) checkUpdates(manual)
    }

    /** Re-reads the mods folder and checks Modrinth again: "Проверить обновления" of the installed list. */
    fun refreshAndCheck() {
        if (checkingUpdates) return
        manualCheckPending = true
        updatesBuildId = null
        refresh()
    }

    /** The newest rollback-able update of build [id], for the mods screen and the build settings. */
    suspend fun loadLastUpdate(id: String) {
        try {
            lastUpdate = game.lastModUpdate(id)
            lastUpdateBuildId = id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { game.reportError("mod-backups", e) }
    }

    /** Background checks report a failure once until one succeeds; [manual] ones always show it. */
    fun checkUpdates(manual: Boolean = false) {
        val id = selectedBuildId ?: return
        val build = builds.builds.firstOrNull { it.id == id } ?: return
        if (checkingUpdates || build.loader.equals("Vanilla", ignoreCase = true)) return
        checkingUpdates = true
        scope.launch {
            try {
                val found = game.modUpdates(id)
                updates = found.associateBy { it.fileName }
                updatesBuildId = id
                messages.backgroundRecovered(CHECK_TASK)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (manual) messages.failure(CHECK_TASK, "Не удалось проверить обновления модов.", e)
                else messages.backgroundFailure(CHECK_TASK, "Не удалось проверить обновления модов.", e)
            } finally { checkingUpdates = false }
        }
    }

    /** Updates [mod] or, with null, every mod that has an update. */
    fun update(mod: InstalledMod?) {
        val id = selectedBuildId ?: return
        if (actionId != null) return
        val files = mod?.let { setOf(it.fileName) }
        scope.launch {
            actionId = if (mod == null) UPDATE_ALL else UPDATE_PREFIX + mod.fileName
            try {
                val result = game.applyModUpdates(id, files)
                if (result.updated == 0) messages.notice = "Обновлений не найдено: Modrinth не предложил совместимых версий."
                else {
                    messages.notice = null
                    messages.undoModUpdate = id to
                        if (result.dependencies > 0) "Обновлено модов: ${result.updated}, добавлено зависимостей: ${result.dependencies}."
                        else "Обновлено модов: ${result.updated}."
                }
                builds.bumpJavaPlan(id)
                if (selectedBuildId == id) {
                    installedMods = game.installedMods(id)
                    installedModsBuildId = id
                    updates = game.modUpdates(id).associateBy { it.fileName }
                    updatesBuildId = id
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.failure("mod-update", "Не удалось обновить моды.", e) }
            finally {
                actionId = null
                loadLastUpdate(id)
            }
        }
    }

    /** Restores the jars replaced by the newest update of build [id]. */
    fun rollback(id: String) {
        if (actionId != null) return
        scope.launch {
            actionId = ROLLBACK
            try {
                val restored = game.rollbackModUpdate(id)
                messages.notice = "Обновление модов откачено, восстановлено файлов: $restored."
                builds.bumpJavaPlan(id)
                if (selectedBuildId == id) {
                    installedMods = game.installedMods(id)
                    installedModsBuildId = id
                    updatesBuildId = null
                    checkUpdates()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.failure("mod-rollback", "Не удалось откатить обновление модов.", e) }
            finally {
                actionId = null
                loadLastUpdate(id)
            }
        }
    }

    fun scanSecurity() {
        val buildId = installedModsBuildId ?: return
        if (buildId != selectedBuildId) return
        if (!preferences.modAntivirusEnabled) return
        val validKeys = installedMods.mapTo(hashSetOf()) { "$buildId:${it.fileName}" }
        securityReports.keys.toList()
            .filter { it.startsWith("$buildId:") && it !in validKeys }
            .forEach { securityReports.remove(it) }
        securityReports.keys.toList()
            .filter { it.startsWith("$buildId:") && securityReports[it]?.level == "error" }
            .forEach { securityReports.remove(it) }
        // Scans run in the screen scope: installing a mod changes the caller's effect keys,
        // and a restart would cancel a scan halfway.
        installedMods.filter { it.enabled }.forEach { mod ->
            val key = "$buildId:${mod.fileName}"
            if (securityReports.containsKey(key) || key in scanningFiles) return@forEach
            scanningFiles.add(key)
            scope.launch {
                try {
                    scanSemaphore.withPermit {
                        securityReports[key] = game.scanInstalledMod(buildId, mod.fileName)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    securityReports[key] = ModSecurityReport("error", listOf(e.message ?: "Не удалось проверить JAR."), 0)
                } finally {
                    scanningFiles.remove(key)
                }
            }
        }
    }

    suspend fun searchCatalog() {
        if (!catalogVisible || !builds.loaded) {
            catalogLoading = false
            return
        }
        val id = selectedBuildId ?: return
        val build = builds.builds.firstOrNull { it.id == id } ?: return
        if (build.loader.equals("Vanilla", ignoreCase = true)) {
            projects = emptyList()
            catalogHasMore = false
            catalogLoading = false
            return
        }
        val requestGeneration = catalogGeneration + 1
        catalogGeneration = requestGeneration
        projects = emptyList()
        catalogOffset = 0
        catalogHasMore = false
        catalogLoadingMore = false
        catalogMoreError = null
        catalogLoading = true
        error = null
        try {
            delay(350)
            val page = game.searchMods(id, search, 0)
            if (catalogGeneration == requestGeneration && selectedBuildId == id) {
                projects = page
                catalogOffset = page.size
                catalogHasMore = page.size == 30 && catalogOffset < 10000
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить каталог Modrinth."
        } finally {
            if (catalogGeneration == requestGeneration) catalogLoading = false
        }
    }

    fun loadMore() {
        val id = selectedBuildId ?: return
        if (!catalogVisible || catalogLoading || catalogLoadingMore || !catalogHasMore) return
        val query = search
        val offset = catalogOffset
        val generation = catalogGeneration
        catalogLoadingMore = true
        catalogMoreError = null
        scope.launch {
            try {
                val page = game.searchMods(id, query, offset)
                if (catalogGeneration == generation && selectedBuildId == id && search == query && catalogVisible) {
                    val knownIds = projects.mapTo(hashSetOf()) { it.id }
                    projects = projects + page.filterNot { it.id in knownIds }
                    catalogOffset += page.size
                    catalogHasMore = page.size == 30 && catalogOffset < 10000
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (catalogGeneration == generation) catalogMoreError = e.message ?: "Не удалось загрузить следующую страницу."
            } finally {
                if (catalogGeneration == generation) catalogLoadingMore = false
            }
        }
    }

    suspend fun loadIcons() {
        if (!catalogVisible) return
        val visibleIds = projects.mapTo(hashSetOf()) { it.id }
        icons.keys.toList().filterNot { it in visibleIds }.forEach { icons.remove(it) }
        val missing = projects.filter { it.iconUrl.isNotBlank() && !icons.containsKey(it.id) }
        val semaphore = Semaphore(4)
        coroutineScope {
            missing.forEach { project -> launch {
                semaphore.withPermit {
                    try {
                        game.modIcon(project.iconUrl)?.let { icons[project.id] = it }
                        messages.backgroundRecovered("mod-icons")
                    } catch (e: CancellationException) { throw e }
                    // A missing icon only shows the placeholder; log it without a toast per project.
                    catch (e: Exception) { messages.backgroundFailure("mod-icons", "", e, visible = false) }
                }
            } }
        }
    }

    suspend fun translateDescriptions() {
        if (!catalogVisible) return
        if (!preferences.translateModDescriptions) {
            translatedDescriptions.clear()
            failedTranslationIds.clear()
            return
        }
        val visibleIds = projects.mapTo(hashSetOf()) { it.id }
        translatedDescriptions.keys.toList().filterNot { it in visibleIds }.forEach { translatedDescriptions.remove(it) }
        val semaphore = Semaphore(3)
        coroutineScope {
            projects.filter { it.description.isNotBlank() }.forEach { project -> launch {
                if (translatedDescriptions.containsKey(project.id)) return@launch
                failedTranslationIds.remove(project.id)
                semaphore.withPermit {
                    try {
                        translatedDescriptions[project.id] = game.translateModDescription(project.description)
                        messages.backgroundRecovered("mod-translation")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        // The card already shows "translation failed"; keep the reason in the log.
                        failedTranslationIds.add(project.id)
                        messages.backgroundFailure("mod-translation", "", e, visible = false)
                    }
                }
            } }
        }
    }

    fun remove(mod: InstalledMod) = changeMod(mod, "Не удалось удалить мод.") { id -> game.removeMod(id, mod.fileName) }

    fun setEnabled(mod: InstalledMod, enabled: Boolean) =
        changeMod(mod, "Не удалось изменить состояние мода.") { id -> game.setModEnabled(id, mod.fileName, enabled) }

    private fun changeMod(mod: InstalledMod, failure: String, action: suspend (String) -> Unit) {
        val id = selectedBuildId
        if (id == null || actionId != null) return
        scope.launch {
            actionId = mod.fileName
            error = null
            try {
                action(id)
                installedMods = game.installedMods(id)
                installedModsBuildId = id
                builds.bumpJavaPlan(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failure }
            finally { actionId = null }
        }
    }

    companion object {
        const val UPDATE_ALL = "update:all"
        const val UPDATE_PREFIX = "update:"
        const val ROLLBACK = "rollback"
        private const val CHECK_TASK = "mod-updates-check"
    }

    fun refresh() {
        reloadKey++
        catalogReloadKey++
        selectedBuildId?.let { builds.bumpJavaPlan(it) }
    }

    fun openFolder() {
        val id = selectedBuildId ?: return
        scope.launch {
            try { game.openModsFolder(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось открыть папку модов." }
        }
    }
}
