package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.JavaOptimizationInfo
import com.cactuslauncher.ui.LauncherSettings
import kotlinx.coroutines.CancellationException

/** Loading and saving launcher settings, first-run setup and machine memory limits. */
class PreferencesState(private val game: GameService, private val messages: AppMessages) {
    val preferences = LauncherSettings(game.gameDir())
    var settingsReady by mutableStateOf(false)
    var firstSetupOpen by mutableStateOf(false)
    var settingsLoadFailed by mutableStateOf(false)
    var preferencesError by mutableStateOf<String?>(null)
    var javaOptimizationInfo by mutableStateOf<JavaOptimizationInfo?>(null)

    suspend fun load() {
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

    suspend fun save() {
        if (!settingsReady || settingsLoadFailed) return
        try {
            game.savePreferences(preferences.snapshot(!firstSetupOpen))
            preferencesError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preferencesError = e.message ?: "Не удалось сохранить настройки."
        }
    }

    fun reset() {
        preferences.reset(game.gameDir())
        settingsLoadFailed = false
        preferencesError = null
    }

    suspend fun applyRecommendedMemory() {
        if (!settingsReady || !preferences.automaticMemory) return
        try {
            val recommended = game.recommendedMemoryGb()
            if (preferences.automaticMemory) preferences.memoryGb = recommended
            messages.backgroundRecovered("recommended-memory")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the safe default if the platform cannot report physical memory.
            messages.backgroundFailure("recommended-memory", "Не удалось определить объём памяти, оставлено значение по умолчанию.", e)
        }
    }

    suspend fun loadJavaOptimizationInfo() {
        try {
            val info = game.javaOptimizationInfo()
            javaOptimizationInfo = info
            if (preferences.memoryGb > info.maxMemoryGb) preferences.memoryGb = info.maxMemoryGb
            messages.backgroundRecovered("java-optimization-info")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            javaOptimizationInfo = null
            messages.backgroundFailure("java-optimization-info", "Не удалось получить сведения о Java и памяти.", e)
        }
    }
}
