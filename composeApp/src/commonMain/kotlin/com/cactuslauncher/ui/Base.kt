package com.cactuslauncher.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.cactuslauncher.resources.Res
import com.cactuslauncher.resources.mounts_of_mayhem
import com.cactuslauncher.resources.minecraft_release
import com.cactuslauncher.resources.minecraft_snapshot
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cactuslauncher.theme.C
import com.cactuslauncher.theme.LocalAppFont

@Composable
fun Modifier.clickNoRipple(onClick: () -> Unit): Modifier =
    clickNoRipple(enabled = true, onClick = onClick)

@Composable
fun Modifier.clickNoRipple(enabled: Boolean, onClick: () -> Unit): Modifier {
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val source = remember { MutableInteractionSource() }
    val interactions = rememberControlInteractions(source, enabled)
    val hovered = interactions.value.hovered
    val pressed = interactions.value.pressed
    val opacity = animateFloatAsState(
        if (!enabled) 1f else if (pressed) 0.88f else if (hovered) 0.97f else 1f,
        tween(if (pressed) CactusMotion.press else CactusMotion.hover, easing = CactusMotion.easeOut), label = "interaction-opacity",
    )
    return this
        .graphicsLayer { alpha = if (enabled) opacity.value else 1f }
        .controlFocusRing(interactions, enabled)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .focusProperties { canFocus = enabled && keyboardMode }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
private fun Modifier.controlFocusRing(interactions: State<ControlInteractions>, enabled: Boolean): Modifier {
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focusColor = C.accent
    return drawWithCache {
        val corners = CornerRadius(if (size.width == size.height) size.minDimension / 2f else 8.dp.toPx())
        val stroke = Stroke(1.dp.toPx())
        onDrawWithContent {
            drawContent()
            if (enabled && keyboardMode && interactions.value.focused) drawRoundRect(focusColor, cornerRadius = corners, style = stroke)
        }
    }
}

@Composable
fun Modifier.buttonClickNoRipple(onClick: () -> Unit): Modifier =
    buttonClickNoRipple(enabled = true, onClick = onClick)

@Composable
fun Modifier.buttonClickNoRipple(
    enabled: Boolean,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactions = rememberControlInteractions(source, enabled)
    val hovered = interactions.value.hovered
    val pressed = interactions.value.pressed
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale = rememberTactileScale(
        if (enabled && pressed) CactusMotion.buttonScale else 1f,
        "button-press-scale", engaged = enabled && pressed,
    )
    val opacity = animateFloatAsState(
        if (!enabled) 1f else if (pressed) 0.94f else if (hovered) 0.97f else 1f,
        tween(if (pressed) CactusMotion.press else CactusMotion.hover, easing = CactusMotion.easeOut), label = "button-tone",
    )
    return Modifier
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .focusProperties { canFocus = enabled && keyboardMode }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .controlFocusRing(interactions, enabled)
        .graphicsLayer {
            scaleX = if (enabled) scale.value else 1f
            scaleY = scaleX
            alpha = if (enabled) opacity.value else 1f
        }
        .then(this)
}

fun Color.hoverTint(): Color {
    val light = (0.299f * red + 0.587f * green + 0.114f * blue) > 0.55f
    return lerp(this, if (light) Color.Black else Color.White, 0.08f)
}

@Composable
fun Switch(on: Boolean, horizontalInset: Dp = 3.dp, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val interactions = rememberControlInteractions(source, enabled)
    val hovered = interactions.value.hovered
    val pressed = interactions.value.pressed
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val travel = 44.dp - horizontalInset * 2 - 20.dp
    val knob = animateDpAsState(
        if (on) travel else 0.dp,
        tween(CactusMotion.normal, easing = CactusMotion.easeOut), label = "switch-thumb",
    )
    val thumbScale = rememberTactileScale(
        if (!enabled) 1f else if (pressed) 0.94f else if (hovered) 1.025f else 1f,
        "switch-contact", engaged = enabled && pressed,
    )
    val track = animateColorAsState(if (on) C.toggleOn else C.toggleOff,
        tween(CactusMotion.hover, easing = CactusMotion.easeOut), label = "switch-track")
    Box(
        Modifier
            .width(44.dp)
            .height(26.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(13.dp))
            .drawWithCache { onDrawBehind { drawRect(track.value) } }
            .controlFocusRing(interactions, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .focusProperties { canFocus = enabled && keyboardMode }
            .toggleable(value = on, enabled = enabled, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = horizontalInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .graphicsLayer {
                    translationX = knob.value.toPx()
                    scaleX = if (enabled) thumbScale.value else 1f
                    scaleY = scaleX
                }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    fill: Color = C.input,
    height: Dp = 40.dp,
    radius: Dp = 12.dp,
    obscureText: Boolean = false,
) {
    val shape = RoundedCornerShape(radius)
    var focused by remember { mutableStateOf(false) }
    val focusStroke = animateColorAsState(if (focused) C.accent.copy(alpha = 0.65f) else Color.Transparent, tween(140), label = "field-focus")
    Box(
        modifier
            .height(height)
            .then(if (elevated) Modifier.softShadow(shape, elevation = 2.dp, color = Color(0x14000000)) else Modifier)
            .clip(shape)
            .background(fill)
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val stroke = Stroke(2.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawOutline(outline, focusStroke.value, style = stroke)
                }
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) T(placeholder, 15, C.text3, lineHeight = 18)
        BasicTextField(
            value = value,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (obscureText) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 18.sp,
                color = C.text1,
                fontFamily = LocalAppFont.current,
            ),
            cursorBrush = SolidColor(C.accent),
        )
    }
}

@Composable
fun BannerArtwork(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.mounts_of_mayhem),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

@Composable
fun BuildArtwork(label: String, color: Color, modifier: Modifier = Modifier) {
    BuildArtwork(label, modifier)
}

@Composable
fun BuildArtwork(label: String, modifier: Modifier = Modifier) {
    Image(painterResource(if (label.contains("snapshot", true) || label.equals("dirt", true)) Res.drawable.minecraft_snapshot else Res.drawable.minecraft_release),
        contentDescription = "Иконка Minecraft: $label", modifier = modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
}

@Composable
fun PixelIcon(res: DrawableResource, contentDescription: String?, modifier: Modifier = Modifier) {
    Image(
        bitmap = imageResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

@Composable
fun MemoryControl(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, maxGb: Int = 16, enabled: Boolean = true) {
    val maximum = maxGb.coerceIn(2, 16)
    CactusSlider(
        value = value.toFloat(), onChange = { onChange(it.roundToInt()) },
        modifier = modifier.semantics {
            contentDescription = "Объём памяти"
            stateDescription = "${value.coerceIn(2, maximum)} ГБ"
        },
        valueRange = 2f..maximum.toFloat(), steps = (maximum - 3).coerceAtLeast(0), enabled = enabled, discrete = true,
    )
}

@Composable
fun CactusSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    discrete: Boolean = steps > 0,
) {
    require(valueRange.start.isFinite() && valueRange.endInclusive.isFinite() && valueRange.endInclusive >= valueRange.start)
    require(steps >= 0)
    val source = remember { MutableInteractionSource() }
    var width by remember { mutableFloatStateOf(1f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(LocalDensity.current) { 16.dp.toPx() }
    val span = valueRange.endInclusive.toDouble() - valueRange.start.toDouble()
    val interactive = enabled && span > 0.0
    val current = sliderValue(value, valueRange, steps, discrete)
    val fraction = if (span > 0.0) ((current.toDouble() - valueRange.start) / span).toFloat() else 0f
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val latestChange by rememberUpdatedState(onChange)
    val latestUpdate by rememberUpdatedState<(Float) -> Boolean> { candidate ->
        val next = sliderValue(candidate, valueRange, steps, discrete)
        if (interactive && next != current) { latestChange(next); true } else false
    }
    val updatePosition by rememberUpdatedState<(Float) -> Unit> { x ->
        val position = ((x - thumbPx / 2f) / (width - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        latestUpdate((valueRange.start.toDouble() + position * span).toFloat())
    }
    Box(
        modifier.fillMaxWidth().height(24.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(current, valueRange, if (discrete) steps else 0)
                if (!interactive) disabled()
                setProgress { latestUpdate(it) }
            }
            .pointerHoverIcon(if (interactive) PointerIcon.Hand else PointerIcon.Default)
            .hoverable(source, enabled = interactive)
            .onKeyEvent { event ->
                if (!interactive || event.type != KeyEventType.KeyDown) false else {
                    val increment = if (discrete) span / (steps.toDouble() + 1.0) else span / 100.0
                    val target = when (event.key) {
                        Key.DirectionRight, Key.DirectionUp -> (current.toDouble() + increment).coerceAtMost(valueRange.endInclusive.toDouble()).toFloat()
                        Key.DirectionLeft, Key.DirectionDown -> (current.toDouble() - increment).coerceAtLeast(valueRange.start.toDouble()).toFloat()
                        Key.MoveHome -> valueRange.start
                        Key.MoveEnd -> valueRange.endInclusive
                        else -> null
                    }
                    if (target == null) false else { latestUpdate(target); true }
                }
            }
            .focusProperties { canFocus = interactive && keyboardMode }
            .focusable(enabled = interactive, interactionSource = source)
            .pointerInput(interactive, source) {
                if (interactive) detectTapGestures(
                    onPress = { position ->
                        val press = PressInteraction.Press(position)
                        source.emit(press)
                        var released = false
                        try { released = tryAwaitRelease() } finally {
                            source.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        }
                    },
                    onTap = { updatePosition(it.x) },
                )
            }
            .draggable(
                state = rememberDraggableState { delta -> dragX += delta; updatePosition(dragX) },
                orientation = Orientation.Horizontal, enabled = interactive, interactionSource = source,
                onDragStarted = { dragX = it.x; updatePosition(dragX) },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val drawnFraction = rememberUpdatedState(fraction)
        SliderTrack(drawnFraction)
        SliderThumb(drawnFraction, rememberUpdatedState(width), source, interactive)
    }
}

@Composable
private fun SliderTrack(fraction: State<Float>) {
    Box(Modifier.fillMaxWidth().height(4.dp).drawWithCache {
        val corners = CornerRadius(2.dp.toPx())
        onDrawBehind {
            drawRoundRect(C.track, cornerRadius = corners)
            val fillWidth = size.width * fraction.value
            if (fillWidth > 0f) drawRoundRect(
                C.accent, size = androidx.compose.ui.geometry.Size(fillWidth, size.height), cornerRadius = corners,
            )
        }
    })
}

internal fun sliderValue(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, discrete: Boolean = steps > 0): Float {
    val clamped = (if (value.isFinite()) value else range.start).coerceIn(range.start, range.endInclusive)
    val span = range.endInclusive.toDouble() - range.start.toDouble()
    if (span <= 0.0 || !discrete) return clamped
    val intervals = steps.toDouble() + 1.0
    val index = ((clamped.toDouble() - range.start) / span * intervals).roundToLong()
    return (range.start.toDouble() + index * span / intervals).toFloat()
        .coerceIn(range.start, range.endInclusive)
}

@Composable
fun SliderThumb(fraction: Float, trackWidthPx: Float, interactionSource: MutableInteractionSource? = null, enabled: Boolean = true) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    SliderThumb(rememberUpdatedState(fraction), rememberUpdatedState(trackWidthPx), source, enabled)
}

@Composable
private fun SliderThumb(fraction: State<Float>, trackWidthPx: State<Float>, source: MutableInteractionSource, enabled: Boolean) {
    val interactions by rememberControlInteractions(source, enabled)
    val pressed = interactions.pressed
    val dragged = interactions.dragged
    val hovered = interactions.hovered
    val focused = interactions.focused
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale = rememberTactileScale(
        if (!enabled) 1f else if (pressed || dragged) 1.12f else if (hovered || keyboardFocus) 1.06f else 1f,
        "slider-contact", engaged = enabled && (pressed || dragged),
    )
    Box(
        Modifier
            .size(16.dp)
            .graphicsLayer {
                translationX = (trackWidthPx.value - 16.dp.toPx()).coerceAtLeast(0f) * fraction.value.coerceIn(0f, 1f)
                scaleX = if (enabled) scale.value else 1f
                scaleY = scaleX
            }
            .softShadow(CircleShape, elevation = 2.dp, color = Color(0x33000000))
            .clip(CircleShape)
            .background(Color.White)
            .border(if (keyboardFocus && enabled) 3.dp else 2.dp, C.accent, CircleShape),
    )
}

fun Modifier.softShadow(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 8.dp,
    color: Color = Color(0x14000000),
): Modifier = this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)

@Composable
fun T(
    text: String,
    size: Int,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Int = (size * 1.25f).toInt().coerceAtLeast(size + 2),
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    colorProducer: androidx.compose.ui.graphics.ColorProducer? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        color = colorProducer,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFamily = LocalAppFont.current,
            lineHeight = lineHeight.sp,
        ),
    )
}
