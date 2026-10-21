package com.cactuslauncher.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow
import com.cactuslauncher.ui.CactusMotion
import com.cactuslauncher.ui.SearchBox
import com.cactuslauncher.ui.BuildArtwork
import com.cactuslauncher.ui.InstanceArtwork
import com.cactuslauncher.ui.BuildIconPicker
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.ModUpdateBackup
import com.cactuslauncher.model.JavaOptimizationPlan
import com.cactuslauncher.model.JavaOptimizationInfo
import com.cactuslauncher.ui.MemoryWarning
import com.cactuslauncher.ui.SliderThumb
import com.cactuslauncher.model.InstalledResourcePack
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun BuildManageScreen(build: LauncherBuild, busy: Boolean = false, running: Boolean = false, error: String? = null, memoryGb: Int = 4, automaticMemory: Boolean = true, javaPlan: JavaOptimizationPlan? = null, javaPlanLoading: Boolean = false, javaOptimizationInfo: JavaOptimizationInfo? = null, resourcePacks: List<InstalledResourcePack> = emptyList(), resourcePacksLoading: Boolean = false, resourcePacksError: String? = null, onImportResourcePack: () -> Unit = {}, onToggleResourcePack: (InstalledResourcePack, Boolean) -> Unit = { _, _ -> }, onOpenResourcePacks: () -> Unit = {}, onRefreshResourcePacks: () -> Unit = {}, onMemoryChange: (Int) -> Unit = {}, onAutomaticMemoryChange: (Boolean) -> Unit = {}, onIcon: (String) -> Unit = {}, onRename: (String) -> Unit = {}, onDelete: (Boolean) -> Unit = {}, onOpenFolder: () -> Unit = {}, onAutoUpdateMods: (Boolean) -> Unit = {}, lastModUpdate: ModUpdateBackup? = null, rollingBackMods: Boolean = false, onRollbackMods: () -> Unit = {}, onBack: () -> Unit, onPlay: () -> Unit, onNewBuild: () -> Unit, onOpenLogs: () -> Unit = {}, onOpenMods: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Обзор", "Моды", "Ресурспаки", "Настройки", "Логи")

    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        T("← Все сборки", 14, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onBack))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InstanceArtwork(build, Modifier.size(64.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(build.name, 28, C.text1, FontWeight.Bold, lineHeight = 34)
                    T("Minecraft ${build.version}, загрузчик ${build.loader}", 15, C.text2, lineHeight = 18)
                }
            }
            val actionEnabled = !busy && !running
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                     .background(if (actionEnabled && !build.installed) C.accentFill else C.fill)
                     .then(if (actionEnabled) Modifier.clickNoRipple(onPlay) else Modifier)
                    .padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                T(if (running) "Игра запущена" else if (build.installed) "Играть" else "Скачать", 15, if (actionEnabled && !build.installed) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 18)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            tabs.forEachIndexed { i, label ->
                val active = i == tab
                Column(
                    Modifier.clickNoRipple {
                        when (i) {
                            1 -> onOpenMods()
                            4 -> onOpenLogs()
                            else -> tab = i
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    T(
                        label,
                        15,
                        if (active) C.text1 else C.text2,
                        if (active) FontWeight.Medium else FontWeight.Normal,
                        lineHeight = 18,
                    )
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) C.accent else Color.Transparent),
                    )
                }
            }
        }
        if (tab == 0) {
            T("Создать новую сборку", 14, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onNewBuild))
        }
        error?.let { T(it, 14, C.danger, lineHeight = 20) }

        when (tab) {
            0 -> OverviewTab(build, if (automaticMemory) javaPlan?.heapGb ?: memoryGb else memoryGb, automaticMemory, onOpenMods)
            2 -> PacksTab(resourcePacks, resourcePacksLoading, resourcePacksError, onImportResourcePack, onToggleResourcePack, onOpenResourcePacks, onRefreshResourcePacks)
            else -> BuildSettingsTab(build, busy, memoryGb, automaticMemory, javaPlan, javaPlanLoading, javaOptimizationInfo, onMemoryChange, onAutomaticMemoryChange, onIcon, onRename, onDelete, onOpenFolder, onAutoUpdateMods, lastModUpdate, rollingBackMods, onRollbackMods)
        }
    }
}

