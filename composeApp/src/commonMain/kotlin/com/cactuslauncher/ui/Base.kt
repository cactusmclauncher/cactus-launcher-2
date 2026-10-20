package com.cactuslauncher.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
fun Modifier.clickNoRipple(onClick: () -> Unit): Modifier {
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val opacity by animateFloatAsState(if (pressed) 0.88f else if (hovered) 0.97f else 1f, tween(CactusMotion.quick, easing = CactusMotion.easeOut), label = "interaction-opacity")
    return this
        .graphicsLayer { alpha = opacity }
        .pointerHoverIcon(PointerIcon.Hand)
        .focusProperties { canFocus = keyboardMode }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

fun Color.hoverTint(): Color {
    val light = (0.299f * red + 0.587f * green + 0.114f * blue) > 0.55f
    return lerp(this, if (light) Color.Black else Color.White, 0.08f)
}

@Composable
fun Switch(on: Boolean, horizontalInset: Dp = 3.dp, onToggle: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val travel = 44.dp - horizontalInset * 2 - 20.dp
    val knob by animateDpAsState(if (on) travel else 0.dp, tween(180, easing = FastOutSlowInEasing), label = "switch-thumb")
    val track by animateColorAsState(if (on) C.toggleOn else C.toggleOff, tween(180), label = "switch-track")
    Box(
        Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(track)
            .pointerHoverIcon(PointerIcon.Hand)
            .toggleable(value = on, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = horizontalInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(20.dp)
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
    val focusStroke by animateColorAsState(if (focused) C.accent.copy(alpha = 0.65f) else Color.Transparent, tween(140), label = "field-focus")
    Box(
        modifier
            .height(height)
            .then(if (elevated) Modifier.softShadow(shape, elevation = 2.dp, color = Color(0x14000000)) else Modifier)
            .clip(shape)
            .background(fill)
            .border(1.dp, focusStroke, shape)
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
fun MemoryControl(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, maxGb: Int = 16) {
    var width by remember { mutableFloatStateOf(1f) }
    val latestChange by rememberUpdatedState(onChange)
    val latestMax by rememberUpdatedState(maxGb.coerceIn(2, 16))
    val span = (maxGb.coerceIn(2, 16) - 2).coerceAtLeast(1)
    fun update(x: Float) {
        val range = (latestMax - 2).coerceAtLeast(1)
        latestChange((2 + (x / width).coerceIn(0f, 1f) * range).toInt().coerceIn(2, latestMax))
    }
    Box(
        modifier.fillMaxWidth().height(24.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { update(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); update(change.position.x) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.track))
        Box(Modifier.fillMaxWidth(((value - 2) / span.toFloat()).coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.accent))
        SliderThumb(((value - 2) / span.toFloat()).coerceIn(0f, 1f), width)
    }
}

/** Round slider handle; place inside a CenterStart-aligned Box at least 16 dp tall. */
@Composable
fun SliderThumb(fraction: Float, trackWidthPx: Float) {
    Box(
        Modifier
            .offset { IntOffset(((trackWidthPx - 16.dp.toPx()) * fraction.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0), 0) }
            .size(16.dp)
            .softShadow(CircleShape, elevation = 2.dp, color = Color(0x33000000))
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, C.accent, CircleShape),
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
) {
    BasicText(
        text = text,
        modifier = modifier,
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
