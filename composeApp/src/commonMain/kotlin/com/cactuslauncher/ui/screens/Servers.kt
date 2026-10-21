package com.cactuslauncher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.MinecraftServer
import com.cactuslauncher.state.ServerForm
import com.cactuslauncher.state.ServerPingState
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.BuildArtwork
import com.cactuslauncher.ui.SearchBox
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow

/** What the servers section shows and does; App wires it to ServersState. */
class ServersSectionState(
    val selectedBuildId: String?,
    /** Visible entries of servers.dat (hidden ones stay in the file). */
    val servers: List<MinecraftServer>,
    val pings: Map<String, ServerPingState>,
    val loading: Boolean,
    val refreshing: Boolean,
    val error: String?,
    val form: ServerForm?,
    val busy: Boolean,
    /** A launch is in progress or the selected build is already running. */
    val playBlocked: Boolean,
    val onSelectBuild: (LauncherBuild) -> Unit,
    val onRefresh: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (MinecraftServer) -> Unit,
    val onFormChange: (name: String, address: String) -> Unit,
    val onFormSave: () -> Unit,
    val onFormCancel: () -> Unit,
    val onRemove: (MinecraftServer) -> Unit,
    val onPlay: (MinecraftServer) -> Unit,
)

@Composable
internal fun ColumnScope.ServersSection(
    builds: List<LauncherBuild>,
    loadingBuilds: Boolean,
    state: ServersSectionState,
    onOpenBuilds: () -> Unit,
) {
    val selectedBuild = builds.firstOrNull { it.id == state.selectedBuildId }
    BuildPicker(builds = builds, selected = selectedBuild, loading = loadingBuilds, onSelect = state.onSelectBuild)
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedBuildId) { listState.scrollToItem(0) }
    LaunchedEffect(state.form?.index, state.form != null) { if (state.form?.index == null && state.form != null) listState.animateScrollToItem(0) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loadingBuilds -> EmptyState("Загружаем сборки…", "Серверы появятся после загрузки сборок.")
            builds.isEmpty() -> EmptyState("Сборок пока нет", "Создайте сборку, и её серверы появятся здесь.", "Перейти к сборкам", onOpenBuilds)
            selectedBuild == null -> EmptyState("Выберите сборку", "Список серверов хранится в папке каждой сборки.")
            state.error != null -> EmptyState(state.error, "Файл servers.dat не удалось прочитать. Игра создаст его заново при следующем запуске.", "Повторить", state.onRefresh)
            state.loading -> EmptyState("Читаем список серверов", "Открываем servers.dat сборки «${selectedBuild.name}»…")
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "header") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("${serversCount(state.servers.size)} в сборке ${selectedBuild.name}", 13, C.text2, lineHeight = 16, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        val canRefresh = !state.refreshing
                        T(if (state.refreshing) "Обновляем…" else "Обновить", 13, if (canRefresh) C.accent else C.text2, FontWeight.Medium, lineHeight = 16,
                            modifier = if (canRefresh) Modifier.clickNoRipple(state.onRefresh) else Modifier)
                    }
                }
                val form = state.form
                if (form != null && form.index == null) item(key = "new") { ServerFormCard(form, state) }
                if (state.servers.isEmpty() && form == null) item(key = "empty") {
                    EmptyState("Серверов пока нет", "Добавьте адрес сервера, и он появится в списке серверов этой сборки в игре.", "Добавить сервер", state.onAdd)
                }
                items(state.servers, key = { "${it.index}:${it.ip}" }) { server ->
                    if (form != null && form.index == server.index) ServerFormCard(form, state)
                    else ServerRow(server, state.pings[server.ip], state)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        T(selectedBuild?.let { "Файл: ${it.name}/servers.dat" } ?: "Сначала выберите сборку", 13, C.text2, lineHeight = 17)
        val canAdd = selectedBuild != null && state.form == null && !state.loading && state.error == null
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(if (canAdd) C.accentFill else C.fill)
                .then(if (canAdd) Modifier.clickNoRipple(state.onAdd) else Modifier)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            T("Добавить сервер", 15, if (canAdd) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 18)
        }
    }
}

private val cardShape = RoundedCornerShape(16.dp)
private fun Modifier.card() = fillMaxWidth().softShadow(cardShape, elevation = 4.dp, color = Color(0x10000000)).clip(cardShape).background(C.surface)

