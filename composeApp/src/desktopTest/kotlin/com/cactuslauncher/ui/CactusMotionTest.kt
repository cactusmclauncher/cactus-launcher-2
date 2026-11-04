package com.cactuslauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class CactusMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pressIsQuickAndReleaseSettlesWithoutOvershoot() {
        val pressed = mutableStateOf(false)
        lateinit var scale: State<Float>
        var motionEnabled = false
        compose.setContent {
            motionEnabled = tactileMotionEnabled()
            scale = rememberTactileScale(if (pressed.value) CactusMotion.buttonScale else 1f, "test", pressed.value)
            Box(Modifier.width(40.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value })
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(motionEnabled, "Motion must be enabled for timing coverage") }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { pressed.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle { assertEquals(CactusMotion.buttonScale, scale.value, 0.001f) }
        compose.runOnIdle { pressed.value = false }
        var previous = CactusMotion.buttonScale
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue(scale.value >= previous - 0.0001f)
                assertTrue(scale.value <= 1.0001f)
                previous = scale.value
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1f, scale.value, 0.001f) }
    }

    @Test
    fun segmentsRetargetKeepTabSemanticsAndDoNotRemeasureEachFrame() {
        val selected = mutableStateOf(0)
        var measures = 0
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            Box(Modifier.width(300.dp)) {
                AnimatedSegments(listOf("Первый", "Второй", "Третий"), selected.value, { selected.value = it },
                    Modifier.layout { measurable, constraints ->
                        measures++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Третий").performClick().assertIsSelected()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Второй").performClick().assertIsSelected()
        compose.onNodeWithText("Третий").assertIsNotSelected()
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        val baseline = measures
        repeat(10) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertEquals(baseline, measures)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("Первый").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithText("Первый").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Первый").assertIsSelected()
    }

    @Test
    fun switchRemainsKeyboardOperableAndDisabledIgnoresClicks() {
        val on = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            Switch(on.value, enabled = enabled.value) { on.value = it }
        }
        val node = compose.onNode(isToggleable())
        node.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        node.performKeyInput { pressKey(Key.Spacebar) }
        node.assertIsOff()
        compose.runOnIdle { enabled.value = false }
        node.assertIsNotEnabled().performTouchInput { click() }
        node.assertIsOff()
    }
}
