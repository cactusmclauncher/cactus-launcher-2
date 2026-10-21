package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.Screenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Остальное" → Скриншоты: the selected build's screenshots, refreshed while the screen is open, and the viewer. */
class ScreenshotsState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val builds: BuildsState,
    private val messages: AppMessages,
) {
    var selectedBuildId by mutableStateOf<String?>(null)
    var shots by mutableStateOf<List<Screenshot>>(emptyList())
    var shotsBuildId by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** File name of the screenshot open in the viewer. */
    var viewing by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    val selectedShots: List<Screenshot> get() = if (shotsBuildId == selectedBuildId) shots else emptyList()
    val viewingIndex: Int get() = selectedShots.indexOfFirst { it.fileName == viewing }
    val viewingShot: Screenshot? get() = selectedShots.getOrNull(viewingIndex)

    fun ensureSelection() {
        if (builds.builds.none { it.id == selectedBuildId }) selectedBuildId = builds.builds.firstOrNull()?.id
    }

    /** Lists the folder now and then every few seconds while the caller (the open screen) is active. */
    suspend fun watch() {
        val id = selectedBuildId
        if (id == null) { shots = emptyList(); shotsBuildId = null; return }
        if (shotsBuildId != id) { shots = emptyList(); shotsBuildId = null; viewing = null; loading = true }
        error = null
        while (true) {
            try {
                val next = game.screenshots(id)
                if (shotsBuildId != id || next != shots) {
                    shots = next
                    shotsBuildId = id
                    if (viewing != null && next.none { it.fileName == viewing }) viewing = null
                }
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось прочитать папку скриншотов." }
            finally { loading = false }
            delay(REFRESH_MILLIS)
        }
    }

    fun open(shot: Screenshot) { viewing = shot.fileName }
    fun close() { viewing = null }

    /** Moves the viewer by [step] (−1 previous, +1 next), wrapping at the ends. */
    fun move(step: Int) {
        val list = selectedShots
        val index = viewingIndex
        if (list.isEmpty() || index < 0) return
        viewing = list[(index + step).mod(list.size)].fileName
    }

    fun openInSystem(shot: Screenshot) = action("Не удалось открыть скриншот.") { game.openScreenshot(it, shot.fileName) }
    fun reveal(shot: Screenshot) = action("Не удалось показать скриншот в папке.") { game.revealScreenshot(it, shot.fileName) }
    fun copy(shot: Screenshot) = action("Не удалось скопировать скриншот.") {
        game.copyScreenshot(it, shot.fileName)
        messages.notice = "Скриншот скопирован."
    }
    fun openFolder() = action("Не удалось открыть папку скриншотов.") { game.openScreenshotsFolder(it) }

    fun delete(shot: Screenshot) {
        val list = selectedShots
        val index = list.indexOfFirst { it.fileName == shot.fileName }
        action("Не удалось удалить скриншот.") { id ->
            val trashed = game.deleteScreenshot(id, shot.fileName)
            val remaining = list.filter { it.fileName != shot.fileName }
            if (shotsBuildId == id) shots = remaining
            // Stay in the viewer on the neighbour, or close it when nothing is left.
            if (viewing == shot.fileName) viewing = remaining.getOrNull(index.coerceAtMost(remaining.size - 1))?.fileName
            messages.notice = if (trashed) "Скриншот перемещён в корзину." else "Скриншот удалён."
        }
    }

    private fun action(failure: String, block: suspend (String) -> Unit) {
        val id = selectedBuildId ?: return
        if (busy) return
        scope.launch {
            busy = true
            try { block(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.failure("screenshots", failure, e) }
            finally { busy = false }
        }
    }

    private companion object { const val REFRESH_MILLIS = 3000L }
}
