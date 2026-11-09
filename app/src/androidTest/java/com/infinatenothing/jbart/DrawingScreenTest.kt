package com.infinatenothing.jbart

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.Tool
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun exportDialogShowsFormatQualityAndTransparent() {
        rule.onNodeWithTag("tool_export").performScrollTo().performClick()
        rule.onNodeWithTag("export_dialog").assertIsDisplayed()
        rule.onNodeWithTag("export_format_png").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("export_format_jpeg").assertIsDisplayed()
        rule.onNodeWithTag("export_format_bmp").assertIsDisplayed()
        rule.onNodeWithTag("export_transparent").assertIsDisplayed()
        rule.onNodeWithTag("export_save").assertIsDisplayed()
        rule.onNodeWithTag("export_share").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("export_quality_slider").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("export_format_jpeg").performClick()
        rule.onNodeWithTag("export_quality_slider").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("export_transparent").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("export_format_bmp").performClick()
        assertTrue(rule.onAllNodesWithTag("export_quality_slider").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("export_transparent").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("export_format_png").performClick()
        rule.onNodeWithTag("export_transparent").assertIsDisplayed()
        rule.onNodeWithTag("export_cancel").performClick()
        assertTrue(rule.onAllNodesWithTag("export_dialog").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun toolbarIsDisplayed() {
        rule.onNodeWithTag("toolbar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Brush").assertIsDisplayed()
    }

    @Test
    fun brushSettingsPopupShowsSizeAndOpacitySliders() {
        rule.onNodeWithTag("tool_brush").performClick()
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
    fun shapeOptionsShowForShapeAndHideForBrush() {
        assertTrue(rule.onAllNodesWithTag("shape_kind_rectangle").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("tool_shape").performScrollTo().performClick()
        rule.onNodeWithTag("shape_kind_rectangle").performScrollTo().assertIsDisplayed().assertIsSelected()
        rule.onNodeWithContentDescription("Rectangle").assertIsDisplayed()
        rule.onNodeWithTag("shape_kind_ellipse").performScrollTo().assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithContentDescription("Ellipse").assertIsDisplayed()
        rule.onNodeWithTag("shape_kind_triangle").performScrollTo().assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithContentDescription("Triangle").assertIsDisplayed()
        rule.onNodeWithTag("shape_kind_arrow").performScrollTo().assertIsDisplayed().assertIsNotSelected()
        rule.onNodeWithContentDescription("Arrow").assertIsDisplayed()
        rule.onNodeWithTag("shape_constrain_toggle").performScrollTo().assertIsDisplayed().assertIsOff()
        rule.onNodeWithContentDescription("Constrain proportions").assertIsDisplayed()
        rule.onNodeWithTag("shape_fill_toggle").performScrollTo().assertIsDisplayed().assertIsOff()
        rule.onNodeWithContentDescription("Filled").assertIsDisplayed()

        rule.onNodeWithTag("shape_kind_ellipse").performScrollTo().performClick()
        rule.onNodeWithTag("shape_kind_ellipse").assertIsSelected()
        rule.onNodeWithTag("shape_kind_rectangle").assertIsNotSelected()
        rule.onNodeWithTag("shape_constrain_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("shape_constrain_toggle").assertIsOn()
        rule.onNodeWithTag("shape_fill_toggle").performScrollTo().performClick()
        rule.onNodeWithTag("shape_fill_toggle").assertIsOn()

        rule.onNodeWithTag("tool_brush").performScrollTo().performClick()
        assertTrue(rule.onAllNodesWithTag("shape_kind_rectangle").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("shape_constrain_toggle").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("shape_fill_toggle").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun pendingRectangleMovesSurvivesRecreateThenCommitsOnTapOutside() {
        rule.onNodeWithTag("tool_shape").performScrollTo().performClick()
        rule.waitForIdle()
        val model = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        if (model.pending != null) {
            rule.runOnUiThread { model.undo() }
            rule.waitForIdle()
        }
        val before = model.document.objects.size
        val view = drawingView()
        assertTrue(view.width > 200 && view.height > 200)
        drag(view, view.width * 0.18f, view.height * 0.22f, view.width * 0.62f, view.height * 0.58f)
        val drawn = model.pending as DrawObject.Shape
        assertEquals(before, model.document.objects.size)
        val startX = drawn.x1
        val centerX = (drawn.x1 + drawn.x2) / 2f
        val centerY = (drawn.y1 + drawn.y2) / 2f
        drag(view, centerX, centerY, centerX + view.width * 0.06f, centerY + view.height * 0.04f)
        val moved = model.pending as DrawObject.Shape
        assertTrue(moved.x1 != startX || moved.y1 != drawn.y1)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        val restored = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        val still = restored.pending as DrawObject.Shape
        assertEquals(moved.x1, still.x1, 0.5f)
        assertEquals(moved.y1, still.y1, 0.5f)
        assertEquals(moved.rotationDeg, still.rotationDeg, 0f)
        assertEquals(before, restored.document.objects.size)
        val again = drawingView()
        tap(again, again.width * 0.93f, again.height * 0.07f)
        assertNull(restored.pending)
        assertEquals(before + 1, restored.document.objects.size)
    }

    @Test
    fun pendingLineEndpointDragSurvivesRecreateThenCommitsOnTapOutside() {
        rule.onNodeWithTag("tool_line").performScrollTo().performClick()
        rule.onNodeWithTag("line_snap_toggle").performClick()
        rule.waitForIdle()
        val model = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        if (model.pending != null) {
            rule.runOnUiThread { model.undo() }
            rule.waitForIdle()
        }
        val before = model.document.objects.size
        val view = drawingView()
        assertTrue(view.width > 200 && view.height > 200)
        val y = view.height * 0.45f
        drag(view, view.width * 0.25f, y, view.width * 0.55f, y)
        val drawn = model.pending as DrawObject.Line
        assertEquals(before, model.document.objects.size)
        drag(view, drawn.x2, drawn.y2, drawn.x2 + view.width * 0.08f, drawn.y2 + view.height * 0.06f)
        val edited = model.pending as DrawObject.Line
        assertEquals(drawn.x1, edited.x1, 1f)
        assertEquals(drawn.y1, edited.y1, 1f)
        assertTrue(edited.x2 != drawn.x2 || edited.y2 != drawn.y2)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        val restored = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        val still = restored.pending as DrawObject.Line
        assertEquals(edited.x1, still.x1, 0.5f)
        assertEquals(edited.y1, still.y1, 0.5f)
        assertEquals(edited.x2, still.x2, 0.5f)
        assertEquals(edited.y2, still.y2, 0.5f)
        assertEquals(before, restored.document.objects.size)
        val again = drawingView()
        tap(again, again.width * 0.93f, again.height * 0.07f)
        assertNull(restored.pending)
        assertEquals(before + 1, restored.document.objects.size)
        val committed = restored.document.objects.last() as DrawObject.Line
        assertEquals(still.x2, committed.x2, 0.5f)
        assertEquals(still.y2, committed.y2, 0.5f)
    }

    @Test
    fun colorPickerSetsStrokeAndFillAndEyedropperSamplesTheStroke() {
        val model = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        rule.runOnUiThread {
            if (model.pending != null) model.undo()
            model.selectTool(Tool.BRUSH)
            model.updateBrush(BrushSettings(sizeDp = 28f, opacity = 1f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tool_color").performScrollTo().performClick()
        assertEquals(Tool.BRUSH, model.tool)
        val red = ColorSettings.PALETTE.indexOf(ColorSettings.RED)
        rule.onNodeWithTag("color_swatch_$red").performScrollTo().performClick()
        Espresso.pressBack()
        rule.waitForIdle()
        val view = drawingView()
        val y = view.height * 0.42f
        drag(view, view.width * 0.28f, y, view.width * 0.72f, y)
        val stroke = model.document.objects.last() as DrawObject.Stroke
        assertEquals(ColorSettings.RED, stroke.color)

        rule.onNodeWithTag("tool_color").performScrollTo().performClick()
        rule.onNodeWithTag("color_target_fill").performScrollTo().performClick()
        rule.onNodeWithTag("color_target_fill").assertIsSelected()
        val blue = ColorSettings.PALETTE.indexOf(ColorSettings.BLUE)
        rule.onNodeWithTag("color_swatch_$blue").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ColorSettings.BLUE, model.colorSettings.fill)

        val before = model.document.objects.size
        val undo = model.history.undoCount
        rule.onNodeWithTag("color_eyedropper").performScrollTo().performClick()
        rule.waitForIdle()
        val again = drawingView()
        tap(again, again.width * 0.5f, y)
        rule.waitForIdle()
        val after = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        assertEquals(ColorSettings.RED, after.colorSettings.stroke)
        assertEquals(before, after.document.objects.size)
        assertEquals(undo, after.history.undoCount)
    }

    @Test
    fun fillTapAddsOneFillAndUndoRemovesIt() {
        rule.onNodeWithTag("tool_fill").performScrollTo().performClick()
        rule.waitForIdle()
        val model = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        if (model.pending != null) {
            rule.runOnUiThread { model.undo() }
            rule.waitForIdle()
        }
        val before = model.document.objects.size
        val view = drawingView()
        tap(view, view.width * 0.5f, view.height * 0.5f)
        rule.waitUntil(5_000) {
            !model.fillBusy && model.document.objects.size == before + 1
        }
        assertTrue(rule.onAllNodesWithTag("fill_busy").fetchSemanticsNodes().isEmpty())
        assertTrue(model.document.objects.last() is DrawObject.Fill)
        rule.onNodeWithTag("tool_undo").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(before, model.document.objects.size)
    }

    @Test
    fun zoomButtonsStepTheScaleAndShowThePercent() {
        val model = ViewModelProvider(rule.activity)[DrawingViewModel::class.java]
        rule.onNodeWithTag("zoom_label").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom in").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1.25f, model.viewScale, 0.001f)
        rule.onNodeWithContentDescription("Zoom out").performClick()
        rule.waitForIdle()
        assertEquals(1f, model.viewScale, 0.001f)
    }

    @Test
    fun eraserSettingsPopupShowsSizeSliderOnly() {
        rule.onNodeWithTag("tool_eraser").performClick()
        rule.onNodeWithTag("tool_eraser").performClick()
        rule.onNodeWithTag("eraser_size_slider").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("brush_opacity_slider").fetchSemanticsNodes().isEmpty())
    }

    private fun drawingView(): DrawingView {
        lateinit var found: DrawingView
        rule.runOnUiThread {
            found = findDrawing(rule.activity.window.decorView) ?: error("drawing view missing")
        }
        return found
    }

    private fun findDrawing(view: View): DrawingView? {
        if (view is DrawingView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findDrawing(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun drag(view: DrawingView, x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        rule.runOnUiThread {
            dispatch(view, MotionEvent.ACTION_DOWN, x1, y1, down, down)
            dispatch(view, MotionEvent.ACTION_MOVE, (x1 + x2) / 2f, (y1 + y2) / 2f, down, down + 16)
            dispatch(view, MotionEvent.ACTION_MOVE, x2, y2, down, down + 32)
            dispatch(view, MotionEvent.ACTION_UP, x2, y2, down, down + 48)
        }
        rule.waitForIdle()
    }

    private fun tap(view: DrawingView, x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        rule.runOnUiThread {
            dispatch(view, MotionEvent.ACTION_DOWN, x, y, down, down)
            dispatch(view, MotionEvent.ACTION_UP, x, y, down, down + 16)
        }
        rule.waitForIdle()
    }

    private fun dispatch(view: DrawingView, action: Int, x: Float, y: Float, down: Long, time: Long) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        val event = MotionEvent.obtain(
            down,
            time,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }
}
