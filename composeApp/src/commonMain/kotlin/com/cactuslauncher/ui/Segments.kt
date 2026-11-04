package com.cactuslauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.theme.C

@Composable
fun AnimatedSegments(
    labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, fill: Color = C.input,
) {
    require(labels.isNotEmpty() && selected in labels.indices)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(fill).padding(4.dp),
    ) {
        val segmentWidth = (maxWidth - 4.dp * (labels.size - 1)) / labels.size
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        val position = animateFloatAsState(
            selected.toFloat(),
            tween(CactusMotion.normal, easing = CactusMotion.easeOut), label = "segment-selection",
        )
        Box(
            Modifier.width(segmentWidth).fillMaxHeight()
                .graphicsLayer { translationX = (segmentWidth + 4.dp).toPx() * position.value * direction }
                .softShadow(RoundedCornerShape(9.dp), elevation = 2.dp)
                .clip(RoundedCornerShape(9.dp)).background(C.segmentThumb),
        )
        Row(Modifier.fillMaxSize().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val color = animateColorAsState(
                    if (index == selected) C.text1 else C.text2,
                    tween(CactusMotion.hover, easing = CactusMotion.easeOut), label = "segment-label",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                        .semantics { this.selected = index == selected; role = Role.Tab }
                        .clickNoRipple { onSelect(index) }, contentAlignment = Alignment.Center,
                ) {
                    T(label, 14, C.text1, FontWeight.Medium, lineHeight = 17, maxLines = 1, colorProducer = { color.value })
                }
            }
        }
    }
}
