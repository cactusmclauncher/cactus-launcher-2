package com.cactuslauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.*

@Composable
fun BuildListScreen(
    builds: List<LauncherBuild>,
    loading: Boolean = false,
    error: String? = null,
    runningBuildIds: Set<String> = emptySet(),
    onRetry: () -> Unit = {},
    onCreate: () -> Unit,
    onOpen: (LauncherBuild) -> Unit,
    onPlay: (LauncherBuild) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = builds.filter {
        "${it.name} ${it.version} ${it.loader}".contains(query.trim(), ignoreCase = true)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Сборки", 32, C.text1, FontWeight.Bold, lineHeight = 39)
                T("У каждой своя версия, моды и настройки", 14, C.text2, lineHeight = 20)
            }
            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(C.accentFill).clickNoRipple(onCreate).padding(horizontal = 24.dp, vertical = 12.dp)) {
                T("+ Создать сборку", 15, C.onAccent, FontWeight.Medium, lineHeight = 18)
            }
        }
        SearchBox(query, { query = it }, "Поиск по названию, версии или загрузчику", Modifier.fillMaxWidth(), elevated = true, fill = C.surface, height = 44.dp)
        T("Всего сборок: ${builds.size}, показано: ${visible.size}", 13, C.text2, lineHeight = 16)
        if (loading) {
            T("Загрузка сборок…", 15, C.text2, lineHeight = 20)
        } else if (error != null) {
            T(error, 14, C.danger, lineHeight = 20)
            T("Повторить", 15, C.accent, lineHeight = 18, modifier = Modifier.clickNoRipple(onRetry))
        } else if (visible.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T(if (builds.isEmpty()) "Пока нет сборок" else "Сборки не найдены", 20, C.text1, FontWeight.Medium, lineHeight = 24)
                T(if (builds.isEmpty()) "Создайте первую сборку и выберите версию Minecraft." else "Попробуйте другое название или очистите поиск.", 14, C.text2, lineHeight = 20)
                T(if (builds.isEmpty()) "Создать сборку" else "Очистить поиск", 15, C.accent, FontWeight.Medium, lineHeight = 18,
                    modifier = Modifier.clickNoRipple { if (builds.isEmpty()) onCreate() else query = "" })
            }
        }
        visible.forEach { build ->
            Row(
                Modifier.fillMaxWidth().softShadow(RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)).background(C.surface)
                    .clickNoRipple { onOpen(build) }.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InstanceArtwork(build, Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(build.name, 18, C.text1, FontWeight.Medium, lineHeight = 22)
                    T("Minecraft ${build.version}, ${build.loader}", 14, C.text2, lineHeight = 20)
                    T(build.playSummary, 12, C.text2, lineHeight = 15)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val running = build.id in runningBuildIds
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (build.installed || running) C.fill else C.accentFill)
                        .then(if (running) Modifier else Modifier.clickNoRipple { onPlay(build) })
                        .padding(horizontal = 24.dp, vertical = 10.dp)) {
                        T(if (running) "Игра запущена" else if (build.installed) "Играть" else "Скачать", 14, if (build.installed || running) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 18)
                    }
                    T("Настроить →", 13, C.accent, FontWeight.Medium, lineHeight = 16, modifier = Modifier.clickNoRipple { onOpen(build) })
                }
            }
        }
    }
}
