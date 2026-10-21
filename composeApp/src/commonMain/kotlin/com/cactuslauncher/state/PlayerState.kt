package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.game.TrackCommand
import com.cactuslauncher.model.TrackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The "now playing" widget in the sidebar. */
class PlayerState(private val game: GameService, private val scope: CoroutineScope, private val enabled: () -> Boolean) {
    var expanded by mutableStateOf(false)
    var currentTrack by mutableStateOf<TrackSnapshot?>(null)
    var trackError by mutableStateOf<String?>(null)
    var commandBusy by mutableStateOf(false)

    suspend fun applyEnabled(value: Boolean) {
        if (!value) {
            currentTrack = null
            trackError = null
            expanded = false
        }
        game.setPlayerEnabled(value)
    }

    suspend fun poll() {
        // Let the first frame and profile loading complete before starting the helper.
        delay(600)
        while (currentCoroutineContext().isActive) {
            try {
                val track = game.currentTrack()
                if (enabled() && !commandBusy) {
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

    fun command(command: TrackCommand) {
        if (!enabled() || commandBusy) return
        commandBusy = true
        val previousTrack = currentTrack
        if (command == TrackCommand.PLAY_PAUSE) {
            currentTrack = currentTrack?.let { it.copy(isPlaying = !it.isPlaying) }
        }
        scope.launch {
            try {
                game.controlTrack(command)
                delay(150)
                val track = game.currentTrack()
                if (enabled()) currentTrack = track
                trackError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (enabled()) {
                    currentTrack = previousTrack
                    trackError = e.message ?: "Не удалось управлять текущим треком."
                }
            } finally {
                commandBusy = false
            }
        }
    }
}
