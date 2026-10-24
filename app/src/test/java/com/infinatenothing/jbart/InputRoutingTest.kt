package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.InputRouting
import com.infinatenothing.jbart.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun lineShapeAndFillDoNotDraw() {
        for (selected in listOf(Tool.LINE, Tool.SHAPE, Tool.FILL)) {
            assertNull(InputRouting.toolForEvent(selected, InputRouting.TOOL_TYPE_STYLUS, 0))
            assertNull(InputRouting.toolForEvent(selected, InputRouting.TOOL_TYPE_FINGER, 0))
            assertNull(InputRouting.toolForEvent(selected, InputRouting.TOOL_TYPE_MOUSE, 0))
        }
    }
}
