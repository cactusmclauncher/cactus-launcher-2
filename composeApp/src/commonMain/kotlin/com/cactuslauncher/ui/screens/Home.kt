package com.cactuslauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.cactuslauncher.ui.buttonClickNoRipple
import com.cactuslauncher.ui.softShadow
import com.cactuslauncher.ui.BannerArtwork
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.ui.InstanceArtwork
import com.cactuslauncher.ui.SearchBox
import androidx.compose.runtime.*

@Composable
fun HomeScreen(
    builds: List<LauncherBuild> = emptyList(),
    onOpenBuilds: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenBuild: (LauncherBuild) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = builds.filter { it.name.contains(query.trim(), true) }
    // The stored list has no recency ordering. Do not pretend its first item is the last played.
    val featured = builds.firstOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Главная", 32, C.text1, FontWeight.SemiBold, lineHeight = 39)
            SearchBox(query, { query = it }, "Поиск сборок", Modifier.width(240.dp), fill = C.fill, height = 36.dp, radius = 10.dp)
        }

        if (query.isBlank() || builds.isEmpty()) {
            FeaturedBuild(featured, onOpenBuilds, onOpenBuild)

            Box(
                Modifier.fillMaxWidth().height(136.dp)
                    .clip(RoundedCornerShape(28.dp)).background(C.banner)
                    .clickNoRipple(onOpenNews),
            ) {
                BannerArtwork(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.12f)))))
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T("Обновление 1.21.11", 13, C.bannerEyebrow, lineHeight = 16)
                        T("Mounts of Mayhem", 24, Color.White, FontWeight.SemiBold, lineHeight = 29)
                        T("Копья, наутилусы и зомби-всадники.", 14, C.bannerText, lineHeight = 18, maxLines = 1)
                    }
                    T("Читать →", 15, Color.White, FontWeight.Medium, lineHeight = 18)
                }
            }
        }

        if (builds.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T("Ваши сборки", 20, C.text1, lineHeight = 24)
                    T("Все сборки →", 14, C.accent, lineHeight = 18, modifier = Modifier.buttonClickNoRipple(onOpenBuilds))
                }
                visible.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { build -> RecentCard(build, Modifier.weight(1f)) { onOpenBuild(build) } }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                if (visible.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("Сборки не найдены. Попробуйте другое название.", 14, C.text2, lineHeight = 20)
                        T("Сбросить поиск", 14, C.accent, lineHeight = 18, modifier = Modifier.buttonClickNoRipple { query = "" })
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedBuild(build: LauncherBuild?, onOpenBuilds: () -> Unit, onOpenBuild: (LauncherBuild) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth()
            .softShadow(shape, elevation = 4.dp, color = Color(0x10000000))
            .clip(shape).background(C.surface).border(1.dp, C.fill, shape).padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            T(if (build == null) "Новая сборка" else "Ваша сборка", 13, C.text2, lineHeight = 16)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T(build?.name ?: "Создайте сборку", 32, C.text1, FontWeight.SemiBold, lineHeight = 39, maxLines = 2)
                T(build?.let { "Minecraft ${it.version}, ${it.loader}" }
                    ?: "Выберите версию и загрузчик в разделе «Сборки».", 15, C.text2, lineHeight = 20)
                if (build != null && build.playSummary.isNotBlank()) {
                    T(build.playSummary, 13, C.text3, lineHeight = 18, maxLines = 1)
                }
            }
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(C.accentFill)
                    .buttonClickNoRipple { if (build == null) onOpenBuilds() else onOpenBuild(build) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            ) {
                T(if (build == null) "К сборкам" else "Открыть сборку", 15, C.onAccent, FontWeight.Medium, lineHeight = 18)
            }
        }
        if (build != null) BuildCover(build, Modifier.width(200.dp).height(184.dp), artworkSize = 128)
    }
}

@Composable
private fun BuildCover(build: LauncherBuild, modifier: Modifier = Modifier, artworkSize: Int = 72) {
    val snapshot = build.icon == "snapshot" || build.icon == "dirt" || build.version.contains("snapshot", true)
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (snapshot) C.fill else C.selectedGreen),
        contentAlignment = Alignment.Center,
    ) {
        InstanceArtwork(build, Modifier.size(artworkSize.dp))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .clip(RoundedCornerShape(6.dp)).background(C.surface).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            T(build.loader, 11, C.text2, lineHeight = 14, maxLines = 1)
        }
    }
}

@Composable
private fun RecentCard(build: LauncherBuild, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.softShadow(shape, elevation = 4.dp, color = Color(0x10000000))
            .clip(shape).background(C.surface).border(1.dp, C.fill, shape)
            .clickNoRipple(onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BuildCover(build, Modifier.fillMaxWidth().height(112.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(build.name, 17, C.text1, FontWeight.Medium, lineHeight = 21, maxLines = 1)
            T("Minecraft ${build.version}, ${build.loader}", 13, C.text2, lineHeight = 16, maxLines = 1)
        }
    }
}
