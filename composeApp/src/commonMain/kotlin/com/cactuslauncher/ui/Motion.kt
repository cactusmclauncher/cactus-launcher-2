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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

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
    const val buttonScale = 0.98f
    const val press = 70
    const val hover = 140
    const val tactileDamping = 1f
    const val tactileStiffness = 650f
}

@Composable
internal fun tactileMotionEnabled(): Boolean =
    (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f

@Composable
internal fun rememberTactileScale(target: Float, label: String, engaged: Boolean = false): State<Float> =
    animateFloatAsState(
        if (tactileMotionEnabled()) target else 1f,
        if (engaged) tween(CactusMotion.press, easing = CactusMotion.easeOut)
        else spring(dampingRatio = CactusMotion.tactileDamping, stiffness = CactusMotion.tactileStiffness),
        label = label,
    )

internal data class ControlInteractions(
    val pressed: Boolean = false,
    val hovered: Boolean = false,
    val focused: Boolean = false,
    val dragged: Boolean = false,
)

@Composable
internal fun rememberControlInteractions(
    source: MutableInteractionSource,
    enabled: Boolean,
): State<ControlInteractions> {
    val state = remember(source, enabled) { mutableStateOf(ControlInteractions()) }
    LaunchedEffect(source, enabled) {
        if (!enabled) return@LaunchedEffect
        val presses = mutableSetOf<PressInteraction.Press>()
        val hovers = mutableSetOf<HoverInteraction.Enter>()
        val focuses = mutableSetOf<FocusInteraction.Focus>()
        val drags = mutableSetOf<DragInteraction.Start>()
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> presses.add(interaction)
                is PressInteraction.Release -> presses.remove(interaction.press)
                is PressInteraction.Cancel -> presses.remove(interaction.press)
                is HoverInteraction.Enter -> hovers.add(interaction)
                is HoverInteraction.Exit -> hovers.remove(interaction.enter)
                is FocusInteraction.Focus -> focuses.add(interaction)
                is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                is DragInteraction.Start -> drags.add(interaction)
                is DragInteraction.Stop -> drags.remove(interaction.start)
                is DragInteraction.Cancel -> drags.remove(interaction.start)
            }
            state.value = ControlInteractions(
                pressed = presses.isNotEmpty(), hovered = hovers.isNotEmpty(),
                focused = focuses.isNotEmpty(), dragged = drags.isNotEmpty(),
            )
        }
    }
    return state
}

@Composable
fun MotionOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier,
        enter = fadeIn(tween(140, easing = CactusMotion.easeOut)),
        exit = fadeOut(tween(120, easing = CactusMotion.easeOut)),
    ) { content() }
}
