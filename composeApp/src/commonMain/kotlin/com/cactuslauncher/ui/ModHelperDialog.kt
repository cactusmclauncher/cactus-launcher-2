package com.cactuslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.ModHelperIssue
import com.cactuslauncher.theme.C

@Composable
fun ModHelperDialog(issue: ModHelperIssue, onDismiss: () -> Unit, onOpenBuilds: () -> Unit) {
    Modal(onDismiss = onDismiss, height = 440.dp) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Mod Helper", 24, C.text1, FontWeight.Bold, lineHeight = 30)
                T("Закрыть", 13, C.text2, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onDismiss))
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                T("Нашлась несовместимость", 17, C.text1, FontWeight.SemiBold, lineHeight = 22)
                val detail = if (issue.conflict) {
                    "Установленный мод несовместим с ${issue.projectTitle}."
                } else if (issue.dependencyId != null) {
                    "Для обязательной зависимости ${issue.dependencyId} не нашлась версия под эту сборку."
                } else {
                    "${issue.projectTitle} не подходит к версии Minecraft или загрузчику этой сборки."
                }
                T(detail, 14, C.text2, lineHeight = 20)
                T(issue.message, 12, C.text2, lineHeight = 16, maxLines = 2)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.fill).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                T("${issue.buildName}, Minecraft ${issue.minecraftVersion}", 14, C.text1, FontWeight.Medium, lineHeight = 18)
                T("Загрузчик: ${issue.loader}", 13, C.text2, lineHeight = 17)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Что проверить", 14, C.text1, FontWeight.SemiBold, lineHeight = 18)
                T("Выберите сборку с подходящей версией Minecraft.", 13, C.text2, lineHeight = 18)
                T(if (issue.conflict) "Отключите один из конфликтующих модов или выберите совместимые версии." else "Убедитесь, что зависимость выпущена для ${issue.loader}.", 13, C.text2, lineHeight = 18)
                T("Моды не добавлены частично. После выбора другой сборки повторите установку.", 13, C.text2, lineHeight = 18)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(C.fill).clickNoRipple(onDismiss).padding(horizontal = 18.dp, vertical = 11.dp),
                ) { T("Остаться", 14, C.text1, FontWeight.Medium, lineHeight = 18) }
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(C.accentFill).clickNoRipple(onOpenBuilds).padding(horizontal = 18.dp, vertical = 11.dp),
                ) { T("К сборкам", 14, C.onAccent, FontWeight.Medium, lineHeight = 18) }
            }
        }
    }
}
