package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.CrashDiagnosis
import com.cactuslauncher.ui.LauncherSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Build log viewer: follows the log file incrementally, crash diagnosis and the trial run without a suspect mod. */
class LogsState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val preferences: LauncherSettings,
    private val profiles: ProfilesState,
    private val messages: AppMessages,
) {
    var buildId by mutableStateOf<String?>(null)
    var lines by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var diagnosis by mutableStateOf<CrashDiagnosis?>(null)
    var diagnosisLoading by mutableStateOf(false)
    var recoveryBusy by mutableStateOf(false)
    var recoveryMessage by mutableStateOf<String?>(null)

    /** Diagnoses [id] once, then appends new log lines every 1.5 s, reading only the bytes added since the last poll. */
    suspend fun follow(id: String) {
        loading = true
        error = null
        diagnosisLoading = true
        diagnosis = null
        recoveryMessage = null
        try {
            diagnosis = game.diagnoseBuild(id)
            messages.backgroundRecovered("crash-diagnosis")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            diagnosis = null
            messages.backgroundFailure("crash-diagnosis", "Не удалось разобрать причину вылета.", e)
        }
        finally { diagnosisLoading = false }
        val buffer = ArrayDeque<String>()
        var offset = 0L
        var first = true
        try {
            while (currentCoroutineContext().isActive && buildId == id) {
                val chunk = game.buildLogChunk(id, offset)
                if (chunk.reset) buffer.clear()
                buffer.addAll(chunk.lines)
                while (buffer.size > MAX_LINES) buffer.removeFirst()
                if (first || chunk.reset || chunk.lines.isNotEmpty()) lines = buffer.toList()
                first = false
                offset = chunk.nextOffset
                loading = false
                delay(1500)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            error = e.message ?: "Не удалось прочитать файл логов."
            loading = false
        }
    }

    fun tryRecovery(buildId: String, modFile: String, running: Boolean) {
        if (recoveryBusy || running) return
        recoveryBusy = true
        recoveryMessage = null
        scope.launch {
            try {
                game.launchRecoveryBuild(
                    buildId, modFile, preferences.memoryGb, profiles.launchAuthServer,
                    preferences.automaticMemory, preferences.autoJavaOptimization,
                )
                recoveryMessage = "Пробный запуск идёт без этого мода. Сборка и миры не менялись."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { recoveryMessage = e.message ?: "Пробный запуск не удался." }
            finally {
                profiles.syncExternalAccount()
                recoveryBusy = false
            }
        }
    }

    fun openFile(buildId: String) {
        scope.launch {
            try { game.openBuildLogs(buildId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось открыть файл логов." }
        }
    }

    private companion object {
        const val MAX_LINES = 2_000
    }
}
