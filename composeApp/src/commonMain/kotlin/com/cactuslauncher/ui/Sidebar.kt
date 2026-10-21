package com.cactuslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.cactuslauncher.game.TrackCommand
import com.cactuslauncher.model.TrackSnapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.Dest
import com.cactuslauncher.theme.C
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween

@Composable
fun Sidebar(
    current: Dest,
    onSelect: (Dest) -> Unit,
    onOpenSettings: () -> Unit,
    currentTrack: TrackSnapshot?,
    trackError: String?,
    onTrackCommand: (TrackCommand) -> Unit,
    playerExpanded: Boolean,
    onTogglePlayer: () -> Unit,
    profileName: String,
    onOpenProfiles: () -> Unit,
    profileAvatar: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val paneWidth by animateDpAsState(if (playerExpanded) 340.dp else 240.dp, tween(220, easing = CactusMotion.easeOut), label = "player-pane")
    Column(
        Modifier
            .width(paneWidth)
            .fillMaxHeight()
            .animateContentSize(tween(200, easing = CactusMotion.easeOut))
            .background(C.sidebar)
            .then(if (playerExpanded) Modifier.clickNoRipple(onTogglePlayer) else Modifier)
            .then(if (playerExpanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickNoRipple(onOpenProfiles).padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatar(profileName, profileAvatar, 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                T("$profileName ▾", 15, C.text1, lineHeight = 18, maxLines = 1)
                T("Офлайн-профиль", 12, C.text2, lineHeight = 15)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Dest.entries.forEach { dest ->
                NavItem(dest.label, dest == current) { onSelect(dest) }
            }
        }

        Spacer(if (playerExpanded) Modifier.height(0.dp) else Modifier.weight(1f))

        if (playerExpanded && currentTrack != null) {
            PlayerPopup(onTogglePlayer, currentTrack, trackError, onTrackCommand)
        } else if (currentTrack != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .softShadow(RoundedCornerShape(12.dp), elevation = 4.dp, color = Color(0x0F000000))
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.surface)
                    .clickNoRipple(onTogglePlayer)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val collapsedArtwork = currentTrack.artwork
                if (collapsedArtwork != null) {
                    Image(collapsedArtwork, contentDescription = "Обложка: ${currentTrack.title}", modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                } else Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(C.artPlaceholder), contentAlignment = Alignment.Center) {
                    T("♪", 18, C.accent, FontWeight.Medium, lineHeight = 20)
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    T(currentTrack.title, 13, C.text1, FontWeight.Medium, lineHeight = 16, maxLines = 1)
                    T(currentTrack.artist, 11, C.text2, FontWeight.Medium, lineHeight = 13, maxLines = 1)
                }
            }
        } else if (trackError != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                T("Не удалось определить трек", 12, C.text1, FontWeight.Medium, lineHeight = 15)
                T("Проверьте доступ лаунчера к Helium в настройках «Автоматизация» macOS.", 11, C.text2, lineHeight = 14)
            }
        }

        NavItem("Настройки", false, C.text2, onOpenSettings)
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    color: Color = C.text1,
    onClick: () -> Unit,
) {
    val fill by animateColorAsState(if (selected) C.fill else Color.Transparent, tween(140), label = "navigation-selection")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .clickNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        T(
            label,
            14,
            color,
            if (selected) FontWeight.Medium else FontWeight.Normal,
            lineHeight = 17,
        )
    }
}

@Composable
private fun PlayerPopup(
    onClose: () -> Unit,
    track: TrackSnapshot?,
    error: String?,
    onTrackCommand: (TrackCommand) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200, easing = CactusMotion.easeOut))
            .softShadow(RoundedCornerShape(16.dp), elevation = 16.dp, color = Color(0x26000000))
            .clip(RoundedCornerShape(16.dp))
            .background(C.elevated)
            .clickNoRipple {}
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Плеер", 13, C.text1, FontWeight.SemiBold, lineHeight = 16, modifier = Modifier.clickNoRipple(onClose))
            T("Треки", 12, C.text2, lineHeight = 15)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val artwork = track?.artwork
            if (artwork != null) {
                Image(artwork, contentDescription = "Обложка: ${track?.title ?: "трека"}", modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.artPlaceholder), contentAlignment = Alignment.Center) {
                    T("♪", 24, C.accent, FontWeight.Medium, lineHeight = 28)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                T(track?.title ?: "Нет активного трека", 14, C.text1, FontWeight.Medium, lineHeight = 17, maxLines = 2)
                T(track?.artist ?: "Откройте аудио в браузере или плеере", 12, C.text2, lineHeight = 15, maxLines = 1)
            }
        }
        val position = track?.positionSeconds ?: 0
        val duration = track?.durationSeconds ?: 0
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.fill)) {
            Box(Modifier.fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(formatTrackTime(position), 11, C.text2, lineHeight = 14)
            T(formatTrackTime(duration), 11, C.text2, lineHeight = 14)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackButton("|◀", "Предыдущий трек") { onTrackCommand(TrackCommand.PREVIOUS) }
            TrackButton(if (track?.isPlaying == true) "❚❚" else "▶", "Воспроизведение или пауза") { onTrackCommand(TrackCommand.PLAY_PAUSE) }
            TrackButton("▶|", "Следующий трек") { onTrackCommand(TrackCommand.NEXT) }
        }
        if (error != null) T(error, 12, C.text2, lineHeight = 15)
    }
}

private fun formatTrackTime(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun RowScope.TrackButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(C.fill)
            .clickNoRipple(onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { T(label, 13, C.text1, FontWeight.Medium, lineHeight = 16) }
}
