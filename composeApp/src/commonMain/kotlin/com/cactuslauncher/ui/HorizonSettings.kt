package com.cactuslauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.theme.C

@Composable
fun HorizonSettings(state: com.cactuslauncher.state.HorizonState, settings: LauncherSettings, profileName: String? = null) {
    var detailsOpen by remember { mutableStateOf(false) }
    val enabled = state.busy || !state.prepared || profileName != null
    val primary = !state.busy
    BoxWithConstraints(Modifier.fillMaxSize().background(C.window).verticalScroll(rememberScrollState())) {
        val pagePadding = if (maxWidth < 600.dp) 20.dp else 40.dp
        Column(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(pagePadding), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Horizon", 32, C.text1, FontWeight.SemiBold, lineHeight = 39)
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(C.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        T("Бета", 12, C.accent, FontWeight.Medium, lineHeight = 16)
                    }
                }
                T("Установка, запуск и настройки Horizon", 14, C.text2, FontWeight.Normal, lineHeight = 20)
            }
            Column(
                Modifier.fillMaxWidth().softShadow(RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)).background(C.surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(C.accentSoft), contentAlignment = Alignment.Center) {
                        T("H", 28, C.accent, FontWeight.SemiBold, lineHeight = 34)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T("Horizon", 20, C.text1, FontWeight.SemiBold, lineHeight = 24)
                        T(state.installation?.let { "Версия ${it.release} · Minecraft ${it.minecraft}" } ?: "Не установлен", 14, C.text2, FontWeight.Normal, lineHeight = 20)
                    }
                }
                T(when {
                    state.running -> "Игра запущена"
                    state.launching -> "Запускаем…"
                    state.busy -> state.progress?.message ?: "Подождите…"
                    state.error != null -> "Не удалось завершить операцию"
                    else -> state.status?.substringBefore(" Журнал:") ?: when {
                        state.prepared -> "Готов к игре офлайн"
                        state.installation != null -> "Нужно завершить установку"
                        else -> "Всё необходимое скачается автоматически"
                    }
                }, 15, C.text1, FontWeight.Normal, lineHeight = 22, modifier = Modifier.fillMaxWidth())
                if (state.busy && !state.launching) {
                    state.progress?.let {
                        val fraction = it.fraction.coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            T("${(fraction * 100).toInt()}%", 14, C.text2, FontWeight.Medium, lineHeight = 20)
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(8.dp)).background(C.fill)
                                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
                                if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(C.accentFill))
                            }
                        }
                    }
                }
                val name = state.runningProfile.ifBlank { profileName.orEmpty() }
                if (name.isNotBlank()) T("Профиль: $name", 14, C.text2, FontWeight.Normal, lineHeight = 20)
                else if (state.prepared && !state.busy) T("Выберите профиль в боковой панели", 14, C.text2, FontWeight.Normal, lineHeight = 20)
                HorizonAction(
                    text = when {
                        state.running -> "Остановить"
                        state.busy -> "Отменить"
                        state.prepared -> "Играть офлайн"
                        state.installation != null -> "Завершить установку"
                        else -> "Установить"
                    },
                    enabled = enabled,
                    primary = primary,
                ) {
                    when {
                        state.busy -> state.cancel()
                        state.prepared -> state.launch()
                        else -> state.installAutomatic()
                    }
                }
                state.error?.let { error ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.dangerSoft).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer { T(error, 14, C.dangerInk, FontWeight.Normal, lineHeight = 20, modifier = Modifier.fillMaxWidth()) }
                        T("Восстановление и свои файлы", 14, C.accent, FontWeight.Medium, lineHeight = 20,
                            modifier = Modifier.buttonClickNoRipple { detailsOpen = true })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("Автообновление", 15, C.text1, FontWeight.Normal, lineHeight = 20)
                    T("Проверять при старте Cactus", 13, C.text2, FontWeight.Normal, lineHeight = 18)
                }
                Switch(settings.horizonAutoUpdate, enabled = !state.busy) { settings.horizonAutoUpdate = it }
            }
            HorizonDisclosure("Подробнее", detailsOpen, { detailsOpen = !detailsOpen }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("Обновления и восстановление", 15, C.text1, FontWeight.Medium, lineHeight = 20)
                    if (state.latest.isNotBlank()) T("Доступная версия: ${state.latest}", 13, C.text2, FontWeight.Normal, lineHeight = 18)
                    HorizonAction("Проверить обновления", !state.busy, state::check)
                    if (state.installation != null) {
                        HorizonAction("Обновить", !state.busy, state::installAutomatic)
                        HorizonAction("Вернуть предыдущую версию", !state.busy, state::rollback)
                    }
                    state.status?.takeIf { it.contains(" Журнал:") || state.error != null }?.let {
                        SelectionContainer { T(it, 13, C.text2, FontWeight.Normal, lineHeight = 18, modifier = Modifier.fillMaxWidth()) }
                    }
                }
                HorizonManualSettings(state)
                T("Horizon — бета. Запуск офлайн, без Microsoft-входа. Требуется лицензия Minecraft Java Edition.", 13, C.text2, FontWeight.Normal, lineHeight = 18)
                T("Миры и настройки хранятся отдельно для каждого профиля. Остановка может потерять несохранённые изменения игры.", 13, C.text2, FontWeight.Normal, lineHeight = 18)
            }
        }
    }
}

