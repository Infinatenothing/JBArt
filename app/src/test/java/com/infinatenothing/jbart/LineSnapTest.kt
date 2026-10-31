package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.LineSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LineSnapTest {
    @Test
    fun threeDegreesOffHorizontalSnapsLevelIncludingLeft() {
        val right = snap(3.0)
        assertEquals(0f, right.second, EPS)
        assertTrue(right.first > 0f)

        val left = snap(177.0)
        assertEquals(0f, left.second, EPS)
        assertTrue(left.first < 0f)

        val leftBelow = snap(-177.0)
        assertEquals(0f, leftBelow.second, EPS)
        assertTrue(leftBelow.first < 0f)
    }

    @Test
    fun eightyEightDegreesSnapsVerticalUpAndDown() {
        val down = snap(88.0)
        assertEquals(0f, down.first, EPS)
        assertTrue(down.second > 0f)

        val up = snap(-88.0)
        assertEquals(0f, up.first, EPS)
        assertTrue(up.second < 0f)
    }

    @Test
    fun fortyTwoDegreesSnapsToFortyFiveInAllQuadrants() {
        assertDiagonal(42.0, 45f, xPositive = true, yPositive = true)
        assertDiagonal(138.0, 135f, xPositive = false, yPositive = true)
        assertDiagonal(-42.0, -45f, xPositive = true, yPositive = false)
        assertDiagonal(-138.0, -135f, xPositive = false, yPositive = false)
    }

    @Test
    fun lengthIsPreserved() {
        val length = 312.0
        val (rawX, rawY) = raw(42.0, length)
        val (x, y) = LineSnap.snapEnd(0f, 0f, rawX, rawY, LineSnap.SNAP_STEP_DEG, LineSnap.SNAP_TOLERANCE_DEG)
        assertEquals(hypot(rawX, rawY), hypot(x, y), EPS)
    }

    @Test
    fun outsideToleranceStaysFree() {
        val onThirty = snap(30.0)
        assertEquals(30f, LineSnap.angleDeg(0f, 0f, onThirty.first, onThirty.second), 0.05f)
        val (rawX, rawY) = raw(30.0)
        assertEquals(rawX, onThirty.first, 0.05f)
        assertEquals(rawY, onThirty.second, 0.05f)

        val free = snap(38.0)
        val (freeX, freeY) = raw(38.0)
        assertEquals(freeX, free.first, 0f)
        assertEquals(freeY, free.second, 0f)
        val angle = LineSnap.angleDeg(0f, 0f, free.first, free.second)
        assertEquals(38f, angle, 0.05f)
        assertTrue(abs(angle - 30f) > 1f)
        assertTrue(abs(angle - 45f) > 1f)
    }

    @Test
    fun snapOffReturnsTheRawEnd() {
        val (rawX, rawY) = raw(2.0)
        val (x, y) = LineSnap.snapEnd(
            0f,
            0f,
            rawX,
            rawY,
            LineSnap.SNAP_STEP_DEG,
            LineSnap.SNAP_TOLERANCE_DEG,
            enabled = false,
        )
        assertEquals(rawX, x, 0f)
        assertEquals(rawY, y, 0f)
    }

    @Test
    fun zeroLengthReturnsStartWithoutNaN() {
        val (x, y) = LineSnap.snapEnd(2f, 5f, 2f, 5f, LineSnap.SNAP_STEP_DEG, LineSnap.SNAP_TOLERANCE_DEG)
        assertEquals(2f, x, 0f)
        assertEquals(5f, y, 0f)
        assertFalse(x.isNaN())
        assertFalse(y.isNaN())
    }

    @Test
    fun fortyFiveDegreeStepSnapsOnlyNearTargets() {
        val (rawX, rawY) = raw(20.0)
        val (freeX, freeY) = LineSnap.snapEnd(
            0f,
            0f,
            rawX,
            rawY,
            LineSnap.SNAP_STEP_ALT_DEG,
            LineSnap.SNAP_TOLERANCE_DEG,
        )
        assertEquals(rawX, freeX, 0f)
        assertEquals(rawY, freeY, 0f)

        val (x, y) = snap(43.0, step = LineSnap.SNAP_STEP_ALT_DEG)
        assertEquals(45f, LineSnap.angleDeg(0f, 0f, x, y), 0.05f)
        assertEquals(abs(x), abs(y), 0.01f)
    }

    private fun assertDiagonal(rawDeg: Double, snappedDeg: Float, xPositive: Boolean, yPositive: Boolean) {
        val (x, y) = snap(rawDeg)
        assertEquals(abs(x), abs(y), 0.01f)
        assertEquals(snappedDeg, LineSnap.angleDeg(0f, 0f, x, y), 0.05f)
        assertEquals(xPositive, x > 0f)
        assertEquals(yPositive, y > 0f)
    }

    private fun snap(degrees: Double, step: Float = LineSnap.SNAP_STEP_DEG): Pair<Float, Float> {
        val (rawX, rawY) = raw(degrees)
        return LineSnap.snapEnd(0f, 0f, rawX, rawY, step, LineSnap.SNAP_TOLERANCE_DEG)
    }

    private fun raw(degrees: Double, length: Double = 100.0): Pair<Float, Float> {
        val rad = Math.toRadians(degrees)
        return (Math.cos(rad) * length).toFloat() to (Math.sin(rad) * length).toFloat()
    }

    private companion object {
        const val EPS = 0.001f
    }
}
