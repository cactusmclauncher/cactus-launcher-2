package com.cactuslauncher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.OtherOverview
import com.cactuslauncher.model.OtherSection
import com.cactuslauncher.ui.CactusMotion
import com.cactuslauncher.ui.PixelIcon
import com.cactuslauncher.resources.Res
import com.cactuslauncher.resources.icon_painting
import com.cactuslauncher.resources.icon_compass
import com.cactuslauncher.model.Screenshot
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.Modal
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow
import kotlinx.coroutines.CancellationException

/** One entry of the "Остальное" menu: the section, its picture and a line with live numbers. */
private class OtherEntry(val section: OtherSection, val description: String, val image: @Composable (Modifier) -> Unit)

/**
 * "Остальное": a menu of sections (rows in the build list style); a section opens full screen with
 * "← Остальное" above its title. Esc goes back to the menu.
 */
@Composable
fun OtherScreen(
    builds: List<LauncherBuild>,
    loadingBuilds: Boolean,
    section: OtherSection?,
    onOpenSection: (OtherSection?) -> Unit,
    overview: OtherOverview?,
    shotThumbnail: suspend (buildId: String, Screenshot) -> ImageBitmap?,
    /** A dialog above the screen (the screenshot viewer) handles keys itself. */
    overlayOpen: Boolean,
    screenshots: ScreenshotsSectionState,
    servers: ServersSectionState,
    onOpenBuilds: () -> Unit,
) {
    val pageOffset = with(LocalDensity.current) { 6.dp.roundToPx() }
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            (fadeIn(tween(180, easing = CactusMotion.easeOut)) + slideInVertically(tween(200, easing = CactusMotion.easeOut)) { pageOffset })
                .togetherWith(fadeOut(tween(90)))
        },
        label = "other-section",
    ) { current ->
        if (current == null) OtherMenu(otherEntries(overview, shotThumbnail)) { onOpenSection(it) }
        else {
            val focus = remember { FocusRequester() }
            // Back to the section after the viewer closes or the server form (which had focus) goes away.
            LaunchedEffect(overlayOpen, servers.form == null) { if (!overlayOpen) runCatching { focus.requestFocus() } }
            Column(
                Modifier.fillMaxSize().focusRequester(focus).onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && !overlayOpen) { onOpenSection(null); true } else false
                }.focusable().padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                T("← Остальное", 14, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple { onOpenSection(null) })
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(current.title, 32, C.text1, FontWeight.Bold, lineHeight = 39)
                    T(current.subtitle, 14, C.text2, lineHeight = 20)
                }
                when (current) {
                    OtherSection.Screenshots -> ScreenshotsSection(builds, loadingBuilds, screenshots, onOpenBuilds)
                    OtherSection.Servers -> ServersSection(builds, loadingBuilds, servers, onOpenBuilds)
                }
            }
        }
    }
}

/** New sections add an entry here (and a branch in [OtherScreen]). */
private fun otherEntries(overview: OtherOverview?, shotThumbnail: suspend (String, Screenshot) -> ImageBitmap?) = listOf(
    OtherEntry(
        OtherSection.Screenshots,
        when {
            overview == null -> "Снимки из игры по сборкам"
            overview.screenshots == 0 -> "Скриншотов пока нет, в игре они делаются клавишей F2"
            else -> "${screenshotsCount(overview.screenshots)} во всех сборках"
        },
    ) { modifier -> LatestShotImage(overview?.latestShot, shotThumbnail, modifier) },
    OtherEntry(
        OtherSection.Servers,
        when {
            overview == null || overview.servers == 0 -> "Список серверов из игры, пинг и быстрый вход"
            else -> "${serversCount(overview.servers)} в ${buildsCount(overview.serverBuilds)}: пинг и быстрый вход"
        },
    ) { modifier -> PixelIcon(Res.drawable.icon_compass, "Серверы", modifier) },
)

