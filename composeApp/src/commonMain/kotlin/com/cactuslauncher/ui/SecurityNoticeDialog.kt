package com.cactuslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.ConflictingSoftware
import com.cactuslauncher.theme.C

@Composable
fun SecurityNoticeDialog(
    threats: List<ConflictingSoftware>,
    busyIds: Set<String>,
    removedIds: Set<String>,
    onDismiss: () -> Unit,
    onRemove: (String) -> Unit,
    onRemoveAll: () -> Unit,
) {
    Modal(onDismiss = onDismiss, height = 440.dp, width = 520.dp, wrapHeight = true) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Проверка безопасности", 22, C.text1, FontWeight.SemiBold, lineHeight = 27)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Обнаружено несовместимое ПО", 16, C.text1, FontWeight.SemiBold, lineHeight = 20)
                T("Эти программы могут блокировать файлы лаунчера, моды и запуск игры.", 14, C.text2, lineHeight = 20)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                threats.forEach { threat ->
                    ThreatRow(
                        threat = threat,
                        busy = threat.id in busyIds,
                        removed = threat.id in removedIds,
                        onRemove = { onRemove(threat.id) },
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.warningSoft).padding(14.dp),
            ) {
                T(
                    "Удаление снесёт программу без официального деинсталлятора: процессы, папки, службы, ярлыки и записи реестра. Windows может запросить права администратора.",
                    13, C.warningInk, lineHeight = 18,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(C.fill)
                        .buttonClickNoRipple(onDismiss).padding(horizontal = 18.dp, vertical = 11.dp),
                ) { T("Оставить", 14, C.text1, FontWeight.Medium, lineHeight = 18) }
                if (threats.any { it.id !in removedIds }) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(C.fill)
                            .buttonClickNoRipple(enabled = busyIds.isEmpty(), onClick = onRemoveAll)
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                    ) { T("Удалить всё", 14, C.text1, FontWeight.Medium, lineHeight = 18) }
                }
            }
        }
    }
}

@Composable
private fun ThreatRow(
    threat: ConflictingSoftware,
    busy: Boolean,
    removed: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(C.fill)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(threat.name, 14, C.text1, FontWeight.SemiBold, lineHeight = 18)
            T(threat.detail, 12, C.text2, lineHeight = 16)
        }
        Box(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(if (busy || removed) C.fill else C.danger)
                .buttonClickNoRipple(enabled = !busy && !removed, onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            T(
                if (busy) "Удаляем..." else if (removed) "Удалено" else "Удалить",
                13, if (busy || removed) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 16,
            )
        }
    }
}
