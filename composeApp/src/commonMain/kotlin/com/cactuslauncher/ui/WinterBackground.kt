package com.cactuslauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun WinterBackground(modifier: Modifier = Modifier, dark: Boolean, animate: Boolean = true) {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val motionScale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow { motionScale?.scaleFactor ?: 1f }.collectLatest { scale ->
            if (scale <= 0f) {
                tick.intValue = 0
                return@collectLatest
            }
            val start = withFrameNanos { it }
            val initialTick = tick.intValue
            while (currentCoroutineContext().isActive) {
                withFrameNanos { now ->
                    tick.intValue = ((initialTick + (now - start) / (80_000_000.0 * scale)) % WINTER_LOOP_TICKS).toInt()
                }
                delay(80)
            }
        }
    }
    val palette = remember(dark) {
        if (dark) listOf(
            Color(0xFF202A34), Color(0xFF303E4B), Color(0xFF1D2A2D),
            Color(0xFF25313A), Color(0xFF34434E), Color(0xFF1D292C), Color(0xFF233F50),
        ) else listOf(
            Color(0xFFE3EBF0), Color(0xFFF1F5F7), Color(0xFFCFDDE0),
            Color(0xFFEAF0F3), Color(0xFFF7F9FA), Color(0xFFC5D5D8), Color(0xFFD3E3ED),
        )
    }
    val snow = if (dark) Color(0x557D99AB) else Color(0x55788F9E)
    Canvas(modifier.drawWithCache {
        val pixel = 4.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val columns = ceil(size.width / pixel).toInt().coerceAtLeast(1)
        val rows = ceil(size.height / pixel).toInt().coerceAtLeast(1)
        val landscape = winterLandscape(columns, rows)
        val flakes = winterFlakes(columns, rows)
        val cell = Size(pixel, pixel)
        onDrawBehind {
            landscape.forEach { block ->
                drawRect(
                    palette[block.ink],
                    Offset(block.x * pixel, block.y * pixel),
                    Size(block.width * pixel, block.height * pixel),
                )
            }
            val frame = tick.intValue
            flakes.forEach { flake ->
                val x = winterSnowX(flake, frame, columns) * pixel
                val y = winterSnowY(flake, frame, rows) * pixel
                drawRect(snow, Offset(x, y), cell, alpha = if (flake.near) 0.75f else 0.4f)
            }
        }
    }) {}
}
