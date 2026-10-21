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
import kotlinx.coroutines.sync.withPermit
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
        val stats = Playtime(folder).read()
        val lastPlayed = lastPlayedText(maxOf(buildStore.lastLaunched(it.id()), stats.lastPlayedMillis()))
        val zone = java.time.ZoneId.systemDefault()
        com.cactuslauncher.model.LauncherBuild(it.id(), it.name(), it.version(), it.loader(), lastPlayed, LoaderInstaller.installed(folder, it.loader(), it.version()), it.icon(), artwork,
            playtime = Playtime.duration(stats.totalMillis()),
            playSummary = Playtime.summary(stats, System.currentTimeMillis(), zone).ifBlank { lastPlayed },
            autoUpdateMods = it.autoUpdateMods())
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
    override suspend fun setAutoUpdateMods(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { buildStore.setAutoUpdateMods(id, enabled).uiBuilds() }
    private val modUpdater by lazy { ModUpdater(modrinth) }
    private fun modBuild(id: String): BuildStore.Build {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        if (build.loader() == "Vanilla") throw IOException("В сборке Vanilla нет модов для обновления.")
        return build
    }
    override suspend fun modUpdates(id: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = modBuild(id)
        modUpdater.check(buildStore.modsFolder(id), build.version(), build.loader()).map { update ->
            com.cactuslauncher.model.ModUpdate(update.fileName(), update.title(), update.currentVersion(), update.newVersion(),
                update.newDependencies().map { it.title() })
        }
    }
    override suspend fun applyModUpdates(id: String, fileNames: Set<String>?) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = modBuild(id)
        synchronized(runningBuilds) { require(runningBuilds[id]?.isAlive != true && id !in launchingBuilds) { "Сначала закройте игру этой сборки." } }
        val result = modUpdater.apply(buildStore.modsFolder(id), build.version(), build.loader(), fileNames, true, 0)
        com.cactuslauncher.model.ModUpdateResult(result.updated(), result.dependencies())
    }
    override suspend fun lastModUpdate(id: String) = withContext(Dispatchers.IO) {
        ModBackups.latest(buildStore.folder(id))?.let {
            com.cactuslauncher.model.ModUpdateBackup(
                lastPlayedText(it.createdMillis()), it.replaced(), it.added(), it.confirmed(),
                java.text.SimpleDateFormat("dd.MM").format(java.util.Date(it.createdMillis())),
            )
        }
    }
    override suspend fun rollbackModUpdate(id: String) = withContext(Dispatchers.IO) {
        synchronized(runningBuilds) { require(runningBuilds[id]?.isAlive != true && id !in launchingBuilds) { "Сначала закройте игру этой сборки." } }
        ModBackups.rollbackLatest(buildStore.folder(id))
    }
    /**
     * Auto-update before launch: at most ~20 s, skips updates that need new dependencies, and any failure
     * (offline, timeout, Modrinth error) only goes to the error log so the game still starts.
     */
    private fun autoUpdateMods(build: BuildStore.Build, folder: java.nio.file.Path) {
        if (!build.autoUpdateMods() || build.loader() == "Vanilla") return
        val worker = Thread({
            try {
                modUpdater.apply(folder.resolve("mods"), build.version(), build.loader(), null, false, System.nanoTime() + 20_000_000_000L)
            } catch (e: Exception) {
                reportError("mod-auto-update", e)
            }
        }, "cactus-mod-auto-update").apply { isDaemon = true; start() }
        worker.join(25_000)
        if (worker.isAlive) {
            worker.interrupt()
            // Downloads stop on interrupt; wait for a commit that is already moving files.
            worker.join(10_000)
        }
    }
    private val playSessions = java.util.concurrent.ConcurrentHashMap<Process, Playtime.Session>()
    private val playtimeCheckpoints = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cactus-playtime").apply { isDaemon = true }
    }.also { executor ->
        executor.scheduleWithFixedDelay({
            playSessions.values.forEach { session ->
                try { session.checkpoint(System.currentTimeMillis()) } catch (e: Exception) { reportError("playtime-checkpoint", e) }
            }
        }, Playtime.CHECKPOINT_MILLIS, Playtime.CHECKPOINT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    /** Counts the time [process] runs into the build's play time (checkpointed every 30 s). */
    private fun trackPlaytime(folder: java.nio.file.Path, process: Process) {
        val session = Playtime(folder).start(System.currentTimeMillis())
        playSessions[process] = session
        process.onExit().thenRun {
            playSessions.remove(process)
            try { session.stop(System.currentTimeMillis()) } catch (e: Exception) { reportError("playtime-stop", e) }
        }
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
    override suspend fun modProblems(id: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        if (build.loader() == "Vanilla") return@runInterruptible emptyList()
        val folder = buildStore.folder(id)
        val environment = ModConflicts.environment(folder, build.loader(), build.version())
        ModConflicts.check(buildStore.modsFolder(id), environment, modMetadataCache).map {
            com.cactuslauncher.model.ModProblem(it.fileName(), it.modName(), it.severity() == ModConflicts.Severity.ERROR,
                it.message(), it.dependencyId(), it.otherFile())
        }
    }
    private val modMetadataCache = java.util.concurrent.ConcurrentHashMap<String, ModMetadata.Jar>()
    override suspend fun findModrinthProject(id: String, slug: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
        modrinth.findCompatible(slug, build.version(), build.loader())?.let {
            com.cactuslauncher.model.ModrinthProject(it.id(), it.title(), it.description(), it.author(), it.downloads(), it.iconUrl())
        }
    }

    private val screenshotCache = ProfileStore.defaultFile().resolveSibling("cache").resolve("screenshots")
    /** Thumbnails by path, time and size; ~160 thumbnails of 480 px are about 100 MB of pixels at most. */
    private val thumbnails = object : java.util.LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > 160
    }
    private val thumbnailPermits = kotlinx.coroutines.sync.Semaphore(3)
    private fun screenshotFile(id: String, fileName: String) = Screenshots.resolve(buildStore.folder(id), fileName)
    override suspend fun screenshots(id: String) = withContext(Dispatchers.IO) {
        Screenshots.list(buildStore.folder(id)).map {
            com.cactuslauncher.model.Screenshot(it.fileName(), it.modifiedMillis(), it.size(), lastPlayedText(it.modifiedMillis()))
        }
    }
    override suspend fun otherOverview() = withContext(Dispatchers.IO) {
        var shots = 0
        var latest: Pair<String, Screenshots.Shot>? = null
        var servers = 0
        var serverBuilds = 0
        for (build in buildStore.load()) {
            val folder = buildStore.folder(build.id())
            // One unreadable build folder must not hide the numbers of the others.
            try {
                val list = Screenshots.list(folder)
                shots += list.size
                val newest = list.firstOrNull()
                if (newest != null && (latest == null || newest.modifiedMillis() > latest.second.modifiedMillis())) latest = build.id() to newest
            } catch (_: IOException) { }
            try {
                val visible = ServerList.read(folder).count { !it.hidden() }
                servers += visible
                if (visible > 0) serverBuilds++
            } catch (_: IOException) { }
        }
        com.cactuslauncher.model.OtherOverview(shots, latest?.let { (id, shot) ->
            id to com.cactuslauncher.model.Screenshot(shot.fileName(), shot.modifiedMillis(), shot.size(), lastPlayedText(shot.modifiedMillis()))
        }, servers, serverBuilds)
    }
    override suspend fun screenshotThumbnail(id: String, shot: com.cactuslauncher.model.Screenshot): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "$id/${shot.fileName}/${shot.modifiedMillis}/${shot.size}"
        synchronized(thumbnails) { thumbnails[key] }?.let { return it }
        return thumbnailPermits.withPermit {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                val bytes = Screenshots.thumbnail(screenshotFile(id, shot.fileName), 480, screenshotCache)
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { synchronized(thumbnails) { thumbnails[key] = it } }
            }
        }
    }
    override suspend fun screenshotImage(id: String, shot: com.cactuslauncher.model.Screenshot, maxSize: Int) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        Screenshots.readScaled(screenshotFile(id, shot.fileName), maxSize.coerceIn(256, 4096)).toComposeImageBitmap()
    }
    override suspend fun openScreenshot(id: String, fileName: String) = withContext(Dispatchers.IO) {
        BuildFolders.open(screenshotFile(id, fileName))
    }
    override suspend fun revealScreenshot(id: String, fileName: String) = withContext(Dispatchers.IO) {
        val file = screenshotFile(id, fileName)
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(file.toFile())
        else BuildFolders.open(file.parent)
    }
    override suspend fun copyScreenshot(id: String, fileName: String) = withContext(Dispatchers.IO) {
        val image = javax.imageio.ImageIO.read(screenshotFile(id, fileName).toFile()) ?: throw IOException("Не удалось прочитать скриншот.")
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                return image
            }
        }
        java.awt.EventQueue.invokeAndWait { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null) }
    }
    override suspend fun deleteScreenshot(id: String, fileName: String) = withContext(Dispatchers.IO) {
        Screenshots.delete(buildStore.folder(id), fileName)
    }
    override suspend fun openScreenshotsFolder(id: String) = withContext(Dispatchers.IO) {
        val folder = Screenshots.folder(buildStore.folder(id))
        java.nio.file.Files.createDirectories(folder)
        BuildFolders.open(folder)
    }
    private fun serverIcon(data: String?): androidx.compose.ui.graphics.ImageBitmap? = try {
        data?.let { org.jetbrains.skia.Image.makeFromEncoded(java.util.Base64.getMimeDecoder().decode(it)).toComposeImageBitmap() }
    } catch (_: Exception) { null }
    private fun serverModels(id: String) = ServerList.read(buildStore.folder(id)).map {
        com.cactuslauncher.model.MinecraftServer(it.index(), it.name(), it.ip(), it.icon(), serverIcon(it.icon()), it.hidden())
    }
    override suspend fun servers(id: String) = withContext(Dispatchers.IO) { serverModels(id) }
    override fun validateServer(name: String, address: String): String? = try {
        ServerList.validName(name); ServerList.validAddress(address); null
    } catch (e: IllegalArgumentException) { e.message }
    override suspend fun addServer(id: String, name: String, address: String) = withContext(Dispatchers.IO) {
        ServerList.add(buildStore.folder(id), name, address)
        serverModels(id)
    }
    override suspend fun editServer(id: String, index: Int, expectedIp: String, name: String, address: String) = withContext(Dispatchers.IO) {
        ServerList.edit(buildStore.folder(id), index, expectedIp, name, address)
        serverModels(id)
    }
    override suspend fun removeServer(id: String, index: Int, expectedIp: String) = withContext(Dispatchers.IO) {
        ServerList.remove(buildStore.folder(id), index, expectedIp)
        serverModels(id)
    }
    override suspend fun setServerIcon(id: String, index: Int, expectedIp: String, favicon: String) = withContext(Dispatchers.IO) {
        ServerList.setIcon(buildStore.folder(id), index, expectedIp, favicon)
    }
    override suspend fun pingServer(address: String) = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val status = ServerPing.ping(address, 3000)
        com.cactuslauncher.model.ServerStatus(status.motd(), status.online(), status.max(), status.version(), status.pingMillis(),
            status.favicon(), serverIcon(status.favicon()))
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
    /** Builds between "launch pressed" and "process registered": Java/authlib downloads and Ghost Mode run here, outside the lock. */
    private val launchingBuilds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override suspend fun runningBuildIds(): Set<String> = withContext(Dispatchers.IO) {
        synchronized(runningBuilds) {
            runningBuilds.entries.removeIf { !it.value.isAlive }
            runningBuilds.keys.toSet() + launchingBuilds
        }
    }
    /** Reserves [id] for one launch at a time; the lock only covers this check, never downloads or process start. */
    private fun reserveLaunch(id: String) = synchronized(runningBuilds) {
        require(runningBuilds[id]?.isAlive != true && id !in launchingBuilds) { "Эта сборка уже запущена." }
        launchingBuilds.add(id)
    }
    private fun registerProcess(id: String, process: Process) = synchronized(runningBuilds) { runningBuilds[id] = process }
    private fun unregisterProcess(id: String, process: Process) = synchronized(runningBuilds) { runningBuilds.remove(id, process) }
    private fun activeProfile(): ProfileStore.Profile {
        val snapshot = profileStore.load()
        return snapshot.profiles().firstOrNull { it.id() == snapshot.activeId() }
            ?: snapshot.profiles().firstOrNull()
            ?: throw IOException("Создайте профиль игрока перед запуском.")
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
            // A normal session after a mod update: it is no longer offered for rollback after a crash.
            else try { ModBackups.confirmLatest(buildStore.folder(id)) } catch (e: Exception) { reportError("mod-backup-confirm", e) }
        }, java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS))
    }
    override suspend fun buildLogChunk(id: String, offset: Long): com.cactuslauncher.model.LogChunk = withContext(Dispatchers.IO) {
        val chunk = buildStore.readLogChunk(id, offset, 1024 * 1024)
        com.cactuslauncher.model.LogChunk(chunk.lines(), chunk.nextOffset(), chunk.reset())
    }
    private val errorLog = ProfileStore.defaultFile().resolveSibling("launcher-errors.log")
    override fun reportError(context: String, error: Throwable) {
        val line = "${java.time.OffsetDateTime.now()} [$context] ${error.javaClass.name}: ${error.message.orEmpty()}"
        System.err.println(line)
        try {
            synchronized(errorLog) {
                if (java.nio.file.Files.isSymbolicLink(errorLog)) return
                if (java.nio.file.Files.isRegularFile(errorLog) && java.nio.file.Files.size(errorLog) > 1024 * 1024)
                    java.nio.file.Files.move(errorLog, errorLog.resolveSibling("launcher-errors.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                java.nio.file.Files.writeString(errorLog, line + "\n", Charsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            }
        } catch (_: IOException) {
            // stderr already has the line; a read-only data folder must not break the UI.
        }
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
    /**
     * The third-party session for [authlibUrl], checked with the server right before launch.
     * Blank [authlibUrl] means an offline profile. A refused token signs the user out.
     */
    private fun launchSession(authlibUrl: String?): ExternalAuth.Session? {
        if (authlibUrl.isNullOrBlank()) return null
        val current = externalSession?.takeIf { it.matches(authlibUrl) }
            ?: throw IOException("Нет входа на сервер авторизации $authlibUrl. Войдите заново в профилях.")
        val fresh = try {
            ExternalAuth().ensureValid(current)
        } catch (rejected: ExternalAuth.RejectedException) {
            synchronized(this) { if (externalSession === current) externalSession = null }
            throw rejected
        }
        if (fresh !== current) synchronized(this) { if (externalSession === current) externalSession = fresh }
        return fresh
    }
    override fun externalAccountName(): String? = externalSession?.name()
    override fun signOutExternal() { externalSession = null }
    override suspend fun signInExternal(apiRoot: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        val session = ExternalAuth().authenticate(apiRoot, username, password)
        externalSession = session
        session.name()
    }
    override suspend fun launchBuild(id: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean, ghostMode: Boolean, server: String?): Boolean {
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
            val folder = buildStore.folder(id)
            require(LoaderInstaller.installed(folder, build.loader(), build.version())) { "Сначала установите Minecraft и выбранный загрузчик." }
            val joinArguments = server?.let { ServerList.quickJoinArguments(build.version(), it) } ?: emptyList()
            val launchVersion = LoaderInstaller.profileId(folder, build.loader(), build.version())
            val profile = activeProfile()
            reserveLaunch(id)
            try {
                autoUpdateMods(build, folder)
                val session = launchSession(authlibUrl)
                val url = session?.apiRoot()
                val injector = if (session != null) AuthlibInjector(injectorRoot).ensure() else null
                val process = MinecraftLauncher.launch(folder, launchVersion, profile.name(), memoryGb, session, injector, url, automaticHeap, automaticOptimization, ghostMode, joinArguments)
                if (process == null) {
                    // Ghost Mode: the background process counts play time (GhostModeDaemon).
                    markLaunchedQuietly(id)
                    return@runInterruptible true
                }
                trackPlaytime(folder, process)
                registerProcess(id, process)
                if (process.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    unregisterProcess(id, process)
                    Thread.sleep(300)
                    throw IOException(launchFailureMessage(id, process.exitValue(), onlyKnown = false))
                }
                markLaunchedQuietly(id)
                watchLaunchedProcess(id, process)
                false
            } finally {
                launchingBuilds.remove(id)
            }
        }
    }
    /** The game is already running, so a failed "last played" write must not be reported as a launch error. */
    private fun markLaunchedQuietly(id: String) {
        try { buildStore.markLaunched(id) } catch (_: IOException) { }
    }
    override suspend fun launchRecoveryBuild(id: String, excludedMod: String, memoryGb: Int, authlibUrl: String?, automaticHeap: Boolean, automaticOptimization: Boolean) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val build = buildStore.load().find { it.id() == id } ?: throw IOException("Сборка не найдена.")
            val folder = buildStore.folder(id)
            require(LoaderInstaller.installed(folder, build.loader(), build.version())) { "Сначала установите Minecraft и выбранный загрузчик." }
            val diagnosis = CrashDiagnosis().analyze(folder, build.version(), build.loader())
            require(diagnosis.recoverable() && diagnosis.suspectMod() == excludedMod) { "Этот мод нельзя безопасно убрать из пробного запуска." }
            val launchVersion = LoaderInstaller.profileId(folder, build.loader(), build.version())
            val profile = activeProfile()
            reserveLaunch(id)
            try {
                val session = launchSession(authlibUrl)
                val url = session?.apiRoot()
                val injector = if (session != null) AuthlibInjector(injectorRoot).ensure() else null
                val workspace = CrashRecovery().createWorkspace(folder, folder.parent.resolve("recovery"), excludedMod)
                var process: Process? = null
                try {
                    val started = MinecraftLauncher.launchInDirectory(folder, workspace, launchVersion, profile.name(), memoryGb,
                        session, injector, url, automaticHeap, automaticOptimization, false)
                    process = started
                    trackPlaytime(folder, started)
                    registerProcess(id, started)
                    Thread({
                        try { started.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        finally {
                            unregisterProcess(id, started)
                            try { CrashRecovery().deleteWorkspace(workspace) } catch (_: IOException) { }
                        }
                    }, "cactus-recovery-cleanup").apply { isDaemon = true; start() }
                    if (started.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        unregisterProcess(id, started)
                        throw IOException("Пробный запуск завершился с кодом ${started.exitValue()}. Его вывод добавлен в logs/cactus-launch.log.")
                    }
                } catch (error: Exception) {
                    val running = process
                    if (running == null) {
                        try { CrashRecovery().deleteWorkspace(workspace) } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
                    } else if (!running.isAlive) unregisterProcess(id, running)
                    throw error
                }
            } finally {
                launchingBuilds.remove(id)
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
    private val profileAvatars = ProfileAvatars(ProfileAvatars.defaultFolder())
    private fun ProfileStore.Snapshot.ui() = ProfileSnapshot(profiles().map { PlayerProfile(it.id(), it.name(), avatarImage(it)) }, activeId())
    /** A missing or unreadable picture falls back to the default artwork; the failure is only logged. */
    private fun avatarImage(profile: ProfileStore.Profile): androidx.compose.ui.graphics.ImageBitmap? {
        if (profile.avatar() == null) return null
        return try {
            profileAvatars.read(profile.id())?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
        } catch (e: Exception) {
            reportError("profile-avatar-read", e)
            null
        }
    }
    override suspend fun profiles() = withContext(Dispatchers.IO) { profileStore.load().ui() }
    override suspend fun addProfile(name: String) = withContext(Dispatchers.IO) { profileStore.add(name).ui() }
    override suspend fun renameProfile(id: String, name: String) = withContext(Dispatchers.IO) { profileStore.rename(id, name).ui() }
    override suspend fun selectProfile(id: String) = withContext(Dispatchers.IO) { profileStore.select(id).ui() }
    override suspend fun deleteProfile(id: String) = withContext(Dispatchers.IO) {
        val next = profileStore.delete(id)
        try { profileAvatars.delete(id) } catch (e: Exception) { reportError("profile-avatar-delete", e) }
        next.ui()
    }
    override suspend fun chooseProfileAvatar(id: String) = withContext(Dispatchers.IO) {
        val source = ProfileAvatars.choose() ?: return@withContext profileStore.load().ui()
        val version = profileAvatars.store(id, source)
        try {
            profileStore.setAvatar(id, version).ui()
        } catch (e: Exception) {
            // The profile vanished or the file could not be saved: do not leave an orphaned picture.
            runCatching { profileAvatars.delete(id) }
            throw e
        }
    }
    override suspend fun removeProfileAvatar(id: String) = withContext(Dispatchers.IO) {
        val next = profileStore.setAvatar(id, null)
        profileAvatars.delete(id)
        next.ui()
    }
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
