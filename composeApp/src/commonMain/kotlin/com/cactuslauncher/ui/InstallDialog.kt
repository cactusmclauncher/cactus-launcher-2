package com.cactuslauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.InstallProgress
import com.cactuslauncher.theme.C

@Composable
fun InstallDialog(name: String, progress: InstallProgress, busy: Boolean, paused: Boolean, error: String?, onPause: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit, build: com.cactuslauncher.model.LauncherBuild? = null) {
    val fraction by animateFloatAsState(progress.fraction.coerceIn(0f, 1f), tween(200, easing = CactusMotion.easeOut), label = "installation-progress")
    val close = if (busy) onCancel else onClose
    val finished = !busy && error == null
    Modal(close, height = 520.dp) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (build != null) InstanceArtwork(build, Modifier.size(64.dp)) else BuildArtwork(name, C.green, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(name, 22, C.text1, FontWeight.Bold, lineHeight = 27, maxLines = 1)
                    T(when {
                        paused -> "Загрузка приостановлена"
                        !busy -> if (error == null) "Сборка скачана" else "Загрузка остановлена"
                        progress.total > 0 -> "Загрузка файлов, ${progress.completed} из ${progress.total}"
                        else -> progress.stage
                    }, 14, C.text2, lineHeight = 17, maxLines = 2)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.fill).clickNoRipple(close), contentAlignment = Alignment.Center) {
                    T("✕", 14, C.text2, FontWeight.Medium, lineHeight = 17)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T("${(progress.fraction * 100).toInt()}%", 32, C.text1, FontWeight.Bold, lineHeight = 39)
                    val speed = (progress.bytesPerSecond / 104857.6).toInt() / 10.0
                    val remaining = progress.remainingSeconds
                    T(when {
                        paused -> "На паузе"
                        finished -> "Готово"
                        speed <= 0 -> "Подготовка…"
                        remaining < 0 -> "$speed МБ/с"
                        remaining < 60 -> "$speed МБ/с, осталось ${remaining} с"
                        else -> "$speed МБ/с, осталось ${(remaining + 59) / 60} мин"
                    }, 14, C.text2, lineHeight = 17)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp)).background(C.fill).semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.fraction.coerceIn(0f, 1f), 0f..1f)
                }) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.accent))
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.window).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Клиент Minecraft", "Библиотеки", "Ресурсы и звуки").forEachIndexed { index, label ->
                    val done = finished || progress.phase > index
                    val active = busy && progress.total > 0 && progress.phase == index
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (done) C.toggleOn else if (active) C.accent else C.track), contentAlignment = Alignment.Center) {
                            if (done) T("✓", 12, Color.White, FontWeight.Bold, lineHeight = 15)
                        }
                        T(label, 14, if (done || active) C.text1 else C.text2, lineHeight = 17)
                    }
                }
            }
            if (error != null) T(error, 13, C.danger, lineHeight = 18, maxLines = 2)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).background(C.fill).clickNoRipple(close).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    T(if (busy) "Отмена" else "Закрыть", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                }
                if (busy || error != null) Box(Modifier.clip(CircleShape).background(C.accentFill).clickNoRipple(if (busy) onPause else onRetry).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    T(if (!busy) "Повторить" else if (paused) "Продолжить" else "Пауза", 15, C.onAccent, FontWeight.Medium, lineHeight = 18)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.window).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Файл:", 12, C.text2, FontWeight.Medium, lineHeight = 15)
                T(progress.lastFile.ifBlank { "Ожидание загрузки" }, 12, C.text1, lineHeight = 15, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}
