package com.cactuslauncher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.model.InstalledMod
import com.cactuslauncher.model.ModrinthProject
import com.cactuslauncher.model.ModSecurityReport
import com.cactuslauncher.theme.C
import com.cactuslauncher.ui.AnimatedSegments
import com.cactuslauncher.ui.InstanceArtwork
import com.cactuslauncher.ui.MotionDropdown
import com.cactuslauncher.ui.SearchBox
import com.cactuslauncher.ui.T
import com.cactuslauncher.ui.clickNoRipple
import com.cactuslauncher.ui.softShadow

@Composable
fun ModsCatalogScreen(
    builds: List<LauncherBuild>,
    selectedBuildId: String?,
    installedMods: List<InstalledMod>,
    projects: List<ModrinthProject>,
    query: String,
    actionId: String?,
    loadingCatalog: Boolean,
    icons: Map<String, ImageBitmap>,
    translatedDescriptions: Map<String, String>,
    failedTranslationIds: Set<String>,
    showTranslatedDescriptions: Boolean,
    hasMore: Boolean,
    loadingMore: Boolean,
    loadMoreError: String?,
    installedProjectIds: Set<String>,
    securityEnabled: Boolean,
    securityReportsByProject: Map<String, ModSecurityReport>,
    scanningSecurityProjects: Set<String>,
    queuedProjectIds: List<String>,
    activeInstallProjectId: String?,
    installErrors: Map<String, String>,
    installQueueCount: Int,
    onCatalogSelected: (Boolean) -> Unit,
    loadingBuilds: Boolean,
    loadingMods: Boolean,
    error: String?,
    onSelectBuild: (LauncherBuild) -> Unit,
    onQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onInstall: (ModrinthProject) -> Unit,
    onRemove: (InstalledMod) -> Unit,
    onToggleMod: (InstalledMod, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenBuilds: () -> Unit,
) {
    var segment by remember { mutableIntStateOf(0) }
    SideEffect { onCatalogSelected(segment == 1) }
    val selectedBuild = builds.firstOrNull { it.id == selectedBuildId }
    val visibleMods = installedMods.filter { it.title.contains(query.trim(), true) || it.fileName.contains(query.trim(), true) }
    val visibleProjects = projects
    val catalogListState = rememberLazyListState()
    LaunchedEffect(query, selectedBuildId, segment) {
        catalogListState.scrollToItem(0)
    }
    LaunchedEffect(catalogListState, segment, hasMore, loadingMore, loadMoreError) {
        snapshotFlow {
            val layout = catalogListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (segment == 1 && nearEnd && hasMore && !loadingMore && loadMoreError == null) onLoadMore()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T("Моды", 32, C.text1, FontWeight.Bold, lineHeight = 39)
                T("Установленные файлы хранятся отдельно в каждой сборке", 14, C.text2, lineHeight = 20)
            }
            SearchBox(query, onQueryChange, "Поиск модов", Modifier.width(280.dp), elevated = true, fill = C.surface)
        }

        AnimatedSegments(listOf("Установленные", "Каталог"), segment, {
            segment = it
            onCatalogSelected(it == 1)
        }, fill = C.fill)

        BuildPicker(
            builds = builds,
            selected = selectedBuild,
            loading = loadingBuilds,
            onSelect = onSelectBuild,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = catalogListState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (segment == 1 && installQueueCount > 0) {
                item(key = "queue") { T("Очередь загрузки: ${modsCount(installQueueCount)}", 13, C.text2, lineHeight = 16) }
            }
            when {
                loadingBuilds -> item(key = "state") { EmptyModsState("Загружаем сборки…", "Список модов появится после загрузки сборок.") }
                builds.isEmpty() -> item(key = "state") { EmptyModsState("Сборок пока нет", "Создайте сборку, чтобы её моды хранились отдельно.", "Перейти к сборкам", onOpenBuilds) }
                selectedBuild == null -> item(key = "state") { EmptyModsState("Выберите сборку", "Моды будут показаны только для выбранной сборки.") }
                segment == 1 && selectedBuild.loader.equals("Vanilla", ignoreCase = true) -> item(key = "state") { EmptyModsState("Нужен загрузчик модов", "Это сборка Vanilla. Для модов создайте сборку с Fabric или Forge.") }
                segment == 1 && error != null -> item(key = "state") { EmptyModsState(error, "Проверьте подключение к Modrinth и повторите поиск.", "Повторить", onRefresh) }
                segment == 1 && loadingCatalog -> item(key = "state") { EmptyModsState("Загружаем Modrinth…", "Ищем моды для Minecraft ${selectedBuild.version}, ${selectedBuild.loader}.") }
                segment == 1 && visibleProjects.isEmpty() -> item(key = "state") { EmptyModsState("Ничего не найдено", "Измените поисковый запрос или проверьте каталог позже.") }
                segment == 1 -> {
                    item(key = "catalog-header") {
                        T("${if (query.isBlank()) "Популярное" else "Результаты поиска"}: ${modsCount(visibleProjects.size)} для Minecraft ${selectedBuild.version} (${selectedBuild.loader})", 13, C.text2, lineHeight = 16)
                    }
                    items(visibleProjects, key = { it.id }) { project ->
                        val queuePosition = queuedProjectIds.indexOf(project.id).takeIf { it >= 0 }?.plus(1)
                        ModrinthProjectRow(
                            project, icons[project.id], translatedDescriptions[project.id], showTranslatedDescriptions,
                            project.id in failedTranslationIds,
                            installed = project.id in installedProjectIds,
                            downloading = project.id == activeInstallProjectId,
                            queuePosition = queuePosition,
                            installError = installErrors[project.id],
                            securityReport = securityReportsByProject[project.id],
                            scanningSecurity = project.id in scanningSecurityProjects,
                            securityEnabled = securityEnabled,
                            busy = actionId == project.id, anyBusy = actionId != null,
                            onInstall = { onInstall(project) },
                        )
                    }
                    if (loadingMore) item(key = "loading-more") { T("Загружаем следующие моды…", 13, C.text2, lineHeight = 17) }
                    if (loadMoreError != null) item(key = "load-more-error") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            T("Не удалось загрузить продолжение ленты: $loadMoreError", 13, C.text2, lineHeight = 18)
                            T("Повторить", 13, C.accent, FontWeight.Medium, lineHeight = 17, modifier = Modifier.clickNoRipple(onLoadMore))
                        }
                    }
                }
                loadingMods -> item(key = "state") { EmptyModsState("Читаем папку модов", "Загружаем список .jar для сборки «${selectedBuild.name}»…") }
                error != null -> item(key = "state") { EmptyModsState(error, "Проверьте папку модов или повторите чтение.", "Повторить", onRefresh) }
                visibleMods.isEmpty() -> item(key = "state") {
                    EmptyModsState(
                        if (query.isBlank()) "В этой сборке пока нет модов" else "Моды не найдены",
                        if (query.isBlank()) "В папке «${selectedBuild.name}/mods» пока нет файлов .jar." else "Измените запрос или очистите поиск.",
                        if (query.isBlank()) "Открыть папку модов" else "Очистить поиск",
                        if (query.isBlank()) onOpenFolder else { { onQueryChange("") } },
                    )
                }
                else -> {
                    item(key = "installed-header") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("${modsCount(visibleMods.size)} в сборке ${selectedBuild.name}", 13, C.text2, lineHeight = 16, modifier = Modifier.weight(1f))
                            T("Обновить", 13, C.accent, FontWeight.Medium, lineHeight = 16, modifier = Modifier.clickNoRipple(onRefresh))
                        }
                    }
                    items(visibleMods, key = { it.fileName }) { mod ->
                        InstalledModRow(
                            mod, actionId == mod.fileName, actionId != null,
                            onRemove = { onRemove(mod) },
                            onToggle = { onToggleMod(mod, it) },
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(selectedBuild?.let { "Папка: ${it.name}/mods" } ?: "Сначала выберите сборку", 13, C.text2, lineHeight = 17)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Обновить", 14, if (selectedBuild == null) C.text2 else C.accent, FontWeight.Medium, lineHeight = 17,
                    modifier = if (selectedBuild == null) Modifier else Modifier.clickNoRipple(onRefresh))
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(if (selectedBuild == null) C.fill else C.accentFill)
                        .then(if (selectedBuild == null) Modifier else Modifier.clickNoRipple(onOpenFolder))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    T("Открыть папку модов", 15, if (selectedBuild == null) C.text2 else C.onAccent, FontWeight.Medium, lineHeight = 18)
                }
            }
        }
    }
}

