package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.MinecraftServer
import com.cactuslauncher.model.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** Result of pinging one address: still waiting, the server's status, or no answer. */
sealed interface ServerPingState {
    data object Pinging : ServerPingState
    data class Online(val status: ServerStatus) : ServerPingState
    data object Offline : ServerPingState
}

/** The server form: [index] null adds a new server, otherwise edits that entry of servers.dat. */
data class ServerForm(val index: Int?, val expectedIp: String, val name: String, val address: String, val error: String? = null)

/** "Остальное" → Серверы: the selected build's servers.dat, pings, the add/edit form and joining a server. */
class ServersState(
    private val game: GameService,
    private val scope: CoroutineScope,
    private val builds: BuildsState,
    private val messages: AppMessages,
) {
    var selectedBuildId by mutableStateOf<String?>(null)
    var servers by mutableStateOf<List<MinecraftServer>>(emptyList())
    var serversBuildId by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** By address as written in servers.dat. */
    var pings by mutableStateOf<Map<String, ServerPingState>>(emptyMap())
    var refreshing by mutableStateOf(false)
    var form by mutableStateOf<ServerForm?>(null)
    var busy by mutableStateOf(false)
    private var refreshJob: Job? = null

    /** Hidden entries are kept in the file but not shown, as in the game. */
    val visibleServers: List<MinecraftServer> get() = if (serversBuildId == selectedBuildId) servers.filter { !it.hidden } else emptyList()

    fun ensureSelection() {
        if (builds.builds.none { it.id == selectedBuildId }) selectedBuildId = builds.builds.firstOrNull()?.id
    }

    fun select(id: String) {
        if (id == selectedBuildId) return
        selectedBuildId = id
        form = null
    }

    /** Reads the list of the selected build and pings every server; called on open and by «Обновить». */
    fun refresh() {
        val id = selectedBuildId
        refreshJob?.cancel()
        if (id == null) { servers = emptyList(); serversBuildId = null; return }
        if (serversBuildId != id) { servers = emptyList(); serversBuildId = null; pings = emptyMap(); form = null; loading = true }
        error = null
        refreshJob = scope.launch {
            refreshing = true
            try {
                val list = game.servers(id)
                if (selectedBuildId != id) return@launch
                servers = list
                serversBuildId = id
                loading = false
                pingAll(id, list.filter { !it.hidden })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (selectedBuildId == id) error = e.message ?: "Не удалось прочитать список серверов."
            } finally {
                // A newer refresh owns the flags once this one is cancelled.
                if (refreshJob === coroutineContext[Job]) { loading = false; refreshing = false }
            }
        }
    }

    private suspend fun pingAll(id: String, list: List<MinecraftServer>) = coroutineScope {
        val permits = Semaphore(6)
        pings = pings + list.associate { it.ip to ServerPingState.Pinging }
        list.distinctBy { it.ip }.forEach { server -> launch { permits.withPermit { ping(id, server) } } }
    }

    private suspend fun ping(id: String, server: MinecraftServer) {
        val status = try { withTimeoutOrNull(PING_TIMEOUT_MILLIS) { game.pingServer(server.ip) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (selectedBuildId != id) return
        pings = pings + (server.ip to (status?.let { ServerPingState.Online(it) } ?: ServerPingState.Offline))
        val favicon = status?.favicon ?: return
        // Like the game: keep the icon the server sends. Not while the build runs, the game owns the file then.
        if (favicon == server.iconData || id in builds.runningBuildIds) return
        try {
            game.setServerIcon(id, server.index, server.ip, favicon)
            if (serversBuildId == id) servers = servers.map {
                if (it.index == server.index && it.ip == server.ip) it.copy(iconData = favicon, icon = status.icon) else it
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { game.reportError("server-icon", e) }
    }

    fun startAdd() { form = ServerForm(null, "", "", "") }
    fun startEdit(server: MinecraftServer) { form = ServerForm(server.index, server.ip, server.name, server.ip) }
    fun cancelForm() { form = null }
    fun updateForm(name: String, address: String) { form = form?.copy(name = name, address = address, error = null) }

    fun saveForm() {
        val current = form ?: return
        val id = selectedBuildId ?: return
        game.validateServer(current.name, current.address)?.let { form = current.copy(error = it); return }
        val address = current.address.trim()
        change(id, "Не удалось сохранить сервер.", {
            if (current.index == null) game.addServer(id, current.name, address)
            else game.editServer(id, current.index, current.expectedIp, current.name, address)
        }) { list ->
            form = null
            val target = if (current.index == null) list.lastOrNull() else list.getOrNull(current.index)
            if (target != null && !target.hidden && (current.index == null || target.ip != current.expectedIp)) pingAll(id, listOf(target))
        }
    }

    fun remove(server: MinecraftServer) {
        val id = selectedBuildId ?: return
        change(id, "Не удалось удалить сервер.", { game.removeServer(id, server.index, server.ip) }) {
            if (form?.index != null) form = null
            messages.notice = "Сервер «${server.name}» удалён."
        }
    }

    fun play(server: MinecraftServer, onGhostMode: () -> Unit) {
        val build = builds.builds.find { it.id == selectedBuildId } ?: return
        builds.play(build, onGhostMode, server = server.ip)
    }

    /** Writes servers.dat; on failure re-reads the file, since the game may have changed it meanwhile. */
    private fun change(id: String, failure: String, write: suspend () -> List<MinecraftServer>, after: suspend (List<MinecraftServer>) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val list = write()
                if (selectedBuildId != id) return@launch
                servers = list
                serversBuildId = id
                busy = false
                after(list)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                messages.failure("servers", failure, e)
                if (selectedBuildId == id) try { servers = game.servers(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            } finally { busy = false }
        }
    }

    private companion object { const val PING_TIMEOUT_MILLIS = 8000L }
}
