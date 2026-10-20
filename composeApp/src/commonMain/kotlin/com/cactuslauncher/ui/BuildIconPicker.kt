package com.cactuslauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.LauncherBuild
import com.cactuslauncher.theme.C

private val iconOptions = listOf("grass" to "Релиз", "snapshot" to "Снапшот", "custom" to "Своя")

@Composable
fun InstanceArtwork(build: LauncherBuild, modifier: Modifier = Modifier) {
    IconSwatch(build.icon, build, modifier)
}

@Composable
private fun IconSwatch(icon: String, build: LauncherBuild, modifier: Modifier) {
    val image = build.customIcon
    if (icon == "custom" && image != null) Image(image, "Иконка сборки", modifier.clip(RoundedCornerShape(6.dp)))
    else BuildArtwork(if (icon == "snapshot" || icon == "dirt") "snapshot" else build.version, modifier)
}

@Composable
fun BuildIconPicker(build: LauncherBuild, busy: Boolean, onSelect: (String) -> Unit) {
    var open by remember(build.id) { mutableStateOf(false) }
    Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Иконка сборки", 13, C.text1, FontWeight.Medium, lineHeight = 16)
        Box {
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.window)
                .border(1.dp, if (open) C.accent else C.fill, RoundedCornerShape(12.dp))
                .clickNoRipple { if (!busy) open = !open }.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconSwatch(build.icon, build, Modifier.size(28.dp))
                T(iconOptions.find { it.first == build.icon }?.second ?: "Релиз", 15, C.text1, lineHeight = 18, modifier = Modifier.weight(1f))
                T(if (open) "▴" else "▾", 12, C.text2, lineHeight = 15)
            }
            MotionDropdown(open, onDismiss = { open = false }) {
                Column(Modifier.width(320.dp).padding(top = 48.dp).softShadow(RoundedCornerShape(14.dp), 12.dp, Color(0x26000000))
                    .clip(RoundedCornerShape(14.dp)).background(C.elevated).border(1.dp, C.fill, RoundedCornerShape(14.dp)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    iconOptions.forEach { (id, label) ->
                        val selected = build.icon == id
                        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (selected) C.selectedGreen else Color.Transparent)
                            .clickNoRipple { if (!busy) { open = false; onSelect(id) } }.padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconSwatch(id, build, Modifier.size(24.dp))
                            T(label, 14, C.text1, if (selected) FontWeight.Medium else FontWeight.Normal, lineHeight = 17, modifier = Modifier.weight(1f))
                            if (selected) T("✓", 13, C.accent, FontWeight.Bold, lineHeight = 16)
                        }
                    }
                }
            }
        }
    }
}