@Composable
private fun BuildPicker(
    builds: List<LauncherBuild>,
    selected: LauncherBuild?,
    loading: Boolean,
    onSelect: (LauncherBuild) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().softShadow(RoundedCornerShape(12.dp), elevation = 2.dp, color = Color(0x14000000))
                .clip(RoundedCornerShape(12.dp)).background(C.surface)
                .clickNoRipple { if (!loading && builds.isNotEmpty()) open = !open }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            selected?.let { InstanceArtwork(it, Modifier.size(36.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("Сборка", 11, C.text2, FontWeight.Medium, lineHeight = 13)
                T(
                    when {
                        loading -> "Загружаем сборки…"
                        selected != null -> "${selected.name}, Minecraft ${selected.version}, ${selected.loader}"
                        else -> "Сборка не выбрана"
                    },
                    14, if (selected == null) C.text2 else C.text1, lineHeight = 17, maxLines = 1,
                )
            }
            T(if (open) "▴" else "▾", 13, C.text2, lineHeight = 16)
        }
        MotionDropdown(open, onDismiss = { open = false }) {
            Column(
                Modifier.width(520.dp).heightIn(max = 420.dp).padding(top = 60.dp).verticalScroll(rememberScrollState())
                    .softShadow(RoundedCornerShape(14.dp), elevation = 12.dp, color = Color(0x26000000))
                    .clip(RoundedCornerShape(14.dp)).background(C.elevated).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                builds.forEach { build ->
                    val isSelected = build.id == selected?.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) C.fill else Color.Transparent)
                            .clickNoRipple { open = false; onSelect(build) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InstanceArtwork(build, Modifier.size(36.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            T(build.name, 14, C.text1, if (isSelected) FontWeight.Medium else FontWeight.Normal, lineHeight = 17, maxLines = 1)
                            T("Minecraft ${build.version}, ${build.loader}", 12, C.text2, lineHeight = 15, maxLines = 1)
                        }
                        if (isSelected) T("✓", 13, C.accent, FontWeight.Medium, lineHeight = 16)
                    }
                }
            }
        }
    }
}

