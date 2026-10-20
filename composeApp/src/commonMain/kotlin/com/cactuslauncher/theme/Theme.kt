package com.cactuslauncher.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colour tokens. Light values come from the Figma frame. [dark] is snapshot state,
 * so anything that reads a token recomposes when the theme changes.
 */
object C {
    var dark by mutableStateOf(false)
    private fun pick(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    val window get() = pick(0xFFF5F5F7, 0xFF121314)
    val sidebar get() = pick(0xFFFBFBFD, 0xFF17181A)
    /** Cards, dialogs and other raised panels. */
    val surface get() = pick(0xFFFFFFFF, 0xFF1C1D20)
    /** Popovers above a surface: dropdown menus, segment thumb. */
    val elevated get() = pick(0xFFFFFFFF, 0xFF2B2D31)
    val segmentThumb get() = pick(0xFFFFFFFF, 0xFF3A3D42)

    val text1 get() = pick(0xFF1D1D1F, 0xFFE8E9EB)
    val text2 get() = pick(0xFF6E6E73, 0xFFA0A4AA)
    val text3 get() = pick(0xFF8E8E93, 0xFF878B91)

    /** Accent for text, links, focus and progress. */
    val accent get() = pick(0xFF0071E3, 0xFF5AA2F7)
    /** Background of primary buttons; [onAccent] text on it passes WCAG AA in both themes. */
    val accentFill get() = pick(0xFF0071E3, 0xFF2A72D4)
    val onAccent get() = pick(0xFFFFFFFF, 0xFFFFFFFF)
    val accentSoft get() = pick(0xFFE5F1FC, 0xFF1D2F45)
    val fill get() = pick(0xFFE8E8ED, 0xFF303236)
    val input get() = pick(0xFFF5F5F7, 0xFF232528)
    val border get() = pick(0xFFD2D2D7, 0xFF3A3D42)

    val track get() = pick(0xFFD2D2D7, 0xFF3A3D42)
    val toggleOn get() = pick(0xFF34C759, 0xFF2FA84F)
    val toggleOff get() = pick(0xFFD2D2D7, 0xFF484B50)
    val danger get() = pick(0xFFFF3B30, 0xFFFF6B61)
    /** Destructive text links and error log level. */
    val dangerInk get() = pick(0xFFC62828, 0xFFFF8A80)
    val dangerSoft get() = pick(0xFFFDECEC, 0xFF3A1F1F)
    val success get() = pick(0xFF1F7A3A, 0xFF5CCB7A)
    val warning get() = pick(0xFF9A5A00, 0xFFE8A93E)
    val warningInk get() = pick(0xFFB25E00, 0xFFF0B34A)
    val warningSoft get() = pick(0xFFFFF6E5, 0xFF3A2E17)
    val scrim get() = pick(0x59000000, 0x99000000)
    /** Snackbar-style notices: inverted in light, a raised gray card in dark. */
    val toast get() = pick(0xFF1D1D1F, 0xFF2E3034)
    val onToast get() = pick(0xFFFFFFFF, 0xFFE8E9EB)
    val selectedGreen get() = pick(0xFFE8F3DD, 0xFF24331F)
    val artPlaceholder get() = pick(0xFF99CCFF, 0xFF1D2F45)

    val banner get() = pick(0xFF1D1D1F, 0xFF232528)
    val bannerEyebrow = Color(0xFF99CCFF)
    val bannerText = Color(0xFFEBEBF0)

    // news reader (green) theme
    val readerBg get() = pick(0xFFE8F3E6, 0xFF131C15)
    val readerBar get() = pick(0xFFF4FAF2, 0xFF18221A)
    val readerCard get() = pick(0xFFFFFFFF, 0xFF1D2A20)
    val readerInk get() = pick(0xFF1B2E1D, 0xFFDCE8D9)
    val readerGreen get() = pick(0xFF2E7D32, 0xFF7CC47F)
    val readerButton get() = pick(0xFF2E7D32, 0xFF2E7D32)
    val readerGreenTag get() = pick(0xFFCFE8C9, 0xFF24402A)
    val readerGreenTagText get() = pick(0xFF2E6B2A, 0xFFA6D9A2)
    val readerLede get() = pick(0xFF4A5D4C, 0xFFB3C4B2)
    val readerBody get() = pick(0xFF27362A, 0xFFD0DCCE)
    val readerMuted get() = pick(0xFF6F8A70, 0xFF8FA88F)

    // swatches used for placeholder icons
    val green = Color(0xFF7FB347)
    val blue = Color(0xFF5E7CE2)
    val sand = Color(0xFFC9A77C)
    val slate = Color(0xFF9AA5B1)
    val sage = Color(0xFFA3C48A)
    val clay = Color(0xFFD98E73)

    val shadowSoft = Color(0x14000000)
    val shadowStrong = Color(0x33000000)
}

fun Modifier.cardShadow(radius: Dp = 20.dp, elevation: Dp = 10.dp): Modifier =
    this.shadow(
        elevation = elevation,
        shape = RoundedCornerShape(radius),
        clip = false,
        ambientColor = C.shadowSoft,
        spotColor = C.shadowSoft,
    )

fun Modifier.dialogShadow(radius: Dp = 24.dp): Modifier =
    this.shadow(
        elevation = 32.dp,
        shape = RoundedCornerShape(radius),
        clip = false,
        ambientColor = C.shadowStrong,
        spotColor = C.shadowStrong,
    )

val Medium = FontWeight.Medium
val SemiBold = FontWeight.SemiBold
val Bold = FontWeight.Bold
val Regular = FontWeight.Normal
