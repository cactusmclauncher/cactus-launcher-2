package com.cactuslauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.CrashDiagnosis
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.AnimatedSegments
import com.cactuslauncher.ui.InstanceArtwork
import com.cactuslauncher.ui.SearchBox
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow

@Composable
fun BuildLogsScreen(
    build: LauncherBuild,
    lines: List<String>,
    loading: Boolean,
    error: String?,
    diagnosis: CrashDiagnosis?,
    diagnosisLoading: Boolean,
    recoveryBusy: Boolean,
    recoveryMessage: String?,
    onBack: () -> Unit,
    onTryRecovery: (String) -> Unit,
    onOpenFile: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val entries = remember(lines, C.dark) { lines.map(::logEntry) }
    val filtered = entries.filter { entry ->
        val levelMatches = when (filter) {
            1 -> entry.level == "WARN" || entry.level == "WARNING"
            2 -> entry.level == "ERROR" || entry.level == "FATAL"
            else -> true
        }
        levelMatches && (query.isBlank() || entry.message.contains(query.trim(), ignoreCase = true) || entry.level.contains(query.trim(), ignoreCase = true))
    }

    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InstanceArtwork(build, Modifier.size(56.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T("Логи", 28, C.text1, FontWeight.Bold, lineHeight = 34)
                    T("${build.name}, последний запуск ${build.lastPlayed}", 15, C.text2, lineHeight = 18)
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(C.fill)
                    .clickNoRipple(onBack).padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                T("Назад к сборке", 14, C.text1, FontWeight.Medium, lineHeight = 17)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AnimatedSegments(listOf("Все", "Предупреждения", "Ошибки"), filter, { filter = it }, Modifier.width(400.dp))
            SearchBox(query, { query = it; copied = false }, "Поиск по логам", Modifier.width(280.dp), elevated = true, fill = C.surface)
        }

        if (diagnosisLoading || diagnosis != null || recoveryMessage != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                T(if (diagnosisLoading) "Разбираем последний запуск…" else diagnosis?.title ?: "Диагностика недоступна", 16, C.text1, FontWeight.Medium, lineHeight = 20)
                diagnosis?.let { result ->
                    T(result.detail, 13, C.text2, lineHeight = 18)
                    T("${result.evidence}${if (result.confidence.isBlank()) "" else "  ${result.confidence}"}", 12, C.text3, lineHeight = 16, maxLines = 2)
                    result.suspectMod?.let { mod ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            T("Совпадение найдено в $mod", 13, C.text1, lineHeight = 18, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(if (result.recoverable && !recoveryBusy) C.accentFill else C.fill)
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                                    .then(if (result.recoverable && !recoveryBusy) Modifier.clickNoRipple { onTryRecovery(mod) } else Modifier),
                            ) {
                                T(if (recoveryBusy) "Запускаю…" else "Проверить без мода", 13, if (result.recoverable && !recoveryBusy) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 16)
                            }
                        }
                    }
                }
                recoveryMessage?.let { T(it, 13, if (it.startsWith("Пробный запуск идёт")) C.accent else C.danger, lineHeight = 18) }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth()
                .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
                .clip(RoundedCornerShape(20.dp)).background(C.surface)
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                loading -> T("Загружаем файл логов…", 14, C.text2, lineHeight = 20, modifier = Modifier.padding(16.dp))
                error != null -> T(error, 14, C.danger, lineHeight = 20, modifier = Modifier.padding(16.dp))
                entries.isEmpty() -> T("Логов пока нет. Они появятся после первого запуска этой сборки.", 14, C.text2, lineHeight = 20, modifier = Modifier.padding(16.dp))
                filtered.isEmpty() -> T("По этому фильтру ничего не найдено.", 14, C.text2, lineHeight = 20, modifier = Modifier.padding(16.dp))
                else -> filtered.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(entry.background)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        T(entry.time, 13, C.text3, lineHeight = 16)
                        T(entry.level, 12, entry.levelColor, FontWeight.Medium, lineHeight = 15)
                        T(entry.message, 14, C.text1, lineHeight = 17, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("Открыть файл логов", 15, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple(onOpenFile))
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(if (lines.isEmpty()) C.fill else C.accentFill)
                    .then(if (lines.isEmpty()) Modifier else Modifier.clickNoRipple {
                        clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        copied = true
                    })
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                T(if (copied) "Скопировано" else "Копировать", 15, if (lines.isEmpty()) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 18)
            }
        }
    }
}

private data class LogEntry(
    val time: String,
    val level: String,
    val message: String,
    val levelColor: Color,
    val background: Color,
)

private fun logEntry(line: String): LogEntry {
    val levelMatch = Regex("\\b(ERROR|FATAL|WARN(?:ING)?|INFO|DEBUG|TRACE)\\b", RegexOption.IGNORE_CASE).find(line)
    val rawLevel = levelMatch?.value?.uppercase() ?: "INFO"
    val level = if (rawLevel == "WARNING") "WARN" else rawLevel
    val time = Regex("(?<!\\d)\\d{2}:\\d{2}:\\d{2}(?!\\d)").find(line)?.value ?: "--:--:--"
    val message = levelMatch?.let { line.substring(it.range.last + 1).trimStart(']', ':', ' ') } ?: line
    val (levelColor, background) = when (level) {
        "ERROR", "FATAL" -> C.dangerInk to C.dangerSoft
        "WARN" -> C.warningInk to C.warningSoft
        "DEBUG", "TRACE" -> C.text2 to C.window
        else -> C.accent to C.window
    }
    return LogEntry(time, level, message.ifBlank { line }, levelColor, background)
}