/** "1 мод", "3 мода", "11 модов": Russian plural for mod counts. */
private fun modsCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "модов"
        count % 10 == 1 -> "мод"
        count % 10 in 2..4 -> "мода"
        else -> "модов"
    }
    return "$count $word"
}

@Composable
private fun InstalledModRow(
    mod: InstalledMod,
    busy: Boolean,
    anyBusy: Boolean,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().softShadow(RoundedCornerShape(16.dp), elevation = 4.dp, color = Color(0x10000000))
            .clip(RoundedCornerShape(16.dp)).background(C.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.accentSoft), contentAlignment = Alignment.Center) {
            T("JAR", 11, C.accent, FontWeight.Bold, lineHeight = 14)
        }
        var confirmingRemove by remember(mod.fileName) { mutableStateOf(false) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(mod.title, 15, C.text1, FontWeight.Medium, lineHeight = 18, maxLines = 1)
            if (confirmingRemove && !busy) T("Удалить мод «${mod.title}»? Файл будет удалён из папки сборки.", 12, C.text1, lineHeight = 15, maxLines = 2)
            else T(if (mod.version.isBlank()) mod.fileName else "${mod.version} (${mod.fileName})", 12, C.text2, lineHeight = 15, maxLines = 1)
        }
        if (confirmingRemove && !busy) {
            T("Отмена", 12, C.text2, lineHeight = 15, modifier = Modifier.clickNoRipple { confirmingRemove = false })
            T("Удалить мод", 12, if (anyBusy) C.text2 else C.dangerInk, FontWeight.Medium, lineHeight = 15,
                modifier = if (anyBusy) Modifier else Modifier.clickNoRipple { confirmingRemove = false; onRemove() })
        } else T(if (busy) "Удаляем…" else "Удалить", 12, if (anyBusy) C.text2 else C.dangerInk, FontWeight.Medium, lineHeight = 15,
            modifier = if (anyBusy) Modifier else Modifier.clickNoRipple { confirmingRemove = true })
        com.cactuslauncher.ui.Switch(mod.enabled, horizontalInset = 4.dp) { if (!anyBusy) onToggle(it) }
    }
}

