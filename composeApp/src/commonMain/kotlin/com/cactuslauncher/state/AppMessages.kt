package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService

/** [install]: a missing dependency available on Modrinth for the build, offered as "Установить". */
/** [server]: the launch was meant to join this address, so "Всё равно запустить" keeps it. */
data class LaunchWarning(val buildId: String, val message: String, val install: com.cactuslauncher.model.ModrinthProject? = null, val server: String? = null)

/** Toasts at the bottom of the window and reporting of background failures. */
class AppMessages(private val game: GameService) {
    var notice by mutableStateOf<String?>(null)
    /** Build id to message; shown with a link to the build's logs. */
    var launchFailure by mutableStateOf<Pair<String, String>?>(null)
    /** [launchFailure]'s build was updated by the launcher and may be rolled back. */
    var launchFailureRollback by mutableStateOf(false)
    /** Build id to message after a mod update; shown with "Отменить", which rolls the update back. */
    var undoModUpdate by mutableStateOf<Pair<String, String>?>(null)
    /** Mods of a build that will likely stop the game; the launch waits for "Всё равно запустить". */
    var launchWarning by mutableStateOf<LaunchWarning?>(null)
    private val failingTasks = mutableSetOf<String>()

    /**
     * Logs a failure of a background [task] and, if [visible], shows [userMessage] once.
     * Polling tasks call this on every failed attempt; only the first one after a success is logged and shown.
     */
    fun backgroundFailure(task: String, userMessage: String, error: Throwable, visible: Boolean = true) {
        if (!failingTasks.add(task)) return
        game.reportError(task, error)
        if (visible) notice = listOfNotNull(userMessage, error.message?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /** Logs a failed user action and always shows [userMessage] with the error's own text. */
    fun failure(task: String, userMessage: String, error: Throwable) {
        game.reportError(task, error)
        notice = listOfNotNull(userMessage, error.message?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /** Marks [task] healthy again, so its next failure is reported. */
    fun backgroundRecovered(task: String) {
        failingTasks.remove(task)
    }
}
