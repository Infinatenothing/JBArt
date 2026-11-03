package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.Handle
import com.infinatenothing.jbart.canvas.ShapeTransform
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ShapeTransformTest {
    @Test
    fun handlePositionsAtZeroFortyFiveAndNinety() {
        val shape = rect(rotation = 0f)
        val at0 = FloatArray(18)
        ShapeTransform.handlePositions(shape, ROTATE_OFFSET, at0)
        assertHandles(
            at0,
            10f, 20f,
            60f, 20f,
            110f, 20f,
            110f, 40f,
            110f, 60f,
            60f, 60f,
            10f, 60f,
            10f, 40f,
            60f, 20f - ROTATE_OFFSET,
        )

        val at90 = FloatArray(18)
        ShapeTransform.handlePositions(shape.copy(rotationDeg = 90f), ROTATE_OFFSET, at90)
        assertHandles(
            at90,
            80f, -10f,
            80f, 40f,
            80f, 90f,
            60f, 90f,
            40f, 90f,
            40f, 40f,
            40f, -10f,
            60f, -10f,
            104f, 40f,
        )

        val at45 = FloatArray(18)
        ShapeTransform.handlePositions(shape.copy(rotationDeg = 45f), ROTATE_OFFSET, at45)
        val expected = FloatArray(18)
        ShapeTransform.handlePositions(shape, ROTATE_OFFSET, expected)
        rotateExpected(expected, 60f, 40f, 45f)
        for (i in expected.indices) {
            assertEquals(expected[i], at45[i], 0.02f)
        }
    }

    @Test
    fun hitAndInsideUseTheRotatedBox() {
        val shape = rect(0f, 0f, 100f, 100f, rotation = 45f)
        assertTrue(ShapeTransform.insideBox(shape, 50f, 50f, 0f))
        assertFalse(ShapeTransform.insideBox(shape, 90f, 0f, 0f))
        assertTrue(ShapeTransform.insideBox(shape.copy(rotationDeg = 0f), 10f, 10f, 0f))
        assertFalse(ShapeTransform.insideBox(shape.copy(rotationDeg = 0f), -1f, 10f, 0f))

        val handles = FloatArray(18)
        ShapeTransform.handlePositions(shape, ROTATE_OFFSET, handles)
        val rotate = Handle.ROTATE.ordinal
        assertEquals(
            Handle.ROTATE,
            ShapeTransform.hitHandle(shape, handles[rotate * 2], handles[rotate * 2 + 1], 24f, ROTATE_OFFSET),
        )
        assertNull(ShapeTransform.hitHandle(shape, 50f, 50f, 24f, ROTATE_OFFSET))
        val corner = Handle.TOP_LEFT.ordinal
        assertEquals(
            Handle.TOP_LEFT,
            ShapeTransform.hitHandle(
                shape,
                handles[corner * 2],
                handles[corner * 2 + 1],
                24f,
                ROTATE_OFFSET,
            ),
        )
    }

    @Test
    fun movePreservesSizeAndRotation() {
        val shape = rect(rotation = 33f)
        val moved = ShapeTransform.moved(shape, 12f, -5f)
        assertEquals(shape.x2 - shape.x1, moved.x2 - moved.x1, 0f)
        assertEquals(shape.y2 - shape.y1, moved.y2 - moved.y1, 0f)
        assertEquals(33f, moved.rotationDeg, 0f)
        assertEquals(shape.x1 + 12f, moved.x1, 0f)
        assertEquals(shape.y1 - 5f, moved.y1, 0f)
    }

    @Test
    fun resizeKeepsOppositeAnchorAtZeroAndThirtyDegrees() {
        val degrees = floatArrayOf(0f, 30f)
        val opposite = mapOf(
            Handle.TOP_LEFT to Handle.BOTTOM_RIGHT,
            Handle.TOP to Handle.BOTTOM,
            Handle.TOP_RIGHT to Handle.BOTTOM_LEFT,
            Handle.RIGHT to Handle.LEFT,
            Handle.BOTTOM_RIGHT to Handle.TOP_LEFT,
            Handle.BOTTOM to Handle.TOP,
            Handle.BOTTOM_LEFT to Handle.TOP_RIGHT,
            Handle.LEFT to Handle.RIGHT,
        )
        for (deg in degrees) {
            val shape = rect(0f, 0f, 120f, 80f, rotation = deg)
            for ((handle, anchor) in opposite) {
                val before = FloatArray(18)
                ShapeTransform.handlePositions(shape, ROTATE_OFFSET, before)
                val resized = ShapeTransform.resized(
                    shape,
                    handle,
                    before[handle.ordinal * 2] + 16f,
                    before[handle.ordinal * 2 + 1] - 12f,
                    false,
                    4f,
                )
                val after = FloatArray(18)
                ShapeTransform.handlePositions(resized, ROTATE_OFFSET, after)
                assertEquals(before[anchor.ordinal * 2], after[anchor.ordinal * 2], 0.05f)
                assertEquals(before[anchor.ordinal * 2 + 1], after[anchor.ordinal * 2 + 1], 0.05f)
                assertEquals(deg, resized.rotationDeg, 0f)
                assertTrue(resized.x1.isFinite() && resized.y1.isFinite())
                assertTrue(resized.x2.isFinite() && resized.y2.isFinite())
            }
        }
    }

    @Test
    fun constrainKeepsAspectAndMinimumAndFlipStayFinite() {
        val shape = rect(0f, 0f, 100f, 50f)
        val before = FloatArray(18)
        ShapeTransform.handlePositions(shape, ROTATE_OFFSET, before)
        val br = Handle.BOTTOM_RIGHT.ordinal
        val constrained = ShapeTransform.resized(
            shape,
            Handle.BOTTOM_RIGHT,
            before[br * 2],
            before[br * 2 + 1] + 100f,
            true,
            4f,
        )
        val width = abs(constrained.x2 - constrained.x1)
        val height = abs(constrained.y2 - constrained.y1)
        assertEquals(2f, width / height, 0.02f)
        val after = FloatArray(18)
        ShapeTransform.handlePositions(constrained, ROTATE_OFFSET, after)
        val tl = Handle.TOP_LEFT.ordinal
        assertEquals(before[tl * 2], after[tl * 2], 0.05f)
        assertEquals(before[tl * 2 + 1], after[tl * 2 + 1], 0.05f)

        val clamped = ShapeTransform.resized(shape, Handle.LEFT, 99f, 25f, false, 4f)
        assertEquals(96f, clamped.x1, 0.01f)
        assertEquals(100f, clamped.x2, 0.01f)

        val flipped = ShapeTransform.resized(shape, Handle.LEFT, 150f, 25f, false, 4f)
        assertTrue(flipped.x1.isFinite() && flipped.y1.isFinite() && flipped.x2.isFinite() && flipped.y2.isFinite())
        assertEquals(150f, flipped.x1, 0.01f)
        assertEquals(100f, flipped.x2, 0.01f)
        assertFalse(flipped.x1.isNaN())
    }

    @Test
    fun rotateSnapsWithLineSnapToleranceAndNormalizes() {
        val shape = rect(0f, 0f, 100f, 100f)
        val snapped = ShapeTransform.rotated(shape, touchX(14.0), touchY(14.0), true)
        assertEquals(15f, snapped.rotationDeg, 0.05f)
        val free = ShapeTransform.rotated(shape, touchX(22.0), touchY(22.0), true)
        assertEquals(22f, free.rotationDeg, 0.05f)
        val unsnapped = ShapeTransform.rotated(shape, touchX(14.0), touchY(14.0), false)
        assertEquals(14f, unsnapped.rotationDeg, 0.05f)
        val wrapped = ShapeTransform.rotated(shape, touchX(-20.0), touchY(-20.0), false)
        assertEquals(340f, wrapped.rotationDeg, 0.05f)
        assertTrue(wrapped.rotationDeg >= 0f && wrapped.rotationDeg < 360f)
        val north = ShapeTransform.rotated(shape, 50f, 10f, true)
        assertEquals(0f, north.rotationDeg, 0.05f)
    }

    @Test
    fun rotatedBoundsContainHandlesAndArrowhead() {
        val shape = rect(kind = ShapeKind.ARROW, x1 = 0f, y1 = 0f, x2 = 100f, y2 = 0f, rotation = 30f)
        val bounds = ShapeTransform.rotatedBounds(shape, widthPx = 8f, rotateOffsetPx = 24f, minHeadPx = 12f, handleRadiusPx = 6f)
        val handles = FloatArray(18)
        ShapeTransform.handlePositions(shape, 24f, handles)
        for (i in 0 until 9) {
            assertTrue(handles[i * 2] >= bounds[0] && handles[i * 2] <= bounds[2])
            assertTrue(handles[i * 2 + 1] >= bounds[1] && handles[i * 2 + 1] <= bounds[3])
        }
        assertTrue(bounds[2] - bounds[0] > 100f)
    }

    private fun touchX(degreesClockwiseFromUp: Double): Float {
        val rad = Math.toRadians(degreesClockwiseFromUp)
        return 50f + sin(rad).toFloat() * 40f
    }

    private fun touchY(degreesClockwiseFromUp: Double): Float {
        val rad = Math.toRadians(degreesClockwiseFromUp)
        return 50f - cos(rad).toFloat() * 40f
    }

    private fun rotateExpected(points: FloatArray, cx: Float, cy: Float, deg: Float) {
        val rad = Math.toRadians(deg.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        for (i in 0 until points.size / 2) {
            val dx = points[i * 2] - cx
            val dy = points[i * 2 + 1] - cy
            points[i * 2] = cx + dx * cos - dy * sin
            points[i * 2 + 1] = cy + dx * sin + dy * cos
        }
    }

    private fun assertHandles(actual: FloatArray, vararg expected: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 0.02f)
        }
    }

    private fun rect(
        x1: Float = 10f,
        y1: Float = 20f,
        x2: Float = 110f,
        y2: Float = 60f,
        rotation: Float = 0f,
        kind: ShapeKind = ShapeKind.RECTANGLE,
    ) = DrawObject.Shape(
        kind = kind,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        filled = false,
        color = 0xFF000000.toInt(),
        alpha = 1f,
        widthPx = 4f,
        inputType = InputType.FINGER,
        rotationDeg = rotation,
    )

    private companion object {
        const val ROTATE_OFFSET = 24f
    }
}
