package com.cactuslauncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun MotionDropdown(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    val offset = with(LocalDensity.current) { 6.dp.roundToPx() }
    state.targetState = visible
    if (state.currentState || state.targetState || !state.isIdle) {
        Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = visible)) {
            AnimatedVisibility(
                visibleState = state,
                enter = fadeIn(tween(140, easing = CactusMotion.easeOut)) +
                    scaleIn(tween(180, easing = CactusMotion.easeOut), initialScale = 0.985f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                    slideInVertically(tween(180, easing = CactusMotion.easeOut)) { -offset },
                exit = fadeOut(tween(110, easing = CactusMotion.easeOut)) +
                    scaleOut(tween(110, easing = CactusMotion.easeOut), targetScale = 0.985f, transformOrigin = TransformOrigin(0.5f, 0f)),
            ) { content() }
        }
    }
}

object CactusMotion {
    val easeOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val quick = 110
    const val normal = 200
}

/** Keep modal content alive during dismissal so exit animations can finish. */
@Composable
fun MotionOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier,
        enter = fadeIn(tween(140, easing = CactusMotion.easeOut)),
        exit = fadeOut(tween(120, easing = CactusMotion.easeOut)),
    ) { content() }
}
