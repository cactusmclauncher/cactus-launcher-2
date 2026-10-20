package com.cactuslauncher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.theme.C

/** A single sliding selection surface; interrupted taps retarget the current animation. */
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
        val position by animateDpAsState(
            (segmentWidth + 4.dp) * selected,
            tween(200, easing = CactusMotion.easeOut), label = "segment-selection",
        )
        Box(
            Modifier.offset(x = position).width(segmentWidth).fillMaxHeight()
                .softShadow(RoundedCornerShape(9.dp), elevation = 2.dp)
                .clip(RoundedCornerShape(9.dp)).background(C.segmentThumb),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val color by animateColorAsState(
                    if (index == selected) C.text1 else C.text2,
                    tween(140), label = "segment-label",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                        .clickNoRipple { onSelect(index) }, contentAlignment = Alignment.Center,
                ) {
                    T(label, 14, color, FontWeight.Medium, lineHeight = 17, maxLines = 1)
                }
            }
        }
    }
}
