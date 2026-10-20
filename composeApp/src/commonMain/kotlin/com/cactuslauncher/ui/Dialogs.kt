package com.cactuslauncher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cactuslauncher.theme.C
import com.cactuslauncher.theme.LocalAppFont
import com.cactuslauncher.game.GameService
import com.cactuslauncher.model.MinecraftVersion
import com.cactuslauncher.model.VersionFilter
import com.cactuslauncher.model.JavaOptimizationInfo
import com.cactuslauncher.model.LauncherUpdate
import com.cactuslauncher.model.isValidPlayerName
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun Modal(onDismiss: () -> Unit, compact: Boolean = false, height: androidx.compose.ui.unit.Dp = if (compact) 460.dp else 640.dp, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        entrance.animateTo(1f, tween(CactusMotion.normal, easing = CactusMotion.easeOut))
    }
    Box(
        Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
        }.focusable().background(C.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(560.dp)
                .padding(vertical = 16.dp)
                .heightIn(max = height)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = 0.985f + entrance.value * 0.015f
                    scaleY = scaleX
                    translationY = (1f - entrance.value) * 8.dp.toPx()
                }
                .softShadow(RoundedCornerShape(24.dp), elevation = 32.dp, color = Color(0x33000000))
                .clip(RoundedCornerShape(24.dp))
                .background(C.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        ) { content() }
    }
}

@Composable
fun NewBuildDialog(game: GameService, busy: Boolean = false, saveError: String? = null, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var loader by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("Моя сборка") }
    var version by remember { mutableStateOf<MinecraftVersion?>(null) }
    var versionsOpen by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<MinecraftVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VersionFilter.RELEASE) }
    LaunchedEffect(attempt) {
        loading = true
        error = null
        try {
            versions = game.minecraftVersions()
            version = versions.find { it.id == version?.id } ?: versions.find { it.latestRelease } ?: versions.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить версии Minecraft."
        } finally {
            loading = false
        }
    }
    val filteredVersions = remember(versions, query, filter) {
        versions.filter { filter.matches(it) && it.id.contains(query.trim(), true) }
    }
    Modal(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            BannerArtwork(Modifier.fillMaxWidth().height(200.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Новая сборка", 24, C.text1, FontWeight.Bold, lineHeight = 29)
                    T(
                        "Выберите версию и загрузчик. Настройки можно изменить позже.",
                        15,
                        C.text2,
                        lineHeight = 18,
                    )
                }
                Field("Название") {
                    SearchBox(name, { name = it }, "Название сборки", Modifier.fillMaxWidth(), height = 44.dp)
                }
                Field("Версия Minecraft") {
                    Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(C.input)
                            .clickNoRipple { versionsOpen = true }
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(version?.id ?: if (loading) "Загрузка версий…" else "Выберите версию", 15, C.text1, lineHeight = 18)
                        T("▾", 13, C.text2, lineHeight = 16)
                    }
                    MotionDropdown(versionsOpen, onDismiss = { versionsOpen = false }) {
                        Column(Modifier.width(440.dp).softShadow(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(C.elevated).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SearchBox(query, { query = it }, "Поиск версии", Modifier.fillMaxWidth(), height = 40.dp)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                VersionFilter.entries.forEach { value ->
                                    T(value.label, 13, if (filter == value) C.accent else C.text2, lineHeight = 16,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (filter == value) C.accentSoft else C.fill).clickNoRipple { filter = value }.padding(10.dp))
                                }
                            }
                            when {
                                loading -> T("Загрузка каталога Mojang…", 14, C.text2, lineHeight = 18)
                                error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    T(error.orEmpty(), 14, C.text2, lineHeight = 18)
                                    T("Повторить", 14, C.accent, lineHeight = 18, modifier = Modifier.clickNoRipple { attempt++ })
                                }
                                filteredVersions.isEmpty() -> T("Версии не найдены", 14, C.text2, lineHeight = 18)
                                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(filteredVersions, key = { it.id }) { value ->
                                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (version?.id == value.id) C.accentSoft else Color.Transparent).clickNoRipple { version = value; versionsOpen = false }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                            T(value.id, 15, if (version?.id == value.id) C.accent else C.text1, lineHeight = 18)
                                            T(value.releasedAt.take(10), 12, C.text2, lineHeight = 16)
                                        }
                                    }
                                }
                            }
                            if (filter == VersionFilter.POPULAR) T("Последний релиз и версии, под которые больше всего модов.", 12, C.text2, lineHeight = 16)
                        }
                    }
                    }
                }
                Field("Загрузчик") {
                    AnimatedSegments(listOf("Vanilla", "Fabric", "Forge"), loader, { loader = it })
                }
                saveError?.let { T(it, 13, C.danger, lineHeight = 18) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(C.fill)
                            .clickNoRipple(onDismiss)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        T("Отмена", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (version != null && name.isNotBlank()) C.accentFill else C.fill)
                            .clickNoRipple { version?.let { if (!busy && name.isNotBlank()) onCreate(name.trim(), it.id, listOf("Vanilla", "Fabric", "Forge")[loader]) } }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        T(if (busy) "Создание…" else "Создать", 15, C.onAccent, FontWeight.Medium, lineHeight = 18)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(label, 13, C.text1, FontWeight.Medium, lineHeight = 16)
        content()
    }
}

