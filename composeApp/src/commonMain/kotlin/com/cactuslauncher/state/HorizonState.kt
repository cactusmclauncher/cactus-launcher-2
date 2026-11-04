package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.game.HorizonInstallation
import com.cactuslauncher.game.HorizonProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class HorizonState(private val game: GameService, private val scope: CoroutineScope) {
    var installation by mutableStateOf<HorizonInstallation?>(null)
        private set
    var latest by mutableStateOf("")
        private set
    var prepared by mutableStateOf(false)
        private set
    var source by mutableStateOf("")
    var javaHome by mutableStateOf("")
    var progress by mutableStateOf<HorizonProgress?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var runningProfile by mutableStateOf("")
        private set
    var launching by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var installOpen by mutableStateOf(false)
        private set
    var installPhase by mutableStateOf(0)
        private set
    var installProgress by mutableStateOf(HorizonProgress("Подготовка установки…", 0f))
        private set
    private var job: Job? = null

    private suspend fun <T> progressOperation(start: Float = 0f, end: Float = 1f, action: suspend ((HorizonProgress) -> Unit) -> T): T = coroutineScope {
        val updates = Channel<HorizonProgress>(Channel.CONFLATED)
        val collector = launch {
            for (update in updates) {
                val scaled = update.copy(fraction = start + (end - start) * update.fraction.coerceIn(0f, 1f))
                progress = scaled
                if (installOpen) installProgress = scaled
            }
        }
        try { action { updates.trySend(it) } }
        finally { updates.close(); collector.join() }
    }

    private suspend fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        job = kotlinx.coroutines.currentCoroutineContext()[Job]
        error = null
        progress = null
        status = null
        try { action() }
        catch (cancelled: CancellationException) {
            installOpen = false
            status = if (launching) "Horizon остановлен. Файлы профиля сохранены; несохранённые изменения игры могли потеряться." else "Операция отменена; завершённые файлы кэша сохранены."
            throw cancelled
        }
        catch (failure: Exception) { error = failure.message ?: "Операция Horizon не выполнена." }
        finally { busy = false; progress = null; job = null; running = false; launching = false; runningProfile = "" }
    }

    suspend fun startup(automatic: Boolean) = execute {
        installation = game.horizonInstallation()
        if (source.isBlank()) source = game.horizonMinecraftSource()
        if (javaHome.isBlank()) javaHome = game.horizonJavaHome()
        prepared = game.horizonPrepared()
        if (!automatic) {
            status = if (prepared) "Готов к игре офлайн" else if (installation != null) "Нужно завершить установку" else "Всё необходимое скачается автоматически"
            return@execute
        }
        val canUpdate = game.runningBuildIds().isEmpty()
        val update = progressOperation { game.checkHorizonUpdate(canUpdate, it) }
        installation = update.installation
        latest = update.latest
        status = update.status
        prepared = game.horizonPrepared()
    }

    private fun operation(action: suspend () -> Unit) {
        if (busy || job?.isActive == true) return
        job = scope.launch { try { execute(action) } finally { job = null } }
    }

    fun launch() = operation {
        launching = true
        try {
            coroutineScope {
                val updates = Channel<String>(Channel.CONFLATED)
                val collector = launch {
                    for (name in updates) {
                        runningProfile = name
                        running = true
                        status = "Игра запущена · $name"
                    }
                }
                val result = try { game.launchHorizon { updates.trySend(it) } }
                finally { updates.close(); collector.join() }
                val message = when (result.exitCode) {
                    0 -> "Horizon закрыт."
                    2 -> "Horizon отклонил параметры запуска (код 2)."
                    101 -> "Horizon аварийно завершился (код 101)."
                    else -> "Horizon завершился с ошибкой (код ${result.exitCode})."
                }
                status = "$message Журнал: ${result.logPath}"
                if (result.exitCode != 0) error = status
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    fun check() = operation {
        val update = progressOperation { game.checkHorizonUpdate(false, it) }
        installation = update.installation
        latest = update.latest
        status = update.status
        prepared = game.horizonPrepared()
    }

    fun installAutomatic() {
        if (busy || job?.isActive == true) return
        source = ""
        javaHome = ""
        install()
    }

    fun install() {
        if (busy || job?.isActive == true) return
        installOpen = true
        installPhase = 0
        installProgress = HorizonProgress("Подготовка установки…", 0f)
        operation {
            installation = progressOperation(0f, 0.3f) { game.installHorizon(it) }
            latest = installation?.release.orEmpty()
            prepared = game.horizonPrepared()
            installPhase = 1
            if (!prepared) {
                status = progressOperation(0.3f, 0.99f) { game.prepareHorizon(source, javaHome, it) }
                prepared = game.horizonPrepared()
            }
            if (!prepared) error("Preparer не подтвердил готовность ресурсов.")
            installPhase = 2
            installProgress = HorizonProgress("Готов к игре офлайн", 1f)
            status = "Готов к игре офлайн"
            installation = game.horizonInstallation()
        }
    }

    fun prepare() = operation {
        status = progressOperation { game.prepareHorizon(source, javaHome, it) }
        prepared = game.horizonPrepared()
        installation = game.horizonInstallation()
    }

    fun chooseSource() = operation { game.chooseDirectory(source)?.let { source = it } }
    fun chooseJava() = operation { game.chooseDirectory(javaHome)?.let { javaHome = it } }
    fun validate() = operation { status = game.validateHorizonMinecraft(source) }
    fun rollback() = operation {
        installation = game.rollbackHorizon()
        prepared = game.horizonPrepared()
        status = "Восстановлена предыдущая установка Horizon."
    }
    fun closeInstall() { if (busy) cancel() else installOpen = false }
    fun cancel() { installOpen = false; job?.cancel() }
    fun disabled() { cancel() }
}
