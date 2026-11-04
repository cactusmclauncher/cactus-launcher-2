package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.ConflictingSoftware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Startup check for software that conflicts with the launcher; force-removal is always user-consented. */
class SecurityState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val messages: AppMessages,
) {
    var threats by mutableStateOf<List<ConflictingSoftware>>(emptyList())
    var visible by mutableStateOf(false)
    var busyIds by mutableStateOf<Set<String>>(emptySet())
    var removedIds by mutableStateOf<Set<String>>(emptySet())
    private var checked = false
    private var pending = 0

    suspend fun check() {
        if (checked) return
        checked = true
        try {
            val found = game.detectConflictingSoftware()
            threats = found
            if (found.isNotEmpty()) visible = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            game.reportError("conflicting-software", e)
        }
    }

    fun dismiss() {
        visible = false
    }

    fun removeAll() {
        threats.map { it.id }.filter { it !in removedIds && it !in busyIds }.forEach(::remove)
    }

    fun remove(id: String) {
        if (id in busyIds || id in removedIds) return
        val found = threats.firstOrNull { it.id == id } ?: return
        busyIds = busyIds + id
        pending++
        scope.launch {
            try {
                val removed = game.forceRemoveConflictingSoftware(id)
                if (removed) removedIds = removedIds + id
                messages.notice = if (removed) {
                    "${found.name} удалён."
                } else {
                    "Не удалось удалить ${found.name}. Возможно, нужны права администратора."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.failure("force-remove-conflicting-software", "Не удалось удалить ${found.name}.", e)
            } finally {
                busyIds = busyIds - id
                pending--
                if (pending <= 0) {
                    pending = 0
                    visible = false
                }
            }
        }
    }
}