@Composable
private fun ColumnScope.OverviewTab(build: LauncherBuild, memoryGb: Int, automaticMemory: Boolean, onOpenMods: () -> Unit) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
                .clip(RoundedCornerShape(20.dp))
                .background(C.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Сведения", 17, C.text1, FontWeight.Medium, lineHeight = 21)
            InfoLine("Версия", build.version)
            InfoLine("Загрузчик", build.loader)
            InfoLine("Память", "$memoryGb ГБ${if (automaticMemory) ", авто" else ""}")
            InfoLine("Последний запуск", build.lastPlayed)
            InfoLine("Время в игре", build.playtime.ifBlank { "Пока не считалось" })
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
                .clip(RoundedCornerShape(20.dp))
                .background(C.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Моды", 17, C.text1, FontWeight.Medium, lineHeight = 21)
            T("Моды ставятся, включаются и удаляются в разделе «Моды».", 15, C.text2, lineHeight = 20)
            T("Открыть моды сборки", 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple(onOpenMods))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        T(label, 15, C.text2, lineHeight = 18)
        T(value, 15, C.text1, lineHeight = 18)
    }
}

@Composable
private fun Toolbar(placeholder: String, action: String, query: String, onAction: () -> Unit, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBox(query, onQuery, placeholder, Modifier.width(320.dp), elevated = true, fill = C.surface)
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(C.accentFill)
                .clickNoRipple(onAction)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            T(action, 14, C.onAccent, FontWeight.Medium, lineHeight = 17)
        }
    }
}

@Composable
private fun ColumnScope.ListCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .softShadow(RoundedCornerShape(20.dp), elevation = 8.dp, color = Color(0x14000000))
            .clip(RoundedCornerShape(20.dp))
            .background(C.surface)
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun ColumnScope.PacksTab(packs: List<InstalledResourcePack>, loading: Boolean, error: String?, onImport: () -> Unit, onToggle: (InstalledResourcePack, Boolean) -> Unit, onOpenFolder: () -> Unit, onRefresh: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Toolbar("Поиск ресурспаков", "Добавить ресурспак", query, onAction = onImport) { query = it }
        ListCard {
            when {
                loading -> T("Читаем папку ресурспаков…", 14, C.text2, lineHeight = 20, modifier = Modifier.padding(24.dp))
                error != null -> T(error, 14, C.danger, lineHeight = 20, modifier = Modifier.padding(24.dp))
                packs.isEmpty() -> T("В этой сборке пока нет ZIP-ресурспаков.", 14, C.text2, lineHeight = 20, modifier = Modifier.padding(24.dp))
                else -> packs.filter { it.title.contains(query, true) || it.fileName.contains(query, true) }.forEach { pack ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(C.accentSoft), contentAlignment = Alignment.Center) { T("ZIP", 11, C.accent, FontWeight.Bold, lineHeight = 14) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(pack.title, 15, C.text1, FontWeight.Medium, lineHeight = 18, maxLines = 1)
                            T("${pack.description} (${pack.fileName})", 12, C.text2, lineHeight = 16, maxLines = 2)
                        }
                        com.cactuslauncher.ui.Switch(pack.enabled, horizontalInset = 4.dp) { onToggle(pack, it) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Открыть папку ресурспаков", 14, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onOpenFolder))
            T("Обновить", 14, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onRefresh))
        }
    }
}

