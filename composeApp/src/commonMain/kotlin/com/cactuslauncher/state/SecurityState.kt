package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.ConflictingSoftware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Startup check for security software that conflicts with the launcher; removal is always user-consented. */
class SecurityState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val messages: AppMessages,
) {
    var threat by mutableStateOf<ConflictingSoftware?>(null)
    var visible by mutableStateOf(false)
    var busy by mutableStateOf(false)
    private var checked = false

    suspend fun check() {
        if (checked) return
        checked = true
        try {
            val found = game.detectConflictingSoftware()
            threat = found
            if (found != null) visible = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            game.reportError("conflicting-software", e)
        }
    }

    fun dismiss() {
        visible = false
    }

    fun uninstall() {
        if (busy) return
        val found = threat ?: return
        if (!found.uninstallable) {
            messages.notice = "Автоматическое удаление недоступно. Удалите ${found.name} через «Программы и компоненты»."
            return
        }
        busy = true
        scope.launch {
            try {
                val started = game.uninstallConflictingSoftware()
                messages.notice = if (started) {
                    "Запущен деинсталлятор ${found.name}. Подтвердите удаление в его окне."
                } else {
                    "Не удалось запустить деинсталлятор ${found.name}. Удалите программу вручную."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.failure("uninstall-conflicting-software", "Не удалось удалить ${found.name}.", e)
            } finally {
                busy = false
                visible = false
                threat = null
            }
        }
    }
}