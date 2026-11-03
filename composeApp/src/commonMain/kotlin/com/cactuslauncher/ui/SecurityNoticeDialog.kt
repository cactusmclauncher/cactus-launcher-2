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
    threat: ConflictingSoftware,
    busy: Boolean,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
) {
    Modal(onDismiss = onDismiss, height = 440.dp, width = 520.dp, wrapHeight = true) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Проверка безопасности", 22, C.text1, FontWeight.SemiBold, lineHeight = 27)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Обнаружено несовместимое ПО", 16, C.text1, FontWeight.SemiBold, lineHeight = 20)
                T("${threat.name} может блокировать файлы лаунчера, моды и запуск игры. ${threat.detail}", 14, C.text2, lineHeight = 20)
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.warningSoft).padding(14.dp),
            ) {
                T(
                    if (threat.uninstallable)
                        "Удаление запустит официальный деинсталлятор. Windows может запросить права администратора."
                    else
                        "Автоматическое удаление недоступно. Удалите программу через «Программы и компоненты».",
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
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(if (busy || !threat.uninstallable) C.fill else C.danger)
                        .buttonClickNoRipple(enabled = !busy && threat.uninstallable, onClick = onUninstall)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    T(if (busy) "Запускаем..." else "Удалить", 14, if (busy || !threat.uninstallable) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 18)
                }
            }
        }
    }
}