@Composable
private fun ColumnScope.BuildSettingsTab(build: LauncherBuild, busy: Boolean, memoryGb: Int, automaticMemory: Boolean, javaPlan: JavaOptimizationPlan?, javaPlanLoading: Boolean, javaOptimizationInfo: JavaOptimizationInfo?, onMemoryChange: (Int) -> Unit, onAutomaticMemoryChange: (Boolean) -> Unit, onIcon: (String) -> Unit, onRename: (String) -> Unit, onDelete: (Boolean) -> Unit, onOpenFolder: () -> Unit, onAutoUpdateMods: (Boolean) -> Unit, lastModUpdate: ModUpdateBackup?, rollingBackMods: Boolean, onRollbackMods: () -> Unit) {
    val jvmArguments = remember(javaPlan) {
        javaPlan?.let { plan ->
            (listOf("-Xmx${plan.heapGb}G") + plan.arguments.filterNot {
                it.startsWith("-Xmx") || it.startsWith("-XX:MaxRAMPercentage=")
            }).distinctBy(::jvmArgumentKey)
        }.orEmpty()
    }
    var argumentIndex by remember(build.id) { mutableIntStateOf(0) }
    LaunchedEffect(jvmArguments) {
        argumentIndex = 0
        if (jvmArguments.size > 1) {
            while (isActive) {
                delay(2200)
                argumentIndex = (argumentIndex + 1) % jvmArguments.size
            }
        }
    }
    var name by remember(build.id, build.name) { mutableStateOf(build.name) }
    var deleting by remember(build.id) { mutableStateOf(false) }
    var deleteFiles by remember(build.id) { mutableStateOf(false) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("ОСНОВНОЕ", 13, C.text2, lineHeight = 16)
            SearchBox(name, { name = it }, "Название сборки", Modifier.fillMaxWidth(), height = 44.dp)
            T(if (busy) "Сохранение…" else "Сохранить название", 15, C.accent, lineHeight = 18,
                modifier = Modifier.clickNoRipple { if (!busy) onRename(name) })
            BuildIconPicker(build, busy, onIcon)
            SettingRow("Папка сборки") { T("Открыть", 15, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple { if (!busy) onOpenFolder() }) }
        }
        if (!build.loader.equals("Vanilla", ignoreCase = true)) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("МОДЫ", 13, C.text2, lineHeight = 16)
            SettingRow("Обновлять моды перед запуском") {
                com.cactuslauncher.ui.Switch(build.autoUpdateMods, horizontalInset = 4.dp) { if (!busy) onAutoUpdateMods(it) }
            }
            T("Перед каждым запуском лаунчер ставит совместимые обновления с Modrinth, старые файлы сохраняются для отката. Без интернета игра запустится как обычно.", 13, C.text2, lineHeight = 18)
            lastModUpdate?.let { backup ->
                val files = backup.replaced + backup.added
                SettingRow("Последнее обновление модов (${backup.createdShort}, $files ${filesWord(files)})") {
                    T(if (rollingBackMods) "Откатываем…" else "Откатить", 15, if (busy || rollingBackMods) C.text2 else C.accent, FontWeight.Medium, lineHeight = 18,
                        modifier = Modifier.clickNoRipple { if (!busy && !rollingBackMods) onRollbackMods() })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("ПРОИЗВОДИТЕЛЬНОСТЬ", 13, C.text2, lineHeight = 16)
            Column(
                Modifier
                    .fillMaxWidth()
                    .softShadow(RoundedCornerShape(16.dp), elevation = 8.dp, color = Color(0x14000000))
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                            T(if (automaticMemory) "AUTO" else "ВРУЧНУЮ", 11, C.accent, lineHeight = 13,
                                modifier = Modifier.clickNoRipple { onAutomaticMemoryChange(!automaticMemory) })
                        }
                        T("${javaPlan?.heapGb ?: memoryGb} ГБ", 15, C.text1, lineHeight = 18)
                    }
                }
                val shownMemoryGb = if (automaticMemory) javaPlan?.heapGb ?: memoryGb else memoryGb
                val maxMemoryGb = (javaOptimizationInfo?.maxMemoryGb ?: 16).coerceIn(2, 16)
                val memorySpan = (maxMemoryGb - 2).coerceAtLeast(1)
                MemorySlider(((shownMemoryGb - 2).toFloat() / memorySpan).coerceIn(0f, 1f)) {
                    onMemoryChange((2 + it * memorySpan).toInt().coerceIn(2, maxMemoryGb))
                }
                javaOptimizationInfo?.let { info -> MemoryWarning(shownMemoryGb, info) }
            }
            Row(
                Modifier.fillMaxWidth().softShadow(RoundedCornerShape(16.dp), elevation = 8.dp, color = Color(0x14000000))
                    .clip(RoundedCornerShape(16.dp)).background(C.surface).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("Аргументы JVM", 15, C.text1, lineHeight = 18)
                Box(Modifier.weight(1f).padding(start = 20.dp).height(22.dp).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                when {
                    javaPlanLoading -> T("Подбираю…", 13, C.text2, lineHeight = 17, maxLines = 1)
                    javaPlan == null -> T("Пока нет данных", 13, C.text2, lineHeight = 17, maxLines = 1)
                    jvmArguments.isEmpty() -> T("JVM по умолчанию", 13, C.text2, lineHeight = 17, maxLines = 1)
                    else -> AnimatedContent(
                        targetState = jvmArguments[argumentIndex.coerceIn(jvmArguments.indices)],
                        transitionSpec = {
                            (slideInVertically(tween(150, easing = CactusMotion.easeOut)) { it / 2 } +
                                fadeIn(tween(130, easing = CactusMotion.easeOut))) togetherWith
                                (slideOutVertically(tween(110, easing = CactusMotion.easeOut)) { -it / 2 } +
                                    fadeOut(tween(100, easing = CactusMotion.easeOut)))
                        },
                        label = "jvm-argument",
                    ) { argument ->
                        T(argument, 13, C.text2, lineHeight = 17, maxLines = 1)
                    }
                }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingRow("Удалить сборку", labelColor = C.danger) {
                T(if (busy) "Удаление…" else if (deleting) "Подтвердить" else "Удалить из списка", 15, C.danger, lineHeight = 18,
                    modifier = Modifier.clickNoRipple { if (!busy) { if (deleting) onDelete(deleteFiles) else deleting = true } })
            }
            if (deleting) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface)
                        .clickNoRipple { if (!busy) deleteFiles = !deleteFiles }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val checkboxFill by animateColorAsState(
                        if (deleteFiles) C.danger else Color.Transparent,
                        tween(160, easing = CactusMotion.easeOut),
                        label = "delete-files-checkbox-fill",
                    )
                    val checkboxBorder by animateColorAsState(
                        if (deleteFiles) C.danger else C.text2,
                        tween(160, easing = CactusMotion.easeOut),
                        label = "delete-files-checkbox-border",
                    )
                    val checkMarkProgress by animateFloatAsState(
                        if (deleteFiles) 1f else 0f,
                        tween(140, easing = CactusMotion.easeOut),
                        label = "delete-files-checkbox-mark",
                    )
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                            .background(checkboxFill)
                            .border(1.dp, checkboxBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        T(
                            "✓", 12, Color.White, FontWeight.Bold, lineHeight = 14,
                            modifier = Modifier.graphicsLayer {
                                alpha = checkMarkProgress
                                scaleX = 0.7f + 0.3f * checkMarkProgress
                                scaleY = 0.7f + 0.3f * checkMarkProgress
                            },
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        T("Удалить файлы сборки", 13, C.text1, FontWeight.Medium, lineHeight = 16)
                        T("Включая моды и миры", 12, C.text2, lineHeight = 15)
                    }
                }
                T("Отмена", 14, C.accent, lineHeight = 18, modifier = Modifier.clickNoRipple { deleting = false })
            }
        }
    }
}

private fun jvmArgumentKey(argument: String): String = when {
    argument.startsWith("-XX:") -> argument.substringBefore('=').replace(":+", ":").replace(":-", ":")
    argument.startsWith("-Xms") -> "-Xms"
    argument.startsWith("-Xmx") -> "-Xmx"
    argument.startsWith("-Xss") -> "-Xss"
    argument.startsWith("-D") -> argument.substringBefore('=')
    else -> argument
}

private fun filesWord(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> "файлов"
        last == 1 -> "файл"
        last in 2..4 -> "файла"
        else -> "файлов"
    }
}

@Composable
private fun SettingRow(
    label: String,
    labelColor: Color = C.text1,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .softShadow(RoundedCornerShape(16.dp), elevation = 8.dp, color = Color(0x14000000))
            .clip(RoundedCornerShape(16.dp))
            .background(C.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, 15, labelColor, lineHeight = 18)
        trailing()
    }
}

@Composable
private fun MemorySlider(value: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    val latestChange by rememberUpdatedState(onChange)
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { latestChange((it.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    latestChange((change.position.x / width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(C.track),
        )
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(C.accent),
        )
        SliderThumb(value, width)
    }
}