@Composable
private fun ServerRow(server: MinecraftServer, ping: ServerPingState?, state: ServersSectionState) {
    var confirmingRemove by remember(server.index, server.ip) { mutableStateOf(false) }
    val online = ping as? ServerPingState.Online
    Row(
        Modifier.card().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = server.icon ?: online?.status?.icon
        if (icon != null) Image(icon, "Иконка сервера ${server.name}", Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        else BuildArtwork("release", Modifier.size(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(server.name, 15, C.text1, FontWeight.Medium, lineHeight = 18, maxLines = 1)
            if (confirmingRemove) {
                T("Удалить «${server.name}» из списка серверов сборки?", 12, C.text1, lineHeight = 15, maxLines = 2)
            } else {
                online?.status?.motd?.takeIf { it.isNotBlank() }?.let { T(it, 12, C.text1, lineHeight = 15, maxLines = 2) }
                val details = when (ping) {
                    is ServerPingState.Online -> listOf(
                        server.ip,
                        "${ping.status.online} из ${ping.status.max}",
                        ping.status.version,
                        "${ping.status.pingMillis} мс",
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    ServerPingState.Offline -> "${server.ip} · Не отвечает"
                    else -> "${server.ip} · Проверяем…"
                }
                T(details, 12, C.text2, lineHeight = 15, maxLines = 1)
            }
        }
        val enabled = !state.busy && state.form == null
        if (confirmingRemove) {
            T("Отмена", 12, C.text2, lineHeight = 15, modifier = Modifier.clickNoRipple { confirmingRemove = false })
            T("Удалить сервер", 12, if (enabled) C.dangerInk else C.text2, FontWeight.Medium, lineHeight = 15,
                modifier = if (enabled) Modifier.clickNoRipple { confirmingRemove = false; state.onRemove(server) } else Modifier)
        } else {
            T("Изменить", 12, if (enabled) C.accent else C.text2, FontWeight.Medium, lineHeight = 15,
                modifier = if (enabled) Modifier.clickNoRipple { state.onEdit(server) } else Modifier)
            T("Удалить", 12, if (enabled) C.dangerInk else C.text2, FontWeight.Medium, lineHeight = 15,
                modifier = if (enabled) Modifier.clickNoRipple { confirmingRemove = true } else Modifier)
            PillButton("Играть", !state.playBlocked) { state.onPlay(server) }
        }
    }
}

/** Adding or editing in place: name and address fields, Enter saves, Esc cancels. */
@Composable
private fun ServerFormCard(form: ServerForm, state: ServersSectionState) {
    Column(
        Modifier.card().onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false
            else when (it.key) {
                Key.Enter, Key.NumPadEnter -> { if (!state.busy) state.onFormSave(); true }
                Key.Escape -> { state.onFormCancel(); true }
                else -> false
            }
        }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        T(if (form.index == null) "Новый сервер" else "Изменить сервер", 15, C.text1, FontWeight.Medium, lineHeight = 18)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Название", 12, C.text2, lineHeight = 15)
                SearchBox(form.name, { state.onFormChange(it, form.address) }, "Сервер Minecraft", Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Адрес", 12, C.text2, lineHeight = 15)
                SearchBox(form.address, { state.onFormChange(form.name, it) }, "play.example.com или 192.168.1.5:25565", Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            T(form.error ?: "Без порта используется 25565.", 12, if (form.error != null) C.dangerInk else C.text2, lineHeight = 15, maxLines = 2,
                modifier = Modifier.weight(1f))
            T("Отмена", 13, C.text2, lineHeight = 16, modifier = Modifier.clickNoRipple(state.onFormCancel))
            PillButton(if (state.busy) "Сохраняем…" else if (form.index == null) "Добавить" else "Сохранить", !state.busy, state.onFormSave)
        }
    }
}

@Composable
private fun PillButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (enabled) C.accentFill else C.fill)
            .then(if (enabled) Modifier.clickNoRipple(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        T(label, 13, if (enabled) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 16)
    }
}

/** "1 сервер", "3 сервера", "11 серверов". */
internal fun serversCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "серверов"
        count % 10 == 1 -> "сервер"
        count % 10 in 2..4 -> "сервера"
        else -> "серверов"
    }
    return "$count $word"
}