@Composable
private fun ModrinthProjectRow(
    project: ModrinthProject,
    icon: ImageBitmap?,
    translatedDescription: String?,
    showTranslatedDescription: Boolean,
    translationFailed: Boolean,
    installed: Boolean,
    downloading: Boolean,
    queuePosition: Int?,
    installError: String?,
    securityReport: ModSecurityReport?,
    scanningSecurity: Boolean,
    securityEnabled: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().softShadow(RoundedCornerShape(16.dp), elevation = 4.dp, color = Color(0x10000000))
            .clip(RoundedCornerShape(16.dp)).background(C.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = "Иконка мода ${project.title}", modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.accentSoft), contentAlignment = Alignment.Center) {
                T("M", 18, C.accent, FontWeight.Bold, lineHeight = 22)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            T(project.title, 15, C.text1, FontWeight.Medium, lineHeight = 18, maxLines = 1)
            T(
                (if (showTranslatedDescription) translatedDescription else null)?.ifBlank { project.description }
                    ?: project.description.ifBlank { "Автор: ${project.author}" },
                12, C.text2, lineHeight = 15, maxLines = 3,
            )
            if (showTranslatedDescription && translationFailed) T("Сервисы перевода не ответили. Проверьте подключение.", 11, C.text2, lineHeight = 14, maxLines = 1)
            installError?.let { T(it, 11, C.danger, lineHeight = 14, maxLines = 2) }
            if (securityEnabled && (installed || scanningSecurity || securityReport != null)) SecurityAssessment(securityReport, scanningSecurity)
            T("${project.author}, ${project.downloads} загрузок${if (showTranslatedDescription && translatedDescription != null) " (Google Translate)" else ""}", 11, C.text2, lineHeight = 14, maxLines = 1)
        }
        val actionLabel = when {
            installed -> "Установлено"
            downloading -> "Скачиваем…"
            queuePosition != null -> "В очереди, позиция $queuePosition"
            busy -> "Устанавливаем…"
            installError != null -> "Повторить"
            else -> "Установить"
        }
        val canInstall = !installed && !downloading && queuePosition == null && !busy
        T(actionLabel, 13, if (canInstall) C.accent else C.text2, FontWeight.Medium, lineHeight = 16,
            modifier = if (canInstall) Modifier.clickNoRipple(onInstall) else Modifier)
    }
}

@Composable
private fun SecurityAssessment(report: ModSecurityReport?, scanning: Boolean) {
    val color: Color
    val message: String
    when {
        scanning -> { color = C.text2; message = "Проверяем JAR…" }
        report == null -> { color = C.text2; message = "Проверка ещё не выполнена" }
        report.level == "clear" -> {
            color = C.success
            message = "Подозрительных признаков не найдено, классов проверено: ${report.scannedClasses}"
        }
        report.level == "notice" -> {
            color = C.warning
            message = "Предупреждение: ${report.findings.joinToString(" ")}"
        }
        report.level == "warning" -> {
            color = C.danger
            message = "Подозрительные признаки: ${report.findings.joinToString(" ")}"
        }
        else -> {
            color = C.text2
            message = "Проверка не выполнена: ${report.findings.joinToString(" ")}"
        }
    }
    T(message, 11, color, lineHeight = 14, maxLines = 2)
}

@Composable
private fun EmptyModsState(title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(title, 17, C.text1, FontWeight.Medium, lineHeight = 21)
        T(description, 14, C.text2, lineHeight = 20)
        if (action != null && onAction != null) {
            T(action, 14, C.accent, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple(onAction))
        }
    }
}
