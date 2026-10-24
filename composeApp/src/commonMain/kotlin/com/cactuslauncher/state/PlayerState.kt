package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.game.TrackCommand
import com.cactuslauncher.model.TrackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The "now playing" widget in the sidebar. */
class PlayerState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val launcherMode: () -> Boolean,
) {
    var expanded by mutableStateOf(false)
    var currentTrack by mutableStateOf<TrackSnapshot?>(null)
    var trackError by mutableStateOf<String?>(null)
    var commandBusy by mutableStateOf(false)

    /**
     * The bar position is kept here rather than taken from each poll: [anchorPosition] seconds at [anchorMark],
     * advancing on its own while the track plays. Players report whole seconds, some only now and then
     * (Spotify over MPRIS always says 0, macOS AppleScript lookups are cached for 15 s), so a poll only moves
     * the bar when the reported position changed and disagrees with the local clock.
     */
    private var anchorPosition by mutableStateOf(0.0)
    private var anchorMark: TimeMark = TimeSource.Monotonic.markNow()
    private var lastPolled: TrackSnapshot? = null
    /** After a seek, polls keep the target until this passes, so a player that updates late cannot snap it back. */
    private var holdUntil: TimeMark? = null
    private var seekJob: Job? = null

    /** Current position in seconds, for the progress bar. */
    fun position(): Double {
        val track = currentTrack ?: return 0.0
        val elapsed = if (track.isPlaying) anchorMark.elapsedNow().inWholeMilliseconds / 1000.0 else 0.0
        val value = (anchorPosition + elapsed).coerceAtLeast(0.0)
        return if (track.durationSeconds > 0) value.coerceAtMost(track.durationSeconds.toDouble()) else value
    }

    fun seek(positionSeconds: Int) {
        val track = currentTrack ?: return
        if (!enabled() || !track.canSeek || track.durationSeconds <= 0) return
        val target = positionSeconds.coerceIn(0, track.durationSeconds)
        val before = position()
        setAnchor(target.toDouble())
        holdUntil = TimeSource.Monotonic.markNow() + SEEK_HOLD
        seekJob?.cancel()
        seekJob = scope.launch {
            try {
                game.seekTrack(target)
                holdUntil = TimeSource.Monotonic.markNow() + SEEK_HOLD
                trackError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (enabled()) {
                    holdUntil = null
                    lastPolled = null
                    setAnchor(before)
                    trackError = e.message ?: "Не удалось перемотать трек."
                }
            }
        }
    }

    private fun setAnchor(seconds: Double) {
        anchorPosition = seconds
        anchorMark = TimeSource.Monotonic.markNow()
    }

    /** Shows [track], first fixing the position at the moment play/pause flips so the bar neither jumps nor drifts. */
    private fun show(track: TrackSnapshot?) {
        val shown = currentTrack
        if (shown != null && track != null && shown.isPlaying != track.isPlaying) setAnchor(position())
        currentTrack = track
    }

    /** Takes a fresh snapshot from the player. */
    private fun accept(track: TrackSnapshot?) {
        val previous = lastPolled
        lastPolled = track
        val shown = currentTrack
        if (track == null || shown == null || !sameTrack(shown, track)) {
            holdUntil = null
            show(track)
            setAnchor((track?.positionSeconds ?: 0).toDouble())
            return
        }
        show(track)
        if (holdUntil?.hasPassedNow() == false) return
        holdUntil = null
        if (previous != null && sameTrack(previous, track) && previous.positionSeconds == track.positionSeconds) return
        val reported = track.positionSeconds.toDouble()
        // Whole seconds: a playing track is somewhere in [reported, reported + 1).
        if (track.isPlaying && position() in (reported - 0.25)..(reported + 1.25)) return
        setAnchor(reported)
    }

    private fun sameTrack(a: TrackSnapshot, b: TrackSnapshot) =
        a.title == b.title && a.artist == b.artist && a.durationSeconds == b.durationSeconds

    suspend fun applyEnabled(value: Boolean) {
        if (!value) {
            accept(null)
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
                val track = readTrack()
                if (enabled() && !commandBusy) {
                    accept(track)
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
        if (launcherMode() && command == TrackCommand.PLAY_PAUSE && currentTrack?.canPause != true) return
        if (!launcherMode() && currentTrack?.supported == false) return
        commandBusy = true
        val previousTrack = currentTrack
        if (command == TrackCommand.PLAY_PAUSE) {
            show(currentTrack?.let { it.copy(isPlaying = !it.isPlaying) })
        }
        scope.launch {
            try {
                if (launcherMode() && command == TrackCommand.PLAY_PAUSE) {
                    game.setLauncherTrackPaused(previousTrack?.isPlaying == true)
                }
                else if (launcherMode()) game.skipLauncherTrack(command == TrackCommand.NEXT)
                else game.controlTrack(command)
                delay(150)
                val track = readTrack()
                if (enabled()) accept(track)
                trackError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (enabled()) {
                    show(previousTrack)
                    trackError = e.message ?: "Не удалось управлять текущим треком."
                }
            } finally {
                commandBusy = false
            }
        }
    }

    private suspend fun readTrack() = if (launcherMode()) game.launcherTrack() else game.currentTrack()

    private companion object {
        val SEEK_HOLD = 2_500.milliseconds
    }
}