@Composable
private fun OtherMenu(entries: List<OtherEntry>, onOpen: (OtherSection) -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Остальное", 32, C.text1, FontWeight.Bold, lineHeight = 39)
            T("Скриншоты, серверы и другие возможности лаунчера", 14, C.text2, lineHeight = 20)
        }
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().softShadow(RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)).background(C.surface)
                    .clickNoRipple { onOpen(entry.section) }.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entry.image(Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(entry.section.title, 18, C.text1, FontWeight.Medium, lineHeight = 22)
                    T(entry.description, 14, C.text2, lineHeight = 20, maxLines = 1)
                }
                T("Открыть →", 13, C.accent, FontWeight.Medium, lineHeight = 16)
            }
        }
    }
}

/** The newest screenshot of any build, or a painting item while there is none. */
@Composable
private fun LatestShotImage(latest: Pair<String, Screenshot>?, thumbnail: suspend (String, Screenshot) -> ImageBitmap?, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, latest) {
        value = latest?.let { (buildId, shot) ->
            try { thumbnail(buildId, shot) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    val current = image
    if (current != null) Image(current, "Последний скриншот", modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    else PixelIcon(Res.drawable.icon_painting, "Скриншоты", modifier)
}

/** What the screenshots section shows and does; App wires it to ScreenshotsState and GameService. */
class ScreenshotsSectionState(
    val selectedBuildId: String?,
    val shots: List<Screenshot>,
    val loading: Boolean,
    val error: String?,
    val thumbnail: suspend (Screenshot) -> ImageBitmap?,
    val onSelectBuild: (LauncherBuild) -> Unit,
    val onOpen: (Screenshot) -> Unit,
    val onOpenFolder: () -> Unit,
)

@Composable
private fun ColumnScope.ScreenshotsSection(
    builds: List<LauncherBuild>,
    loadingBuilds: Boolean,
    state: ScreenshotsSectionState,
    onOpenBuilds: () -> Unit,
) {
    val selectedBuild = builds.firstOrNull { it.id == state.selectedBuildId }
    BuildPicker(builds = builds, selected = selectedBuild, loading = loadingBuilds, onSelect = state.onSelectBuild)
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selectedBuildId) { gridState.scrollToItem(0) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loadingBuilds -> EmptyState("Загружаем сборки…", "Скриншоты появятся после загрузки сборок.")
            builds.isEmpty() -> EmptyState("Сборок пока нет", "Создайте сборку, и снимки из игры появятся здесь.", "Перейти к сборкам", onOpenBuilds)
            selectedBuild == null -> EmptyState("Выберите сборку", "Скриншоты хранятся в папке каждой сборки.")
            state.error != null -> EmptyState(state.error, "Проверьте папку скриншотов сборки.", "Открыть папку скриншотов", state.onOpenFolder)
            state.loading -> EmptyState("Читаем папку скриншотов", "Ищем снимки сборки «${selectedBuild.name}»…")
            state.shots.isEmpty() -> EmptyState(
                "Скриншотов пока нет",
                "Нажмите F2 в игре: снимок сохранится в папке «${selectedBuild.name}/screenshots» и появится здесь.",
                "Открыть папку скриншотов", state.onOpenFolder,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    T("${screenshotsCount(state.shots.size)} в сборке ${selectedBuild.name}", 13, C.text2, lineHeight = 16,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
                items(state.shots, key = { it.fileName }) { shot ->
                    ScreenshotCard(shot, selectedBuild.id, state.thumbnail) { state.onOpen(shot) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        T(selectedBuild?.let { "Папка: ${it.name}/screenshots" } ?: "Сначала выберите сборку", 13, C.text2, lineHeight = 17)
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(if (selectedBuild == null) C.fill else C.accentFill)
                .then(if (selectedBuild == null) Modifier else Modifier.clickNoRipple(state.onOpenFolder))
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            T("Открыть папку скриншотов", 15, if (selectedBuild == null) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 18)
        }
    }
}

@Composable
private fun ScreenshotCard(shot: Screenshot, buildId: String, thumbnail: suspend (Screenshot) -> ImageBitmap?, onOpen: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, buildId, shot) {
        value = try { thumbnail(shot) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    Column(
        Modifier.fillMaxWidth().softShadow(RoundedCornerShape(16.dp), elevation = 4.dp, color = Color(0x10000000))
            .clip(RoundedCornerShape(16.dp)).background(C.surface).clickNoRipple(onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(C.fill)) {
            image?.let { Image(it, contentDescription = "Скриншот ${shot.takenText}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(shot.takenText, 13, C.text1, FontWeight.Medium, lineHeight = 16, maxLines = 1)
            T(shot.fileName, 12, C.text2, lineHeight = 15, maxLines = 1)
        }
    }
}

/**
 * The open screenshot in the dialog style of the app: ←/→ switch, Esc closes; actions are text links and
 * deleting asks inline, as removing a mod does.
 */
@Composable
fun ScreenshotViewer(
    shot: Screenshot,
    position: Int,
    count: Int,
    busy: Boolean,
    image: suspend (Screenshot) -> ImageBitmap?,
    onMove: (Int) -> Unit,
    onClose: () -> Unit,
    onOpenInSystem: () -> Unit,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember(shot.fileName) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, shot) {
        value = try { image(shot) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    Modal(
        onDismiss = onClose, height = 760.dp, width = 1040.dp,
        onKey = {
            if (it.type != KeyEventType.KeyDown) false
            else when (it.key) {
                Key.DirectionLeft -> { onMove(-1); true }
                Key.DirectionRight -> { onMove(1); true }
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(shot.takenText, 17, C.text1, FontWeight.SemiBold, lineHeight = 22)
                    T("${shot.fileName} · $position из $count", 13, C.text2, lineHeight = 17, maxLines = 1)
                }
                T("Закрыть", 13, C.text2, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onClose))
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.fill),
                contentAlignment = Alignment.Center,
            ) {
                val current = bitmap
                if (current != null) Image(current, contentDescription = "Скриншот ${shot.takenText}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else T("Открываем скриншот…", 13, C.text2, lineHeight = 17)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                val canMove = count > 1
                T("← Предыдущий", 14, if (canMove) C.accent else C.text2, FontWeight.Medium, lineHeight = 18,
                    modifier = if (canMove) Modifier.clickNoRipple { onMove(-1) } else Modifier)
                T("Следующий →", 14, if (canMove) C.accent else C.text2, FontWeight.Medium, lineHeight = 18,
                    modifier = if (canMove) Modifier.clickNoRipple { onMove(1) } else Modifier)
                Spacer(Modifier.weight(1f))
                if (confirmingDelete) {
                    T("Удалить скриншот? Файл попадёт в корзину.", 14, C.text1, lineHeight = 18)
                    T("Отмена", 14, C.text2, lineHeight = 18, modifier = Modifier.clickNoRipple { confirmingDelete = false })
                    T("Удалить", 14, if (busy) C.text2 else C.dangerInk, FontWeight.Medium, lineHeight = 18,
                        modifier = if (busy) Modifier else Modifier.clickNoRipple { confirmingDelete = false; onDelete() })
                } else {
                    ViewerLink("Открыть в системе", busy, onOpenInSystem)
                    ViewerLink("Показать в папке", busy, onReveal)
                    ViewerLink("Скопировать", busy, onCopy)
                    T("Удалить", 14, if (busy) C.text2 else C.dangerInk, FontWeight.Medium, lineHeight = 18,
                        modifier = if (busy) Modifier else Modifier.clickNoRipple { confirmingDelete = true })
                }
            }
        }
    }
}

@Composable
private fun ViewerLink(label: String, busy: Boolean, onClick: () -> Unit) {
    T(label, 14, if (busy) C.text2 else C.accent, FontWeight.Medium, lineHeight = 18,
        modifier = if (busy) Modifier else Modifier.clickNoRipple(onClick))
}

/** "в 1 сборке", "в 3 сборках". */
private fun buildsCount(count: Int) = "$count " + if (count % 10 == 1 && count % 100 != 11) "сборке" else "сборках"

/** "1 скриншот", "3 скриншота", "11 скриншотов". */
private fun screenshotsCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "скриншотов"
        count % 10 == 1 -> "скриншот"
        count % 10 in 2..4 -> "скриншота"
        else -> "скриншотов"
    }
    return "$count $word"
}