@Composable
private fun HorizonManualSettings(state: com.cactuslauncher.state.HorizonState) {
    var open by remember { mutableStateOf(false) }
    HorizonDisclosure("Свои файлы", open, { open = !open }) {
        T("Необязательно. Пустые пути — автоматическая загрузка.", 13, C.text2, FontWeight.Normal, lineHeight = 18)
        T("Папка Minecraft", 14, C.text1, FontWeight.Medium, lineHeight = 20)
        SearchBox(state.source, { if (!state.busy) state.source = it }, "Автоматически", Modifier.fillMaxWidth(), fill = C.fill)
        HorizonAction("Выбрать папку", !state.busy, state::chooseSource)
        HorizonAction("Проверить файлы", !state.busy && state.installation != null && state.source.isNotBlank(), state::validate)
        T("Папка Java", 14, C.text1, FontWeight.Medium, lineHeight = 20)
        SearchBox(state.javaHome, { if (!state.busy) state.javaHome = it }, "Автоматически", Modifier.fillMaxWidth(), fill = C.fill)
        HorizonAction("Выбрать Java", !state.busy, state::chooseJava)
        state.installation?.let { T("Minecraft ${it.minecraft} · Java ${it.javaMajor}", 13, C.text2, FontWeight.Normal, lineHeight = 18) }
        HorizonAction(if (state.installation == null) "Установить с этими файлами" else "Подготовить с этими файлами", !state.busy) {
            if (state.installation == null) state.install() else state.prepare()
        }
    }
}

@Composable
private fun HorizonDisclosure(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface)
        .border(1.dp, C.border.copy(alpha = 0.4f), RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Развёрнуто" else "Свёрнуто" }
            .clickNoRipple(onToggle).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            T(title, 16, C.text1, FontWeight.Medium, lineHeight = 20, modifier = Modifier.weight(1f))
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(C.input), contentAlignment = Alignment.Center) {
                T(if (open) "−" else "+", 14, C.text2, FontWeight.Medium, lineHeight = 20)
            }
        }
        AnimatedVisibility(open,
            enter = fadeIn(tween(CactusMotion.normal, easing = CactusMotion.easeOut)) + expandVertically(tween(CactusMotion.normal, easing = CactusMotion.easeOut)),
            exit = fadeOut(tween(CactusMotion.quick, easing = CactusMotion.easeOut)) + shrinkVertically(tween(CactusMotion.normal, easing = CactusMotion.easeOut))) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun HorizonAction(text: String, enabled: Boolean, action: () -> Unit) = HorizonAction(text, enabled, false, action)

@Composable
private fun HorizonAction(text: String, enabled: Boolean, primary: Boolean, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) C.accentFill else C.input)
            .border(1.dp, if (primary && enabled) C.accentFill else C.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .semantics { role = Role.Button }
            .clickNoRipple(enabled = enabled, onClick = action)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, 14, if (!enabled) C.text3 else if (primary) C.onAccent else C.text1, FontWeight.Medium, lineHeight = 20)
    }
}
