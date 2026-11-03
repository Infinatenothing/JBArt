package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.LineHandle
import com.infinatenothing.jbart.canvas.LineSnap
import com.infinatenothing.jbart.canvas.LineTransform
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class LineTransformTest {
    @Test
    fun handleHitBeatsTheBodyAndCloserHandleWins() {
        val line = line(0f, 0f, 100f, 0f)
        assertEquals(LineHandle.START, LineTransform.hitLineHandle(line, 0f, 0f, 8f))
        assertEquals(LineHandle.END, LineTransform.hitLineHandle(line, 100f, 0f, 8f))
        assertNull(LineTransform.hitLineHandle(line, 50f, 0f, 8f))
        assertTrue(LineTransform.nearLine(line, 0f, 0f, 8f))
        assertEquals(LineHandle.START, LineTransform.hitLineHandle(line, 2f, 0f, 20f))
        assertEquals(LineHandle.END, LineTransform.hitLineHandle(line, 98f, 0f, 20f))
        assertEquals(LineHandle.START, LineTransform.hitLineHandle(line(0f, 0f, 10f, 0f), 5f, 0f, 20f))
        assertNull(LineTransform.hitLineHandle(line, Float.NaN, 0f, 8f))
    }

    @Test
    fun nearLineUsesSegmentDistanceIncludingPastTheEnds() {
        val line = line(0f, 0f, 100f, 0f)
        assertTrue(LineTransform.nearLine(line, 50f, 5f, 10f))
        assertFalse(LineTransform.nearLine(line, 50f, 11f, 10f))
        assertFalse(LineTransform.nearLine(line, 130f, 0f, 10f))
        assertFalse(LineTransform.nearLine(line, 200f, 0.5f, 10f))
        assertTrue(LineTransform.nearLine(line, 108f, 0f, 10f))
        assertFalse(LineTransform.nearLine(line, Float.NaN, 0f, 10f))
    }

    @Test
    fun moveKeepsLengthAndAngle() {
        val line = line(2f, 4f, 14f, 10f)
        val moved = LineTransform.movedLine(line, 5f, -3f)
        assertEquals(line.x2 - line.x1, moved.x2 - moved.x1, 0f)
        assertEquals(line.y2 - line.y1, moved.y2 - moved.y1, 0f)
        assertEquals(7f, moved.x1, 0f)
        assertEquals(1f, moved.y1, 0f)
        assertEquals(line.color, moved.color)
        assertEquals(line, LineTransform.movedLine(line, Float.NaN, 1f))
    }

    @Test
    fun endpointDragSnapOffIsFreeAndAnchorStays() {
        val line = line(0f, 0f, 40f, 0f)
        val out = FloatArray(4)
        LineTransform.draggedEndpoint(line, LineHandle.END, 10f, 30f, false, 4f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        assertEquals(10f, out[2], 0f)
        assertEquals(30f, out[3], 0f)

        LineTransform.draggedEndpoint(line, LineHandle.START, 10f, 40f, false, 4f, out)
        assertEquals(10f, out[0], 0f)
        assertEquals(40f, out[1], 0f)
        assertEquals(40f, out[2], 0f)
        assertEquals(0f, out[3], 0f)
    }

    @Test
    fun endpointDragSnapUsesLineSnapAroundTheAnchor() {
        val line = line(0f, 0f, 80f, 0f)
        val out = FloatArray(4)
        val expected = FloatArray(2)
        val close = point(80.0, 3.0)
        assertTrue(
            LineSnap.snapInto(
                0f, 0f, close[0], close[1],
                LineSnap.SNAP_STEP_DEG, LineSnap.SNAP_TOLERANCE_DEG, true, expected,
            ),
        )
        LineTransform.draggedEndpoint(line, LineHandle.END, close[0], close[1], true, 4f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        assertEquals(expected[0], out[2], 0.02f)
        assertEquals(expected[1], out[3], 0.02f)

        val wide = point(80.0, 8.0)
        assertFalse(
            LineSnap.snapInto(
                0f, 0f, wide[0], wide[1],
                LineSnap.SNAP_STEP_DEG, LineSnap.SNAP_TOLERANCE_DEG, true, expected,
            ),
        )
        LineTransform.draggedEndpoint(line, LineHandle.END, wide[0], wide[1], true, 4f, out)
        assertEquals(expected[0], out[2], 0.02f)
        assertEquals(expected[1], out[3], 0.02f)
        assertEquals(0f, out[0], 0f)
    }

    @Test
    fun minLengthClampAndZeroLengthStayFinite() {
        val line = line(0f, 0f, 50f, 0f)
        val out = FloatArray(4)
        LineTransform.draggedEndpoint(line, LineHandle.END, 1f, 0f, false, 4f, out)
        assertEquals(4f, hypot(out[2] - out[0], out[3] - out[1]), 0.01f)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)

        LineTransform.draggedEndpoint(line, LineHandle.END, 0f, 0f, false, 4f, out)
        assertTrue(out.all { it.isFinite() })
        assertEquals(4f, hypot(out[2] - out[0], out[3] - out[1]), 0.01f)
        assertEquals(4f, out[2], 0.01f)
        assertEquals(0f, out[3], 0.01f)

        val zero = line(0f, 0f, 0f, 0f)
        LineTransform.draggedEndpoint(zero, LineHandle.END, 0f, 0f, true, 4f, out)
        assertTrue(out.all { it.isFinite() })
        assertEquals(4f, hypot(out[2] - out[0], out[3] - out[1]), 0.01f)
    }

    private fun point(length: Double, degrees: Double): FloatArray {
        val rad = Math.toRadians(degrees)
        return floatArrayOf((cos(rad) * length).toFloat(), (sin(rad) * length).toFloat())
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = DrawObject.Line(
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        color = 0xFF000000.toInt(),
        alpha = 1f,
        widthPx = 4f,
        inputType = InputType.FINGER,
    )
}
