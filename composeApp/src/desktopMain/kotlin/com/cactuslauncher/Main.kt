package com.cactuslauncher

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cactuslauncher.game.GameServiceImpl
import com.cactuslauncher.theme.C

fun main() = application {
    val state = rememberWindowState(size = DpSize(1200.dp, 760.dp))
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Cactus Launcher 2.0",
    ) {
        val game = remember { GameServiceImpl() }
        val dark = C.dark
        // macOS: match the title bar to the launcher theme.
        LaunchedEffect(dark) {
            if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
                window.rootPane.putClientProperty(
                    "apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
                )
            }
        }
        // Light keeps the default selection colours.
        val selection = if (dark) TextSelectionColors(C.accent, C.accent.copy(alpha = 0.35f)) else LocalTextSelectionColors.current
        CompositionLocalProvider(LocalTextSelectionColors provides selection) {
            App(game, onGhostMode = ::exitApplication)
        }
    }
}
