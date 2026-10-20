package com.cactuslauncher.game

import com.cactuslauncher.game.GameCore
import com.cactuslauncher.model.ProfileSnapshot
import com.cactuslauncher.model.PlayerProfile
import com.cactuslauncher.model.AudioMixerSnapshot
import com.cactuslauncher.model.AudioSession
import com.cactuslauncher.model.TrackSnapshot
import com.cactuslauncher.model.LauncherUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.compose.ui.graphics.toComposeImageBitmap

/** Desktop glue: delegates to the Java game-core module. */
class GameServiceImpl(private val core: GameCore = GameCore()) : GameService {
    private val javaOptimizer = JavaOptimizer()
    private val modrinth = ModrinthClient()
    private val translator = GoogleTranslateClient()
    private val modSecurityScanner = ModSecurityScanner()
    private val resourcePackStore = ResourcePackStore()
    @Volatile private var externalSession: ExternalAuth.Session? = null
    private val versionCatalog by lazy { VersionCatalog() }
    override suspend fun minecraftVersions() = withContext(Dispatchers.IO) {
        versionCatalog.load().map {
            com.cactuslauncher.model.MinecraftVersion(it.id(), it.type(), it.releasedAt(), it.latestRelease(), it.latestSnapshot())
        }
    }
    private val audioMixer = AudioMixer()
    private val macAudioMixer by lazy { MacAudioMixer() }
    private val desktopMediaPlayer = DesktopMediaPlayer()
    private val profileStore = ProfileStore(ProfileStore.defaultFile())
    private val preferencesStore = LauncherPreferencesStore(ProfileStore.defaultFile().resolveSibling("settings.properties"))
    private val releaseUpdater = GithubReleaseUpdater()
    private val buildStore = BuildStore(ProfileStore.defaultFile().resolveSibling("builds.properties"))
    private fun List<BuildStore.Build>.uiBuilds() = map {
        val folder = buildStore.folder(it.id())
        val artwork = if (it.icon() == "custom") runCatching {
            BuildIcons.read(folder)?.let { data -> org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap() }
        }.getOrNull() else null
        com.cactuslauncher.model.LauncherBuild(it.id(), it.name(), it.version(), it.loader(), lastPlayedText(buildStore.lastLaunched(it.id())), LoaderInstaller.installed(folder, it.loader(), it.version()), it.icon(), artwork)
    }
    private fun lastPlayedText(millis: Long): String = if (millis <= 0) "Ещё не запускалась" else
        java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
    override suspend fun builds() = withContext(Dispatchers.IO) { buildStore.load().uiBuilds() }
    override suspend fun createBuild(name: String, version: String, loader: String) = withContext(Dispatchers.IO) {
        require(versionCatalog.load().any { it.id() == version }) { "Версия отсутствует в каталоге Minecraft." }
        buildStore.create(name, version, loader).uiBuilds()
    }
    override suspend fun renameBuild(id: String, name: String) = withContext(Dispatchers.IO) { buildStore.rename(id, name).uiBuilds() }
    override suspend fun setBuildIcon(id: String, icon: String) = withContext(Dispatchers.IO) {
        if (icon == "custom" && !BuildIcons.choose(buildStore.folder(id))) buildStore.load().uiBuilds()
        else buildStore.setIcon(id, icon).uiBuilds()
    }
    override suspend fun deleteBuild(id: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        synchronized(runningBuilds) { require(runningBuilds[id]?.isAlive != true) { "Сначала закройте игру этой сборки." } }
        buildStore.delete(id, deleteFiles).uiBuilds()
    }
    override suspend fun openBuildFolder(id: String) = withContext(Dispatchers.IO) {
        BuildFolders.open(buildStore.folder(id))
    }
    override suspend fun installedMods(id: String) = withContext(Dispatchers.IO) {
        modrinth.installed(buildStore.modsFolder(id)).map {
            com.cactuslauncher.model.InstalledMod(it.fileName(), it.title(), it.projectId(), it.version(), it.enabled())
        }
    }
    override suspend fun scanInstalledMod(id: String, fileName: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val modsDirectory = buildStore.modsFolder(id).toAbsolutePath().normalize()
        val file = java.nio.file.Path.of(fileName)
        if (file.fileName.toString() != fileName || !fileName.endsWith(".jar", ignoreCase = true))
            throw IOException("Некорректное имя файла мода.")
        val target = modsDirectory.resolve(fileName).normalize()
        if (!target.startsWith(modsDirectory)) throw IOException("Файл мода находится вне папки сборки.")
        val report = modSecurityScanner.scan(target)
        com.cactuslauncher.model.ModSecurityReport(report.level(), report.findings(), report.scannedClasses())
    }
    override suspend fun searchMods(id: String, query: String, offset: Int) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        modrinth.search(query, build.version(), build.loader(), offset).map {
            com.cactuslauncher.model.ModrinthProject(it.id(), it.title(), it.description(), it.author(), it.downloads(), it.iconUrl())
        }
    }
    override suspend fun installMod(id: String, projectId: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        modrinth.install(projectId, build.version(), build.loader(), buildStore.modsFolder(id))
    }
    override suspend fun removeMod(id: String, fileName: String) = withContext(Dispatchers.IO) {
        modrinth.remove(fileName, buildStore.modsFolder(id))
    }
    override suspend fun setModEnabled(id: String, fileName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        modrinth.setEnabled(fileName, enabled, buildStore.modsFolder(id))
    }
    override suspend fun resourcePacks(id: String) = withContext(Dispatchers.IO) {
        resourcePackStore.list(buildStore.folder(id)).map {
            com.cactuslauncher.model.InstalledResourcePack(it.fileName(), it.title(), it.description(), it.enabled())
        }
    }
    override suspend fun importResourcePack(id: String) = withContext(Dispatchers.IO) {
        val selected = java.util.concurrent.atomic.AtomicReference<java.nio.file.Path?>()
        java.awt.EventQueue.invokeAndWait {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = "Добавить ресурспак"
                isMultiSelectionEnabled = false
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Ресурспаки Minecraft (*.zip)", "zip")
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) selected.set(chooser.selectedFile.toPath())
        }
        val source = selected.get() ?: return@withContext false
        resourcePackStore.importPack(buildStore.folder(id), source)
        true
    }
    override suspend fun setResourcePackEnabled(id: String, fileName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        resourcePackStore.setEnabled(buildStore.folder(id), fileName, enabled)
    }
    override suspend fun openResourcePacksFolder(id: String) = withContext(Dispatchers.IO) {
        BuildFolders.open(resourcePackStore.folder(buildStore.folder(id)))
    }
    override suspend fun loadPreferences() = withContext(Dispatchers.IO) {
        preferencesStore.load()?.let {
            com.cactuslauncher.model.LauncherPreferencesSnapshot(
                it.setupComplete(), it.showPlayer(), it.parallelDownloads(), it.memoryGb(), it.automaticMemory(),
                it.autoJavaOptimization(), it.ghostMode(), it.fullscreen(), it.autoUpdate(), it.advancedMode(),
                it.translateModDescriptions(), it.modAntivirusEnabled(), it.resolution(), it.folder(), it.theme(),
            )
        }
    }
    override suspend fun savePreferences(value: com.cactuslauncher.model.LauncherPreferencesSnapshot) = withContext(Dispatchers.IO) {
        preferencesStore.save(LauncherPreferencesStore.Preferences(
            value.setupComplete, value.showPlayer, value.parallelDownloads, value.memoryGb, value.automaticMemory,
            value.autoJavaOptimization, value.ghostMode, value.fullscreen, value.autoUpdate, value.advancedMode,
            value.translateModDescriptions, value.modAntivirusEnabled, value.resolution, value.folder, value.theme,
        ))
    }
    override suspend fun checkLauncherUpdate() = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val release = releaseUpdater.check()
        LauncherUpdate(release.currentVersion(), release.latestVersion(), release.releaseUrl(), release.assetName(), release.downloadUrl(), release.sha256(), release.available())
    }
    override suspend fun downloadLauncherUpdate() = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val release = releaseUpdater.check()
        if (!release.available()) throw IOException("Новой версии пока нет.")
        val updateDirectory = ProfileStore.defaultFile().resolveSibling("updates")
        releaseUpdater.download(release, updateDirectory).toString()
    }
    override suspend fun openLauncherUpdate(path: String) = withContext(Dispatchers.IO) {
        val file = java.nio.file.Path.of(path).toAbsolutePath().normalize()
        if (java.nio.file.Files.isSymbolicLink(file) || !java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw IOException("Файл обновления не найден.")
        BuildFolders.open(file)
    }
    override suspend fun chooseDirectory(current: String) = withContext(Dispatchers.IO) {
        val selected = java.util.concurrent.atomic.AtomicReference<java.nio.file.Path?>()
        java.awt.EventQueue.invokeAndWait {
            val initialDirectory = runCatching { java.io.File(current).takeIf { it.isDirectory } }.getOrNull()
            val chooser = javax.swing.JFileChooser(initialDirectory).apply {
                dialogTitle = "Выбрать папку для игры"
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.selectedFile.toPath())
            }
        }
        selected.get()?.toAbsolutePath()?.normalize()?.toString()
    }
    override suspend fun modIcon(url: String) = withContext(Dispatchers.IO) {
        modrinth.icon(url)?.let { bytes -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
    }
    override suspend fun translateModDescription(text: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        translator.toRussian(text)
    }
    override suspend fun openModsFolder(id: String) = withContext(Dispatchers.IO) {
        BuildFolders.open(buildStore.modsFolder(id))
    }
    @Volatile private var downloadControl: DownloadControl? = null
    override fun pauseInstallation(paused: Boolean) { downloadControl?.setPaused(paused) }
    override suspend fun installBuild(id: String, parallelDownloads: Int, onProgress: (com.cactuslauncher.model.InstallProgress) -> Unit) {
        val control = DownloadControl()
        synchronized(this) {
            check(downloadControl == null) { "Установка уже выполняется." }
            downloadControl = control
        }
        try {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
            val folder = buildStore.folder(id)
            VersionInstaller(folder, {
                onProgress(com.cactuslauncher.model.InstallProgress(it.fraction().toFloat(), it.stage(), it.lastFile(), it.completed(), it.total(), it.bytesPerSecond(), it.remainingSeconds(), it.phase()))
            }, control, parallelDownloads).install(build.version())
            if (build.loader() != "Vanilla") LoaderInstaller(folder, {
                onProgress(com.cactuslauncher.model.InstallProgress(0.98f, it.stage(), "", 0, 0, 0.0, -1, 2))
            }).install(build.loader(), build.version())
        }
        } finally { downloadControl = null }
    }
    private val runningBuilds = java.util.concurrent.ConcurrentHashMap<String, Process>()
    override suspend fun runningBuildIds(): Set<String> = withContext(Dispatchers.IO) {
        synchronized(runningBuilds) {
            runningBuilds.entries.removeIf { !it.value.isAlive }
            runningBuilds.keys.toSet()
        }
    }
    private val launchFailures = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    override suspend fun takeLaunchFailures(): List<Pair<String, String>> = buildList { while (true) add(launchFailures.poll() ?: break) }
    private fun launchFailureMessage(id: String, exitCode: Int, onlyKnown: Boolean): String? {
        val diagnosis = try {
            buildStore.load().find { it.id() == id }?.let { CrashDiagnosis().analyze(buildStore.folder(id), it.version(), it.loader()) }
        } catch (_: Exception) { null }
        val known = diagnosis != null && diagnosis.title() != CrashDiagnosis.UNKNOWN_TITLE && diagnosis.title() != CrashDiagnosis.NO_LOGS_TITLE
        if (onlyKnown && !known) return null
        val reason = if (known) " ${diagnosis!!.title()}. ${diagnosis.detail()}" else ""
        return "Minecraft завершился с ошибкой (код $exitCode).$reason Подробности — в логах сборки."
    }
    private fun watchLaunchedProcess(id: String, process: Process) {
        val started = System.nanoTime()
        // Wait a moment after exit so the output logger finishes writing cactus-launch.log.
        process.onExit().thenAcceptAsync({ exited ->
            val code = exited.exitValue()
            val quickExit = System.nanoTime() - started < 90_000_000_000L
            if (code != 0 || quickExit) launchFailureMessage(id, code, onlyKnown = code == 0)?.let { launchFailures.add(id to it) }
        }, java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS))
    }
    override suspend fun buildLogs(id: String): List<String> = withContext(Dispatchers.IO) {
        buildStore.readLogs(id, 2_000)
    }
    override suspend fun diagnoseBuild(id: String) = withContext(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        val result = CrashDiagnosis().analyze(buildStore.folder(id), build.version(), build.loader())
        com.cactuslauncher.model.CrashDiagnosis(result.title(), result.detail(), result.evidence(), result.suspectMod(), result.confidence(), result.recoverable())
    }
    override suspend fun openBuildLogs(id: String) = withContext(Dispatchers.IO) {
        val logFile = buildStore.logFile(id)
        if (!java.nio.file.Files.isRegularFile(logFile)) throw IOException("Файл лога ещё не создан.")
        BuildFolders.open(logFile)
    }
    private val injectorRoot = ProfileStore.defaultFile().resolveSibling("authlib-injector")
    override fun externalAccountName(): String? = externalSession?.name()
    override fun signOutExternal() { externalSession = null }
    override suspend fun signInExternal(apiRoot: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        val session = ExternalAuth().authenticate(apiRoot, username, password)
        externalSession = session
        session.name()
    }
    override suspend fun launchBuild(id: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean, ghostMode: Boolean): Boolean {
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
            val folder = buildStore.folder(id)
            require(LoaderInstaller.installed(folder, build.loader(), build.version())) { "Сначала установите Minecraft и выбранный загрузчик." }
            val launchVersion = LoaderInstaller.profileId(folder, build.loader(), build.version())
            val session = externalSession?.takeIf { !authlibUrl.isNullOrBlank() }
            val url = session?.apiRoot()
            val injector = if (session != null && !url.isNullOrBlank()) AuthlibInjector(injectorRoot).ensure() else null
            synchronized(runningBuilds) {
                require(runningBuilds[id]?.isAlive != true) { "Эта сборка уже запущена." }
                val profile = profileStore.load().let { snapshot -> snapshot.profiles().first { it.id() == snapshot.activeId() } }
                buildStore.markLaunched(id)
                val process = MinecraftLauncher.launch(folder, launchVersion, profile.name(), memoryGb, session, injector, url, automaticHeap, automaticOptimization, ghostMode)
                if (process == null) return@runInterruptible true
                runningBuilds[id] = process
                if (process.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Thread.sleep(300)
                    throw IOException(launchFailureMessage(id, process.exitValue(), onlyKnown = false))
                }
                watchLaunchedProcess(id, process)
                false
            }
        }
    }
    override suspend fun launchRecoveryBuild(id: String, excludedMod: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
            val folder = buildStore.folder(id)
            require(LoaderInstaller.installed(folder, build.loader(), build.version())) { "Сначала установите Minecraft и выбранный загрузчик." }
            val diagnosis = CrashDiagnosis().analyze(folder, build.version(), build.loader())
            require(diagnosis.recoverable() && diagnosis.suspectMod() == excludedMod) { "Этот мод нельзя безопасно убрать из пробного запуска." }
            synchronized(runningBuilds) {
                require(runningBuilds[id]?.isAlive != true) { "Эта сборка уже запущена." }
                val workspace = CrashRecovery().createWorkspace(folder, folder.parent.resolve("recovery"), excludedMod)
                try {
                    val profile = profileStore.load().let { snapshot -> snapshot.profiles().first { it.id() == snapshot.activeId() } }
                    val session = externalSession?.takeIf { !authlibUrl.isNullOrBlank() }
                    val url = session?.apiRoot()
                    val injector = if (session != null && !url.isNullOrBlank()) AuthlibInjector(injectorRoot).ensure() else null
                    val launchVersion = LoaderInstaller.profileId(folder, build.loader(), build.version())
                    val process = MinecraftLauncher.launchInDirectory(folder, workspace, launchVersion, profile.name(), memoryGb,
                        session, injector, url, automaticHeap, automaticOptimization, false)
                    runningBuilds[id] = process
                    Thread({
                        try { process.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        finally {
                            synchronized(runningBuilds) { if (runningBuilds[id] === process) runningBuilds.remove(id) }
                            try { CrashRecovery().deleteWorkspace(workspace) } catch (_: IOException) { }
                        }
                    }, "cactus-recovery-cleanup").apply { isDaemon = true; start() }
                    if (process.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        synchronized(runningBuilds) { runningBuilds.remove(id, process) }
                        throw IOException("Пробный запуск завершился с кодом ${process.exitValue()}. Его вывод добавлен в logs/cactus-launch.log.")
                    }
                } catch (error: Exception) {
                    if (runningBuilds[id]?.isAlive != true) {
                        runningBuilds.remove(id)
                        try { CrashRecovery().deleteWorkspace(workspace) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
                    }
                    throw error
                }
            }
        }
    }
    override suspend fun recommendedMemoryGb() = withContext(Dispatchers.IO) {
        javaOptimizer.recommendedHeapGb()
    }
    override suspend fun javaOptimizationInfo() = withContext(Dispatchers.IO) {
        val machine = javaOptimizer.inspect()
        com.cactuslauncher.model.JavaOptimizationInfo(
            (machine.totalMemoryBytes() / (1024L * 1024L * 1024L)).toInt(),
            (machine.availableMemoryBytes() / (1024L * 1024L * 1024L)).toInt(),
            machine.processors(), machine.processorName(), machine.graphicsName(),
            machine.javaVersion() + " (" + System.getProperty("java.vendor").orEmpty().ifBlank { "производитель не указан" } + ")",
            JavaOptimizer.maxSafeHeapGb(machine),
        )
    }
    override suspend fun javaOptimizationPlan(buildId: String, memoryGb: Int, automaticHeap: Boolean, automaticOptimization: Boolean) = withContext(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == buildId } ?: throw IOException("Сборка не найдена.")
        val folder = buildStore.folder(buildId)
        val version = if (LoaderInstaller.installed(folder, build.loader(), build.version()))
            LoaderInstaller.profileId(folder, build.loader(), build.version()) else build.version()
        val plan = MinecraftLauncher.optimizationPlan(folder, version, memoryGb, automaticHeap, automaticOptimization)
        com.cactuslauncher.model.JavaOptimizationPlan(plan.heapGb(), plan.collector(), plan.arguments(), plan.modJarCount())
    }
    private fun ProfileStore.Snapshot.ui() = ProfileSnapshot(profiles().map { PlayerProfile(it.id(), it.name()) }, activeId())
    override suspend fun profiles() = withContext(Dispatchers.IO) { profileStore.load().ui() }
    override suspend fun addProfile(name: String) = withContext(Dispatchers.IO) { profileStore.add(name).ui() }
    override suspend fun renameProfile(id: String, name: String) = withContext(Dispatchers.IO) { profileStore.rename(id, name).ui() }
    override suspend fun selectProfile(id: String) = withContext(Dispatchers.IO) { profileStore.select(id).ui() }
    override suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) { profileStore.delete(id).ui() }
    override fun gameDir(): String = core.defaultGameDir()
    override suspend fun audioSessions() = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("mac") || os.contains("darwin")) {
            AudioMixerSnapshot(macAudioMixer.sessions(), supported = true)
        } else if (!os.contains("linux")) {
            AudioMixerSnapshot(
                emptyList(),
                supported = false,
                message = when {
                    os.contains("win") -> "Микшер приложений для Windows пока не подключён."
                    else -> "Микшер приложений не поддерживается на этой ОС."
                },
            )
        } else {
            AudioMixerSnapshot(
                audioMixer.sessions().map {
                    AudioSession(it.id(), it.name(), it.volume(), it.muted())
                },
                supported = true,
            )
        }
    }
    override suspend fun setAudioSessionVolume(id: String, volume: Float) = withContext(Dispatchers.IO) {
        when {
            isMac() -> macAudioMixer.setVolume(id, volume)
            isLinux() -> audioMixer.setVolume(id, volume)
            else -> throw IOException("Управление звуком приложений не подключено для этой ОС.")
        }
    }
    override suspend fun setAudioSessionMuted(id: String, muted: Boolean) = withContext(Dispatchers.IO) {
        when {
            isMac() -> macAudioMixer.setMuted(id, muted)
            isLinux() -> audioMixer.setMuted(id, muted)
            else -> throw IOException("Управление звуком приложений не подключено для этой ОС.")
        }
    }

    override suspend fun controlTrack(command: TrackCommand) = withContext(Dispatchers.IO) {
        if (isMac()) macAudioMixer.controlTrack(command)
        else desktopMediaPlayer.control(command)
    }

    override suspend fun currentTrack(): TrackSnapshot? = withContext(Dispatchers.IO) {
        if (isMac()) macAudioMixer.currentTrack() else desktopMediaPlayer.currentTrack()
    }

    override suspend fun setPlayerEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        if (isMac()) macAudioMixer.setPlayerEnabled(enabled) else desktopMediaPlayer.setEnabled(enabled)
    }

    private fun isLinux() = System.getProperty("os.name", "").lowercase().contains("linux")
    private fun isMac(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("mac") || os.contains("darwin")
    }
}
