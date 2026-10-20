package com.cactuslauncher.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cactuslauncher.resources.Res
import com.cactuslauncher.resources.inter_bold
import com.cactuslauncher.resources.inter_medium
import com.cactuslauncher.resources.inter_regular
import com.cactuslauncher.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * Inter (a free SF-alike) bundled with the app so text renders identically on
 * macOS / Windows / Linux. Font resources load asynchronously, so the family is
 * built in a composable and published through a CompositionLocal.
 */
val LocalAppFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

@Composable
fun AppFontProvider(content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    CompositionLocalProvider(LocalAppFont provides family, content = content)
}