@Composable
fun SettingsDialog(
    settings: LauncherSettings,
    javaOptimizationInfo: JavaOptimizationInfo? = null,
    onDismiss: () -> Unit,
    onReset: () -> Unit = {},
    onRestartSetup: () -> Unit = {},
    error: String? = null,
    updateStatus: String? = null,
    update: LauncherUpdate? = null,
    updateDownloaded: Boolean = false,
    updateBusy: Boolean = false,
    onCheckUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
) {
    var resolutionsOpen by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }
    val settingsScroll = rememberScrollState()
    Modal(onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("Настройки", 24, C.text1, FontWeight.Bold, lineHeight = 29)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(C.fill)
                        .clickNoRipple(onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    T("✕", 14, C.text2, FontWeight.Medium, lineHeight = 17)
                }
            }
            Column(
                Modifier
                    .drawWithContent {
                        drawContent()
                        // Fade at the bottom shows that the list continues below on short windows.
                        if (settingsScroll.canScrollForward) {
                            val fade = 40.dp.toPx()
                            drawRect(Brush.verticalGradient(listOf(C.surface.copy(alpha = 0f), C.surface), startY = size.height - fade, endY = size.height),
                                topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade))
                        }
                    }
                    .verticalScroll(settingsScroll)
                    .padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("ОФОРМЛЕНИЕ", 13, C.text2, lineHeight = 16)
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.input).padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        T("Тема", 15, C.text1, lineHeight = 18)
                        val themes = listOf("system", "light", "dark")
                        AnimatedSegments(listOf("Как в системе", "Светлая", "Тёмная"), themes.indexOf(settings.theme).coerceAtLeast(0),
                            { settings.theme = themes[it] }, fill = C.fill)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("СКАЧИВАНИЕ", 13, C.text2, lineHeight = 16)
                    GameRow("Параллельные загрузки") {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("−", 20, if (settings.parallelDownloads > 1) C.accent else C.text2, lineHeight = 24,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.fill).clickNoRipple { settings.parallelDownloads = (settings.parallelDownloads - 1).coerceAtLeast(1) }.padding(horizontal = 12.dp, vertical = 8.dp))
                            T("${settings.parallelDownloads}", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                            T("+", 20, if (settings.parallelDownloads < 16) C.accent else C.text2, lineHeight = 24,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.fill).clickNoRipple { settings.parallelDownloads = (settings.parallelDownloads + 1).coerceAtMost(16) }.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("ПЛЕЕР", 13, C.text2, lineHeight = 16)
                    GameRow("Показывать плеер") { Switch(settings.showPlayer) { settings.showPlayer = it } }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("МОДЫ", 13, C.text2, lineHeight = 16)
                    GameRow("Проверять моды на угрозы") {
                        Switch(settings.modAntivirusEnabled) { settings.modAntivirusEnabled = it }
                    }
                    GameRow("Переводить описания на русский") {
                        Switch(settings.translateModDescriptions) { settings.translateModDescriptions = it }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("ПАМЯТЬ", 13, C.text2, lineHeight = 16)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(C.input)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T("Выделено ОЗУ", 15, C.text1, lineHeight = 18)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(C.accentSoft)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    T(if (settings.automaticMemory) "AUTO" else "ВРУЧНУЮ", 11, C.accent, lineHeight = 13,
                                        modifier = Modifier.clickNoRipple { settings.automaticMemory = !settings.automaticMemory })
                                }
                                T("${settings.memoryGb} ГБ", 15, C.text1, lineHeight = 18)
                            }
                        }
                        MemoryControl(settings.memoryGb, { settings.memoryGb = it; settings.automaticMemory = false },
                            maxGb = javaOptimizationInfo?.maxMemoryGb ?: 16)
                        javaOptimizationInfo?.let { info -> MemoryWarning(settings.memoryGb, info) }
                        GameRow("Автооптимизация Java") {
                            Switch(settings.autoJavaOptimization) { settings.autoJavaOptimization = it }
                        }
                        GameRow("Ghost Mode") {
                            Switch(settings.ghostMode) { settings.ghostMode = it }
                        }
                        javaOptimizationInfo?.let { info ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                T("${info.totalRamGb} ГБ ОЗУ, доступно ${info.availableRamGb} ГБ, ${info.logicalProcessors} потоков", 12, C.text2, lineHeight = 15, maxLines = 1)
                                T(info.processorName, 12, C.text2, lineHeight = 15, maxLines = 1)
                                T("Видеокарта: ${info.graphicsName}", 12, C.text2, lineHeight = 15, maxLines = 1)
                                T("Java лаунчера: ${info.javaVersion}", 12, C.text2, lineHeight = 15, maxLines = 1)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("ИГРА", 13, C.text2, lineHeight = 16)
                    GameRow("Разрешение окна") {
                        Box {
                            T("${settings.resolution} ▾", 15, C.text2, lineHeight = 19, modifier = Modifier.clickNoRipple { resolutionsOpen = true })
                            MotionDropdown(visible = resolutionsOpen, onDismiss = { resolutionsOpen = false }) {
                                Column(Modifier.width(200.dp).softShadow(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(C.elevated).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("1280 × 720", "1600 × 900", "1920 × 1080", "2560 × 1440").forEach { resolution ->
                                        T(resolution, 15, if (settings.resolution == resolution) C.accent else C.text1, lineHeight = 18,
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickNoRipple { settings.resolution = resolution; resolutionsOpen = false }.padding(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    GameRow("Полноэкранный режим") { Switch(settings.fullscreen) { settings.fullscreen = it } }
                    GameRow("Проверять и скачивать автоматически") { Switch(settings.autoUpdate) { settings.autoUpdate = it } }
                    updateStatus?.let { T(it, 13, if (update?.available == true && updateDownloaded) C.success else C.text2, lineHeight = 17) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(C.fill)
                                .clickNoRipple { if (!updateBusy) onCheckUpdates() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            T(if (updateBusy) "Проверяем…" else "Проверить сейчас", 13, C.text1, FontWeight.Medium, lineHeight = 17)
                        }
                        if (update?.available == true && !updateDownloaded) Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(C.accentFill)
                                .clickNoRipple { if (!updateBusy) onDownloadUpdate() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            T("Скачать обновление", 13, C.onAccent, FontWeight.Medium, lineHeight = 17)
                        }
                        if (updateDownloaded) Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(C.accentFill)
                                .clickNoRipple(onOpenUpdate)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            T("Открыть установщик", 13, C.onAccent, FontWeight.Medium, lineHeight = 17)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("РЕЖИМ", 13, C.text2, lineHeight = 16)
                    GameRow("Продвинутый режим") { Switch(settings.advancedMode) { settings.advancedMode = it } }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("ПАПКА ИГРЫ", 13, C.text2, lineHeight = 16)
                    if (editingFolder) {
                        SearchBox(settings.folder, { settings.folder = it }, "Папка игры", Modifier.fillMaxWidth(), height = 44.dp)
                        T("Готово", 15, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple { editingFolder = false })
                    } else GameRow(settings.folder) {
                        T("Изменить", 15, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple { editingFolder = true })
                    }
                }
                error?.let { T(it, 13, C.danger, lineHeight = 18) }
                if (resetDone) T("Настройки сброшены", 13, C.success, lineHeight = 17)
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.fill)
                        .clickNoRipple { onReset(); resetDone = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    T("Сбросить все", 14, C.text1, FontWeight.Medium, lineHeight = 18)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("БЕТА", 13, C.text2, lineHeight = 16)
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.fill)
                            .clickNoRipple(onRestartSetup)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        T("Показать начальную настройку снова", 14, C.text1, FontWeight.Medium, lineHeight = 18)
                    }
                }
            }
        }
    }
}

@Composable
fun InitialSetupDialog(
    settings: LauncherSettings,
    javaOptimizationInfo: JavaOptimizationInfo?,
    error: String?,
    initialNickname: String,
    onComplete: suspend (String?) -> Unit,
    onChooseFolder: suspend (String) -> String?,
) {
    var step by remember { mutableIntStateOf(1) }
    var nickname by remember { mutableStateOf(initialNickname) }
    var editingFolder by remember { mutableStateOf(false) }
    var otherSettingsOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var choosingFolder by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val nicknameValid = isValidPlayerName(nickname)
    LaunchedEffect(initialNickname) {
        if (step == 1 && nickname == "Nick") nickname = initialNickname
    }
    fun finish(name: String?) {
        if (busy) return
        busy = true
        localError = null
        scope.launch {
            try {
                onComplete(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message ?: "Не удалось сохранить профиль."
            } finally {
                busy = false
            }
        }
    }
    Box(Modifier.fillMaxSize().background(C.surface).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            AnimatedContent(
                targetState = step,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    val forward = targetState > initialState
                    (fadeIn(tween(CactusMotion.normal, easing = CactusMotion.easeOut)) +
                        slideInHorizontally(
                            animationSpec = tween(CactusMotion.normal, easing = CactusMotion.easeOut),
                            initialOffsetX = { width -> if (forward) width / 18 else -width / 18 },
                        )) togetherWith
                        (fadeOut(tween(CactusMotion.quick, easing = CactusMotion.easeOut)) +
                            slideOutHorizontally(
                                animationSpec = tween(CactusMotion.quick, easing = CactusMotion.easeOut),
                                targetOffsetX = { width -> if (forward) -width / 24 else width / 24 },
                            ))
                },
                label = "setupStep",
            ) { currentStep ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("Шаг $currentStep из 2", 13, C.text2, lineHeight = 16)
                        T(if (currentStep == 1) "Как вас зовут?" else "Папка для игры", 28, C.text1, FontWeight.Bold, lineHeight = 34)
                        T(
                            if (currentStep == 1) "Имя будет видно в лаунчере. Его можно поменять в любой момент."
                            else "Здесь будут храниться сборки, миры и моды. Папку можно поменять позже.",
                            15, C.text2, lineHeight = 20,
                        )
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (currentStep == 1) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                T("Никнейм", 13, C.text1, FontWeight.Medium, lineHeight = 16)
                                SetupInput(nickname, { nickname = it; localError = null }, "Steve")
                                T("3–16 символов: A–Z, a–z, 0–9 и _", 12, if (nicknameValid || nickname.isEmpty()) C.text2 else C.danger, lineHeight = 15)
                            }
                            SetupToggle("Автооптимизация Java", settings.autoJavaOptimization) { settings.autoJavaOptimization = it }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                T("Путь", 13, C.text1, FontWeight.Medium, lineHeight = 16)
                                Row(
                                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(C.surface)
                                        .border(1.dp, C.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (editingFolder) SetupTextEntry(settings.folder, { settings.folder = it }, "Папка игры", Modifier.weight(1f))
                                    else T(settings.folder, 15, C.text1, lineHeight = 18, maxLines = 1, modifier = Modifier.weight(1f))
                                    T(if (editingFolder) "Готово" else "Изменить", 13, C.accent, FontWeight.Medium, lineHeight = 16,
                                        modifier = Modifier.clickNoRipple { editingFolder = !editingFolder })
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                                        .background(C.fill)
                                        .clickNoRipple {
                                            if (!choosingFolder && !busy) {
                                                scope.launch {
                                                    choosingFolder = true
                                                    localError = null
                                                    try {
                                                        onChooseFolder(settings.folder)?.let { settings.folder = it }
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        localError = e.message ?: "Не удалось выбрать папку."
                                                    } finally {
                                                        choosingFolder = false
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    T(if (choosingFolder) "Открываем выбор папки…" else "Выбрать папку", 14, C.accent, FontWeight.Medium, lineHeight = 18)
                                }
                            }
                            SetupToggle("Проверять и скачивать обновления автоматически", settings.autoUpdate) { settings.autoUpdate = it }
                            SetupToggle("Проверять моды на угрозы", settings.modAntivirusEnabled) { settings.modAntivirusEnabled = it }
                            SetupToggle("Ghost Mode", settings.ghostMode) { settings.ghostMode = it }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                T("Параллельные загрузки", 13, C.text1, FontWeight.Medium, lineHeight = 16)
                                Row(
                                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(C.surface)
                                        .border(1.dp, C.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    T("${settings.parallelDownloads}", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        T("−", 18, C.accent, lineHeight = 22, modifier = Modifier.clickNoRipple { settings.parallelDownloads = (settings.parallelDownloads - 1).coerceAtLeast(1) })
                                        T("+", 18, C.accent, lineHeight = 22, modifier = Modifier.clickNoRipple { settings.parallelDownloads = (settings.parallelDownloads + 1).coerceAtMost(16) })
                                    }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.fill)
                                        .clickNoRipple { otherSettingsOpen = !otherSettingsOpen }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    T("Остальное", 14, C.text1, FontWeight.Medium, lineHeight = 18)
                                    val arrowRotation by animateFloatAsState(
                                        targetValue = if (otherSettingsOpen) 0f else 180f,
                                        animationSpec = tween(CactusMotion.normal, easing = CactusMotion.easeOut),
                                        label = "other-settings-arrow",
                                    )
                                    Canvas(Modifier.size(16.dp).rotate(arrowRotation)) {
                                        val centerX = size.width / 2f
                                        val top = size.height * 0.28f
                                        val bottom = size.height * 0.72f
                                        val wing = 3.5.dp.toPx()
                                        val stroke = 1.5.dp.toPx()
                                        drawLine(C.text1, Offset(centerX, bottom), Offset(centerX, top), stroke, cap = StrokeCap.Round)
                                        drawLine(C.text1, Offset(centerX, top), Offset(centerX - wing, top + wing), stroke, cap = StrokeCap.Round)
                                        drawLine(C.text1, Offset(centerX, top), Offset(centerX + wing, top + wing), stroke, cap = StrokeCap.Round)
                                    }
                                }
                                AnimatedVisibility(
                                    visible = otherSettingsOpen,
                                    enter = expandVertically(tween(CactusMotion.normal, easing = CactusMotion.easeOut)) +
                                        fadeIn(tween(CactusMotion.normal, easing = CactusMotion.easeOut)),
                                    exit = shrinkVertically(tween(CactusMotion.quick, easing = CactusMotion.easeOut)) +
                                        fadeOut(tween(CactusMotion.quick, easing = CactusMotion.easeOut)),
                                ) {
                                    SetupToggle("Автоперевод описаний модов", settings.translateModDescriptions) {
                                        settings.translateModDescriptions = it
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val primary = if (step == 1) "Продолжить" else "Готово"
                val canContinue = nicknameValid && !busy
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (busy) C.text2 else if (canContinue) C.accentFill else C.fill)
                        .then(if (!canContinue) Modifier else Modifier.clickNoRipple {
                            if (step == 1) { localError = null; step = 2 }
                            else finish(nickname.trim())
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    T(if (busy) "Сохраняем…" else primary, 16, if (canContinue || busy) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 20)
                }
                if (step == 1) {
                    T("Пропустить настройку", 15, C.accent, lineHeight = 20,
                        modifier = Modifier.clickNoRipple { finish(null) })
                } else {
                    T("Назад", 15, C.accent, lineHeight = 20, modifier = Modifier.clickNoRipple { if (!busy) step = 1 })
                }
                localError?.let { T(it, 13, C.danger, lineHeight = 17, maxLines = 2) }
                error?.let { T(it, 12, C.danger, lineHeight = 16, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun SetupInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.fillMaxWidth().height(44.dp).clip(shape).background(C.surface)
            .border(1.dp, C.border, shape).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        SetupTextEntry(value, onValueChange, placeholder, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SetupTextEntry(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) T(placeholder, 15, C.text3, lineHeight = 18)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = C.text1,
                fontSize = 15.sp,
                fontFamily = LocalAppFont.current,
            ),
            cursorBrush = SolidColor(C.accent),
        )
    }
}

@Composable
fun MemoryWarning(memoryGb: Int, info: JavaOptimizationInfo) {
    if (info.availableRamGb in 1 until memoryGb)
        T("Сейчас свободно только ${info.availableRamGb} ГБ из ${info.totalRamGb} ГБ. Закройте лишние программы, иначе игра может тормозить или вылететь.",
            12, C.danger, lineHeight = 15)
}

@Composable
private fun SetupToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, 15, C.text1, lineHeight = 19, modifier = Modifier.weight(1f))
        Switch(checked) { onToggle(it) }
    }
}

@Composable
private fun GameRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.input)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 15, C.text1, lineHeight = 18, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}
