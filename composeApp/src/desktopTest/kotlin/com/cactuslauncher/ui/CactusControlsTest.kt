package com.cactuslauncher.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class CactusControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun quantizationClampsAndRespectsContinuousOverride() {
        assertEquals(2f, sliderValue(-10f, 2f..16f, 13))
        assertEquals(16f, sliderValue(100f, 2f..16f, 13))
        assertEquals(7f, sliderValue(6.6f, 2f..16f, 13))
        assertEquals(6.6f, sliderValue(6.6f, 2f..16f, 13, discrete = false))
        assertEquals(0.37f, sliderValue(0.37f, 0f..1f, 0))
    }

    @Test
    fun degenerateAndTwoEndpointRangesStayConsistent() {
        assertEquals(2f, sliderValue(3f, 2f..2f, 0, discrete = true))
        assertEquals(2f, sliderValue(2.4f, 2f..3f, 0, discrete = true))
        assertEquals(3f, sliderValue(2.6f, 2f..3f, 0, discrete = true))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(2f, sliderValue(invalid, 2f..16f, 13))
        }
    }

    @Test
    fun largeStepCountsAndFiniteExtremeEndpointsDoNotOverflow() {
        assertEquals(1f, sliderValue(1f, 0f..1f, Int.MAX_VALUE))
        assertEquals(0.5f, sliderValue(0.5f, 0f..1f, Int.MAX_VALUE))
        val range = -Float.MAX_VALUE..Float.MAX_VALUE
        assertEquals(0f, sliderValue(0f, range, 1))
        assertEquals(Float.MAX_VALUE, sliderValue(Float.MAX_VALUE, range, 1))
        assertEquals(-Float.MAX_VALUE, sliderValue(-Float.MAX_VALUE, range, 1))
    }

    @Test
    fun interactionsMatchTheirStartsAndResetWhenDisabledOrReplaced() {
        val source = mutableStateOf(MutableInteractionSource())
        val enabled = mutableStateOf(true)
        lateinit var state: State<ControlInteractions>
        compose.setContent { state = rememberControlInteractions(source.value, enabled.value) }
        fun emit(interaction: androidx.compose.foundation.interaction.Interaction) {
            compose.runOnIdle { assertTrue(source.value.tryEmit(interaction)) }
            compose.waitForIdle()
        }
        val first = PressInteraction.Press(Offset.Zero)
        val second = PressInteraction.Press(Offset(1f, 1f))
        val hover = HoverInteraction.Enter()
        val focus = FocusInteraction.Focus()
        val drag = DragInteraction.Start()
        emit(first)
        emit(second)
        emit(PressInteraction.Release(first))
        compose.runOnIdle { assertTrue(state.value.pressed) }
        emit(PressInteraction.Cancel(second))
        emit(hover)
        emit(focus)
        emit(drag)
        compose.runOnIdle { assertEquals(ControlInteractions(hovered = true, focused = true, dragged = true), state.value) }
        emit(HoverInteraction.Exit(hover))
        emit(FocusInteraction.Unfocus(focus))
        emit(DragInteraction.Cancel(drag))
        compose.runOnIdle { assertEquals(ControlInteractions(), state.value) }
        emit(first)
        compose.runOnIdle { enabled.value = false }
        compose.runOnIdle { assertEquals(ControlInteractions(), state.value); enabled.value = true }
        compose.runOnIdle { assertEquals(ControlInteractions(), state.value) }
        emit(first)
        compose.runOnIdle { source.value = MutableInteractionSource() }
        compose.runOnIdle { assertEquals(ControlInteractions(), state.value) }
    }

    @Test
    fun sliderAccessibilityKeyboardAndDisabledInputUseSameValues() {
        val value = mutableStateOf(2f)
        val enabled = mutableStateOf(true)
        lateinit var inputMode: androidx.compose.ui.input.InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            Box(Modifier.width(200.dp)) {
                CactusSlider(value.value, { value.value = it }, Modifier.testTag("slider"), 2f..4f, steps = 1, enabled = enabled.value)
            }
        }
        val node = compose.onNodeWithTag("slider")
        node.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(2.6f)) }
        compose.runOnIdle { assertEquals(3f, value.value) }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        node.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(4f, value.value) }
        node.performKeyInput { pressKey(Key.MoveHome) }
        compose.runOnIdle { assertEquals(2f, value.value); enabled.value = false }
        node.assertIsNotEnabled()
        node.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(4f)) }
        node.performTouchInput { click(centerRight) }
        compose.runOnIdle { assertEquals(2f, value.value) }
    }

    @Test
    fun continuousSliderReportsContinuousSemanticsAndTapDragReachEndpoints() {
        val value = mutableStateOf(0f)
        compose.setContent {
            Box(Modifier.width(200.dp)) {
                CactusSlider(value.value, { value.value = it }, Modifier.testTag("slider"), steps = 9, discrete = false)
            }
        }
        val node = compose.onNodeWithTag("slider")
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, 0f..1f, 0)))
        node.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.37f)) }
        compose.runOnIdle { assertEquals(0.37f, value.value) }
        node.performTouchInput { click(centerRight) }
        compose.runOnIdle { assertEquals(1f, value.value) }
        node.performTouchInput { swipe(centerRight, centerLeft, durationMillis = 300) }
        compose.runOnIdle { assertEquals(0f, value.value) }
    }
}
