package com.infinatenothing.jbart

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolbarIsDisplayed() {
        rule.onNodeWithTag("toolbar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Brush").assertIsDisplayed()
    }

    @Test
    fun brushSettingsPopupShowsSizeAndOpacitySliders() {
        rule.onNodeWithTag("tool_brush").performClick()
        rule.onNodeWithTag("brush_settings").assertIsDisplayed()
        rule.onNodeWithTag("brush_size_slider").assertIsDisplayed()
        rule.onNodeWithTag("brush_opacity_slider").assertIsDisplayed()
    }

    @Test
    fun lineSnapToggleShowsOnlyForLineAndToggles() {
        assertTrue(rule.onAllNodesWithTag("line_snap_toggle").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("tool_line").performClick()
        rule.onNodeWithTag("line_snap_toggle").assertIsDisplayed().assertIsOn()
        rule.onNodeWithContentDescription("Snap angle").assertIsDisplayed()
        rule.onNodeWithTag("line_snap_toggle").performClick()
        rule.onNodeWithTag("line_snap_toggle").assertIsOff()
        rule.onNodeWithTag("line_snap_toggle").performClick()
        rule.onNodeWithTag("line_snap_toggle").assertIsOn()
        rule.onNodeWithTag("tool_brush").performClick()
        assertTrue(rule.onAllNodesWithTag("line_snap_toggle").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun eraserSettingsPopupShowsSizeSliderOnly() {
        rule.onNodeWithTag("tool_eraser").performClick()
        rule.onNodeWithTag("tool_eraser").performClick()
        rule.onNodeWithTag("eraser_size_slider").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("brush_opacity_slider").fetchSemanticsNodes().isEmpty())
    }
}
