package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.GestureMode
import com.infinatenothing.jbart.canvas.InputRouting
import com.infinatenothing.jbart.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

class InputRoutingTest {
    @Test
    fun eraserToolTypeErasesRegardlessOfSelection() {
        for (selected in Tool.entries) {
            assertEquals(
                Tool.ERASER,
                InputRouting.toolForEvent(selected, InputRouting.TOOL_TYPE_ERASER, 0),
            )
        }
    }

    @Test
    fun stylusButtonErasesWithoutDependingOnSelection() {
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.BRUSH, InputRouting.TOOL_TYPE_STYLUS, InputRouting.BUTTON_STYLUS_PRIMARY),
        )
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.LINE, InputRouting.TOOL_TYPE_STYLUS, InputRouting.BUTTON_STYLUS_SECONDARY),
        )
    }

    @Test
    fun brushSelectionRoutesStylusAndFingerToBrush() {
        assertEquals(Tool.BRUSH, InputRouting.toolForEvent(Tool.BRUSH, InputRouting.TOOL_TYPE_STYLUS, 0))
        assertEquals(Tool.BRUSH, InputRouting.toolForEvent(Tool.BRUSH, InputRouting.TOOL_TYPE_FINGER, 0))
        assertEquals(Tool.BRUSH, InputRouting.toolForEvent(Tool.BRUSH, InputRouting.TOOL_TYPE_MOUSE, 0))
    }

    @Test
    fun eraserSelectionRoutesStylusAndFingerToEraser() {
        assertEquals(Tool.ERASER, InputRouting.toolForEvent(Tool.ERASER, InputRouting.TOOL_TYPE_STYLUS, 0))
        assertEquals(Tool.ERASER, InputRouting.toolForEvent(Tool.ERASER, InputRouting.TOOL_TYPE_FINGER, 0))
    }

    @Test
    fun lineSelectionRoutesStylusFingerAndMouseToLine() {
        assertEquals(Tool.LINE, InputRouting.toolForEvent(Tool.LINE, InputRouting.TOOL_TYPE_STYLUS, 0))
        assertEquals(Tool.LINE, InputRouting.toolForEvent(Tool.LINE, InputRouting.TOOL_TYPE_FINGER, 0))
        assertEquals(Tool.LINE, InputRouting.toolForEvent(Tool.LINE, InputRouting.TOOL_TYPE_MOUSE, 0))
    }

    @Test
    fun shapeSelectionRoutesFingerStylusAndMouseToShape() {
        assertEquals(Tool.SHAPE, InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_FINGER, 0))
        assertEquals(Tool.SHAPE, InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_STYLUS, 0))
        assertEquals(Tool.SHAPE, InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_MOUSE, 0))
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_ERASER, 0),
        )
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_STYLUS, InputRouting.BUTTON_STYLUS_PRIMARY),
        )
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.SHAPE, InputRouting.TOOL_TYPE_STYLUS, InputRouting.BUTTON_STYLUS_SECONDARY),
        )
    }

    @Test
    fun fillRoutesFingerStylusAndMouseToFill() {
        assertEquals(Tool.FILL, InputRouting.toolForEvent(Tool.FILL, InputRouting.TOOL_TYPE_STYLUS, 0))
        assertEquals(Tool.FILL, InputRouting.toolForEvent(Tool.FILL, InputRouting.TOOL_TYPE_FINGER, 0))
        assertEquals(Tool.FILL, InputRouting.toolForEvent(Tool.FILL, InputRouting.TOOL_TYPE_MOUSE, 0))
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.FILL, InputRouting.TOOL_TYPE_ERASER, 0),
        )
        assertEquals(
            Tool.ERASER,
            InputRouting.toolForEvent(Tool.FILL, InputRouting.TOOL_TYPE_STYLUS, InputRouting.BUTTON_STYLUS_PRIMARY),
        )
    }

    @Test
    fun oneFingerDrawsAndTwoFingersNavigate() {
        assertEquals(
            GestureMode.DRAW,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_FINGER, 1, stylusDown = false),
        )
        assertEquals(
            GestureMode.NAVIGATE,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_FINGER, 2, stylusDown = false),
        )
    }

    @Test
    fun stylusDrawsAndFingersDoNotNavigateWhileItIsDown() {
        assertEquals(
            GestureMode.DRAW,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_STYLUS, 1, stylusDown = false),
        )
        assertEquals(
            GestureMode.DRAW,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_ERASER, 2, stylusDown = false),
        )
        assertEquals(
            GestureMode.IGNORE,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_FINGER, 2, stylusDown = true),
        )
        assertEquals(
            GestureMode.DRAW,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_MOUSE, 1, stylusDown = false),
        )
        assertEquals(
            GestureMode.IGNORE,
            InputRouting.modeForPointer(InputRouting.TOOL_TYPE_MOUSE, 2, stylusDown = false),
        )
    }
}
