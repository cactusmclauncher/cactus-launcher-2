package com.cactuslauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow
import com.cactuslauncher.ui.BannerArtwork
import com.cactuslauncher.ui.SearchBox
import androidx.compose.runtime.*

@Composable
fun NewsScreen(onOpenArticle: () -> Unit, onOpenBeta: () -> Unit = onOpenArticle) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Новости", 32, C.text1, FontWeight.Bold, lineHeight = 39)
            SearchBox(query, { query = it }, "Поиск новостей", Modifier.width(280.dp), elevated = true, fill = C.surface)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(C.banner)
                .clickNoRipple(onOpenArticle),
            contentAlignment = Alignment.BottomStart,
        ) {
            BannerArtwork(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.1f)))))
            Column(
                Modifier.width(544.dp).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("Обновление 1.21.11", 13, C.bannerEyebrow, lineHeight = 16)
                T("Mounts of Mayhem", 40, Color.White, FontWeight.Bold, lineHeight = 48)
                T(
                    "Копья, наутилусы и зомби-всадники.",
                    15,
                    C.bannerText,
                    lineHeight = 18,
                )
            }
        }

        if ("Бета версия Windows Linux macOS сборки моды плеер обновления".contains(query, true)) {
            NewsCard("БЕТА-ТЕСТ", "Cactus Launcher 2.0", "Лаунчер для Windows, Linux и macOS: сборки, моды, профили и настройка Java.", C.blue, Modifier.fillMaxWidth(), onOpenBeta)
        }
    }
}

@Composable
private fun NewsCard(
    tag: String,
    title: String,
    desc: String,
    thumb: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .clickNoRipple(onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(thumb),
        )
        T(tag, 12, C.accent, FontWeight.Medium, lineHeight = 15)
        T(title, 17, C.text1, lineHeight = 21)
        T(desc, 13, C.text2, lineHeight = 16)
    }
}

@Composable
fun NewsReader(onBack: () -> Unit, beta: Boolean = false) {
    Column(Modifier.fillMaxSize().background(C.readerBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(C.readerBar)
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickNoRipple(onBack),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("←", 16, C.readerGreen, FontWeight.SemiBold, lineHeight = 19)
                T("Все новости", 15, C.readerGreen, FontWeight.Medium, lineHeight = 18)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.width(720.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(C.readerGreenTag)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                    T(if (beta) "БЕТА-ТЕСТ" else "ОБНОВЛЕНИЕ 1.21.11", 12, C.readerGreenTagText, FontWeight.SemiBold, lineHeight = 15)
                    }
                    T(if (beta) "Cactus Launcher 2.0" else "Mounts of Mayhem", 40, C.readerInk, FontWeight.Bold, lineHeight = 48)
                    T(
                        if (beta) "Бета для Windows, Linux и macOS. Пробуйте и пишите, что сломалось, пока не вышел релиз." else "Копья, наутилусы и зомби-всадники: коротко о том, что появилось в игре.",
                        19,
                        C.readerLede,
                        lineHeight = 23,
                    )
                    T(if (beta) "Cactus Launcher, 2 мин чтения" else "По материалам minecraft.net, 2 мин чтения", 13, C.readerMuted, lineHeight = 16)
                }
                if (beta) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        T("Что уже можно проверить", 26, C.readerInk, FontWeight.Bold, lineHeight = 31)
                        T("Сборки Vanilla, Fabric и Forge, установка Minecraft, поиск модов на Modrinth, офлайн-профили и логи запуска. Настройки и сборки сохраняются между запусками.", 17, C.readerBody, lineHeight = 24)
                        T("Плеер показывает текущий трек и управляет воспроизведением. В Linux для этого нужен playerctl и плеер с поддержкой MPRIS.", 17, C.readerBody, lineHeight = 24)
                        T("Обновления лаунчер берёт из GitHub Releases. Скачанный установщик открывается из настроек.", 17, C.readerBody, lineHeight = 24)
                        T("Что ещё в разработке", 26, C.readerInk, FontWeight.Bold, lineHeight = 31)
                        T("Вход через Microsoft, NeoForge, управление мирами и микшер для Windows.", 17, C.readerBody, lineHeight = 24)
                        T("Нашли проблему?", 26, C.readerInk, FontWeight.Bold, lineHeight = 31)
                        T("Напишите, что вы делали, что ожидали и что получилось, и укажите систему. Если дело в плеере, добавьте, где играла музыка. Скриншот или запись экрана очень помогут.", 17, C.readerBody, lineHeight = 24)
                    }
                } else {
                BannerArtwork(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(24.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    T("Что нового", 26, C.readerInk, FontWeight.Bold, lineHeight = 31)
                    T(
                        "Главная новинка: копьё. Его урон зависит от точности и скорости, так что сильнее всего оно бьёт с разгона верхом.",
                        17,
                        C.readerBody,
                        lineHeight = 21,
                    )
                    T(
                        "Наутилусов можно приручить и плавать на них под водой. Ещё появились зомби-наутилусы, зомби-лошади со всадниками и незеритовая конская броня, а большинство ездовых мобов теперь умеет плавать.",
                        17,
                        C.readerBody,
                        lineHeight = 21,
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(C.readerCard)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        T("СОВЕТ", 12, C.readerGreen, FontWeight.SemiBold, lineHeight = 15)
                        T(
                            "Перед обновлением сделайте копию мира, чтобы было куда откатиться.",
                            15,
                            C.readerInk,
                            lineHeight = 18,
                        )
                    }
                    T(
                        "Полный список изменений есть на minecraft.net. Создать сборку с новой версией можно в разделе «Сборки».",
                        17,
                        C.readerBody,
                        lineHeight = 21,
                    )
                }
                }
            }
        }
    }
}
