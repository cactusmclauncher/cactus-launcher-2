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
import com.cactuslauncher.ui.softShadow
import com.cactuslauncher.ui.BannerArtwork
import com.cactuslauncher.ui.BuildArtwork
import com.cactuslauncher.ui.SearchBox
import androidx.compose.runtime.*

@Composable
fun HomeScreen(builds: List<com.cactuslauncher.model.LauncherBuild> = emptyList(), onOpenBuild: () -> Unit) {
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
            T("Главная", 32, C.text1, FontWeight.Bold, lineHeight = 39)
            SearchBox(query, { query = it }, "Поиск сборок", Modifier.width(240.dp), fill = C.fill, height = 36.dp, radius = 10.dp)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(C.banner)
        ) {
            BannerArtwork(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.12f)))))
            Row(
                Modifier.fillMaxSize().padding(40.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    T("Новое обновление", 13, C.bannerEyebrow, lineHeight = 16)
                    T("Minecraft 26.3", 44, Color.White, FontWeight.Bold, lineHeight = 53)
                    T(
                        "Wilderness Bound: новый лес с тополями, заброшенные лагеря и соломенные кровати.",
                        17,
                        C.bannerText,
                        lineHeight = 21,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.accentFill)
                        .clickNoRipple(onOpenBuild)
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    T("К сборкам", 17, C.onAccent, FontWeight.Medium, lineHeight = 21)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            T("Ваши сборки", 20, C.text1, lineHeight = 24)
            val visible = builds.filter { it.name.contains(query, true) }
            visible.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { build -> RecentCard(build.name, "Minecraft ${build.version}, ${build.loader}", C.green, Modifier.weight(1f), onOpenBuild, build) }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            if (visible.isEmpty()) {
                T(if (builds.isEmpty()) "Сборок пока нет. Добавьте первую в разделе «Сборки»." else "Сборки не найдены. Попробуйте другое название.", 14, C.text2, lineHeight = 20)
            }
        }
    }
}

@Composable
private fun RecentCard(
    title: String,
    meta: String,
    icon: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    build: com.cactuslauncher.model.LauncherBuild? = null,
) {
    Column(
        modifier
            .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .clickNoRipple(onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (build != null) com.cactuslauncher.ui.InstanceArtwork(build, Modifier.size(48.dp)) else BuildArtwork(title, icon, Modifier.size(48.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(title, 17, C.text1, lineHeight = 21)
            T(meta, 13, C.text2, lineHeight = 16)
        }
    }
}
