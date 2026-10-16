package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.SegmentBuffer
import com.infinatenothing.jbart.canvas.StrokeMath
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeMathTest {
    @Test
    fun pressureCurveIsBoundedAndMonotonic() {
        assertEquals(StrokeMath.MIN_PRESSURE_FACTOR, StrokeMath.pressureCurve(0f), 0.0001f)
        assertEquals(1f, StrokeMath.pressureCurve(1f), 0.0001f)
        assertEquals(StrokeMath.MIN_PRESSURE_FACTOR, StrokeMath.pressureCurve(-0.4f), 0.0001f)
        assertEquals(1f, StrokeMath.pressureCurve(2f), 0.0001f)

        var previous = StrokeMath.pressureCurve(0f)
        var p = 0.1f
        while (p <= 1.0001f) {
            val next = StrokeMath.pressureCurve(p)
            assertTrue(next >= previous)
            previous = next
            p += 0.1f
        }
    }

    @Test
    fun fingerWidthIgnoresPressure() {
        val light = StrokePoint(0f, 0f, 0.05f, 0L)
        val heavy = StrokePoint(1f, 1f, 1f, 1L)
        assertEquals(8f, StrokeMath.widthFor(light, 8f, InputType.FINGER), 0.0001f)
        assertEquals(8f, StrokeMath.widthFor(heavy, 8f, InputType.FINGER), 0.0001f)
    }

    @Test
    fun stylusWidthFollowsPressureCurve() {
        val light = StrokePoint(0f, 0f, 0f, 0L)
        val heavy = StrokePoint(0f, 0f, 1f, 0L)
        assertEquals(2f, StrokeMath.widthFor(light, 8f, InputType.STYLUS), 0.0001f)
        assertEquals(8f, StrokeMath.widthFor(heavy, 8f, InputType.STYLUS), 0.0001f)
        val mid = StrokeMath.widthFor(StrokePoint(0f, 0f, 0.5f, 0L), 8f, InputType.STYLUS)
        assertTrue(mid > 2f && mid < 8f)
    }

    @Test
    fun midpointAveragesEndpoints() {
        val mid = StrokeMath.midpoint(
            StrokePoint(0f, 2f, 0.2f, 10L),
            StrokePoint(10f, 6f, 0.8f, 20L),
        )
        assertEquals(5f, mid.x, 0.0001f)
        assertEquals(4f, mid.y, 0.0001f)
        assertEquals(0.5f, mid.pressure, 0.0001f)
        assertEquals(15L, mid.timeMs)
    }

    @Test
    fun quadSegmentSmoothsThroughMidpoints() {
        val a = StrokePoint(0f, 0f, 0f, 0L)
        val b = StrokePoint(10f, 0f, 1f, 10L)
        val c = StrokePoint(10f, 10f, 1f, 20L)
        val buffer = SegmentBuffer()

        StrokeMath.fillQuadSegment(null, a, b, 8f, InputType.STYLUS, buffer)
        assertEquals(0f, buffer.startX, 0.001f)
        assertEquals(0f, buffer.startY, 0.001f)
        assertEquals(0f, buffer.controlX, 0.001f)
        assertEquals(0f, buffer.controlY, 0.001f)
        assertEquals(5f, buffer.endX, 0.001f)
        assertEquals(0f, buffer.endY, 0.001f)
        assertTrue(buffer.width > 2f && buffer.width < 8f)

        StrokeMath.fillQuadSegment(a, b, c, 8f, InputType.FINGER, buffer)
        assertEquals(5f, buffer.startX, 0.001f)
        assertEquals(0f, buffer.startY, 0.001f)
        assertEquals(10f, buffer.controlX, 0.001f)
        assertEquals(0f, buffer.controlY, 0.001f)
        assertEquals(10f, buffer.endX, 0.001f)
        assertEquals(5f, buffer.endY, 0.001f)
        assertEquals(8f, buffer.width, 0.001f)
    }
}
