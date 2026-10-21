package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.ProfileSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Offline profiles and the third-party (authlib-injector) account. */
class ProfilesState(private val game: GameService, private val scope: CoroutineScope, private val messages: AppMessages) {
    var profiles by mutableStateOf<ProfileSnapshot?>(null)
    var open by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var externalAccountName by mutableStateOf(game.externalAccountName())
    /** Server address the user signed in to; sent with every launch while [externalAccountName] is set. */
    var externalApiRoot by mutableStateOf("")

    /** The auth server for launches, or null for the offline profile. */
    val launchAuthServer: String? get() = externalApiRoot.takeIf { externalAccountName != null && it.isNotBlank() }

    fun operation(operation: suspend () -> ProfileSnapshot) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { profiles = operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Не удалось сохранить профили." }
            finally { busy = false }
        }
    }

    fun chooseAvatar(id: String) = avatarOperation("Не удалось установить картинку профиля.") { game.chooseProfileAvatar(id) }

    fun removeAvatar(id: String) = avatarOperation("Не удалось убрать картинку профиля.") { game.removeProfileAvatar(id) }

    /** Like [operation], but failures go to the window notice. */
    private fun avatarOperation(userMessage: String, operation: suspend () -> ProfileSnapshot) {
        if (busy) return
        busy = true
        scope.launch {
            try { profiles = operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.failure("profile-avatar", userMessage, e) }
            finally { busy = false }
        }
    }

    fun signIn(apiRoot: String, username: String, password: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                externalAccountName = game.signInExternal(apiRoot, username, password)
                externalApiRoot = apiRoot.trim().trimEnd('/')
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось войти на сервер авторизации."
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        game.signOutExternal()
        externalAccountName = null
        externalApiRoot = ""
    }

    /** Picks up a sign-out or a refreshed name after the core validated the session at launch. */
    fun syncExternalAccount() {
        externalAccountName = game.externalAccountName()
        if (externalAccountName == null) externalApiRoot = ""
    }

    /** First-run setup: create the profile or rename the active one to [nickname]. */
    suspend fun applySetupNickname(nickname: String) {
        val snapshot = game.profiles()
        val active = snapshot.active
        profiles = when {
            active == null -> game.addProfile(nickname)
            active.name != nickname -> game.renameProfile(active.id, nickname)
            else -> snapshot
        }
    }
}
