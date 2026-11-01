package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.LineSnap
import com.infinatenothing.jbart.canvas.ShapeGeometry
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class ShapeGeometryTest {
    @Test
    fun rectangleConstrainIsSquareInAllDirections() {
        val signs = listOf(1f to 1f, 1f to -1f, -1f to 1f, -1f to -1f)
        for ((sx, sy) in signs) {
            val end = ShapeGeometry.constrainEnd(
                ShapeKind.RECTANGLE,
                10f,
                20f,
                10f + 30f * sx,
                20f + 10f * sy,
                true,
            )
            val w = end.first - 10f
            val h = end.second - 20f
            assertEquals(abs(w), abs(h), 0.001f)
            assertEquals(30f, abs(w), 0.001f)
            assertEquals(sx, kotlin.math.sign(w), 0f)
            assertEquals(sy, kotlin.math.sign(h), 0f)
        }
    }

    @Test
    fun ellipseConstrainIsCircleBox() {
        val end = ShapeGeometry.constrainEnd(ShapeKind.ELLIPSE, 0f, 0f, 12f, -40f, true)
        assertEquals(abs(end.first), abs(end.second), 0.001f)
        assertEquals(40f, abs(end.second), 0.001f)
        assertTrue(end.first > 0f)
        assertTrue(end.second < 0f)
    }

    @Test
    fun constrainOffReturnsTheEndUnchanged() {
        for (kind in ShapeKind.entries) {
            val end = ShapeGeometry.constrainEnd(kind, 1f, 2f, 8f, -3f, false)
            assertEquals(8f, end.first, 0f)
            assertEquals(-3f, end.second, 0f)
        }
    }

    @Test
    fun triangleStaysUprightWhenDraggingUpOrDown() {
        assertUpright(0f, 100f, 40f, 10f)
        assertUpright(0f, 10f, 40f, 100f)
    }

    @Test
    fun constrainedTriangleIsEquilateral() {
        val end = ShapeGeometry.constrainEnd(ShapeKind.TRIANGLE, 0f, 0f, 100f, 20f, true)
        val points = ShapeGeometry.trianglePoints(0f, 0f, end.first, end.second)
        val side01 = distance(points, 0, 1)
        val side12 = distance(points, 1, 2)
        val side20 = distance(points, 2, 0)
        assertEquals(side01, side12, 0.02f)
        assertEquals(side12, side20, 0.02f)
        val height = 100f * sqrt(3.0).toFloat() / 2f
        assertEquals(100f, end.first, 0.02f)
        assertEquals(height, end.second, 0.02f)
        assertEquals(0f, points[1], 0.02f)
        assertTrue(points[3] > points[1])
    }

    @Test
    fun arrowHeadTipIsTheEndAndShortShaftClampsHeadLength() {
        val head = ShapeGeometry.arrowHead(2f, 3f, 80f, 30f, 16f)
        assertEquals(80f, head[0], 0f)
        assertEquals(30f, head[1], 0f)
        val length = ShapeGeometry.arrowHeadLength(widthPx = 10f, shaftLength = 50f, minHeadPx = 12f)
        assertEquals(20f, length, 0.001f)
        val clamped = ShapeGeometry.arrowHead(0f, 0f, 50f, 0f, length)
        val midX = (clamped[2] + clamped[4]) * 0.5f
        val midY = (clamped[3] + clamped[5]) * 0.5f
        assertEquals(length, hypot(clamped[0] - midX, clamped[1] - midY), 0.05f)
    }

    @Test
    fun arrowConstrainSnapsNearHorizontalAndLeavesThirtyDegrees() {
        val near = endAtAngle(3.0)
        val snapped = ShapeGeometry.constrainEnd(ShapeKind.ARROW, 0f, 0f, near.first, near.second, true)
        assertEquals(0f, LineSnap.angleDeg(0f, 0f, snapped.first, snapped.second), 0.05f)
        assertEquals(100f, hypot(snapped.first, snapped.second), 0.05f)

        val free = endAtAngle(30.0)
        val kept = ShapeGeometry.constrainEnd(ShapeKind.ARROW, 0f, 0f, free.first, free.second, true)
        assertEquals(30f, LineSnap.angleDeg(0f, 0f, kept.first, kept.second), 0.05f)
        assertEquals(100f, hypot(kept.first, kept.second), 0.05f)
    }

    @Test
    fun boundsContainVerticesPlusHalfStroke() {
        val triangle = shape(ShapeKind.TRIANGLE, 0f, 0f, 100f, 80f, width = 10f)
        val triangleBounds = ShapeGeometry.bounds(triangle, triangle.widthPx)
        val trianglePoints = ShapeGeometry.trianglePoints(0f, 0f, 100f, 80f)
        assertContains(triangleBounds, trianglePoints, halfStroke = 5f)

        val minHead = 12f
        val arrow = shape(ShapeKind.ARROW, 0f, 0f, 80f, 10f, width = 8f)
        val arrowBounds = ShapeGeometry.bounds(arrow, arrow.widthPx, minHead)
        val headLength = ShapeGeometry.arrowHeadLength(arrow.widthPx, hypot(80f, 10f), minHead)
        val head = ShapeGeometry.arrowHead(0f, 0f, 80f, 10f, headLength)
        val vertices = floatArrayOf(0f, 0f, 80f, 10f, head[0], head[1], head[2], head[3], head[4], head[5])
        assertContains(arrowBounds, vertices, halfStroke = 4f)
    }

    @Test
    fun zeroSizeDragProducesNoNaN() {
        for (kind in ShapeKind.entries) {
            val end = ShapeGeometry.constrainEnd(kind, 5f, 6f, 5f, 6f, true)
            assertTrue(end.first.isFinite())
            assertTrue(end.second.isFinite())
            val box = ShapeGeometry.normalizedBox(5f, 6f, end.first, end.second)
            assertTrue(box.all { it.isFinite() })
        }
        val points = ShapeGeometry.trianglePoints(5f, 6f, 5f, 6f)
        assertTrue(points.all { it.isFinite() })
        val head = ShapeGeometry.arrowHead(5f, 6f, 5f, 6f, 0f)
        assertTrue(head.all { it.isFinite() })
        val bounds = ShapeGeometry.bounds(shape(ShapeKind.ARROW, 5f, 6f, 5f, 6f, width = 4f), 4f, 12f)
        assertTrue(bounds.all { it.isFinite() })
        assertEquals(0f, ShapeGeometry.arrowHeadLength(4f, 0f, 12f), 0f)
    }

    private fun assertUpright(x1: Float, y1: Float, x2: Float, y2: Float) {
        val box = ShapeGeometry.normalizedBox(x1, y1, x2, y2)
        val points = ShapeGeometry.trianglePoints(x1, y1, x2, y2)
        assertEquals(box[1], points[1], 0f)
        assertEquals(box[3], points[3], 0f)
        assertEquals(points[3], points[5], 0f)
        assertTrue(points[1] <= points[3])
        assertEquals((box[0] + box[2]) * 0.5f, points[0], 0.001f)
    }

    private fun assertContains(bounds: FloatArray, vertices: FloatArray, halfStroke: Float) {
        var i = 0
        while (i < vertices.size) {
            assertTrue(bounds[0] <= vertices[i] - halfStroke + 0.01f)
            assertTrue(bounds[1] <= vertices[i + 1] - halfStroke + 0.01f)
            assertTrue(bounds[2] >= vertices[i] + halfStroke - 0.01f)
            assertTrue(bounds[3] >= vertices[i + 1] + halfStroke - 0.01f)
            i += 2
        }
    }

    private fun distance(points: FloatArray, a: Int, b: Int): Float {
        return hypot(points[b * 2] - points[a * 2], points[b * 2 + 1] - points[a * 2 + 1])
    }

    private fun endAtAngle(degrees: Double): Pair<Float, Float> {
        val radians = Math.toRadians(degrees)
        return (cos(radians) * 100.0).toFloat() to (sin(radians) * 100.0).toFloat()
    }

    private fun shape(
        kind: ShapeKind,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        width: Float,
    ) = DrawObject.Shape(
        kind = kind,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        filled = false,
        color = 0xFF000000.toInt(),
        alpha = 1f,
        widthPx = width,
        inputType = InputType.STYLUS,
    )
}
