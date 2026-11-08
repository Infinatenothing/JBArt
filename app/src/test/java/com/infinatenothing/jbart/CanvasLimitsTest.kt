package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.CanvasLimits
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.visibleObjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLimitsTest {
    @Test
    fun presetsInsideTheCapAreOfferedUntilHalfTheHeap() {
        assertTrue(CanvasLimits.offered(2560, 1600, 64))
        assertFalse(CanvasLimits.offered(2560, 1600, 32))
        assertFalse(CanvasLimits.withinCap(0, 100))
        assertFalse(CanvasLimits.withinCap(4097, 100))
        assertFalse(CanvasLimits.withinCap(4000, 4001))
        assertTrue(CanvasLimits.withinCap(2048, 2048))
    }

    @Test
    fun replayStartsAtTheLastClear() {
        val stroke = DrawObject.Stroke(
            points = listOf(StrokePoint(1f, 1f, 1f, 0L)),
            color = 0xFF000000.toInt(),
            baseWidthPx = 4f,
            inputType = InputType.STYLUS,
        )
        val objects = listOf(stroke, DrawObject.Clear, stroke, DrawObject.Clear)
        assertTrue(visibleObjects(objects).isEmpty())
        assertEquals(listOf(stroke), visibleObjects(listOf(stroke, DrawObject.Clear, stroke)))
        assertEquals(listOf(stroke), visibleObjects(listOf(stroke)))
    }
}
