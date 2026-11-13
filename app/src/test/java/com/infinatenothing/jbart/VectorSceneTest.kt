package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.Geometry
import com.infinatenothing.jbart.export.VectorOp
import com.infinatenothing.jbart.export.VectorScene
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSceneTest {
    private val red = 0xFFFF0000.toInt()

    @Test
    fun lineIsOneStrokedPolyline() {
        val ops = VectorScene.build(listOf(line()), 100, 100, 0f)
        val group = ops.single() as VectorOp.Group
        assertEquals(0xFF0000, group.rgb)
        assertEquals(1f, group.opacity, 0f)
        val mark = group.marks.single()
        assertFalse(mark.filled)
        assertEquals(3f, mark.width, 0f)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), (mark.geometry as Geometry.Polyline).xy, 0f)
    }

    @Test
    fun zeroLengthLineAndEmptyStrokeAreSkipped() {
        val dot = line().copy(x2 = 10f, y2 = 20f)
        val empty = DrawObject.Stroke(emptyList(), red, 4f, InputType.FINGER)
        assertTrue(VectorScene.build(listOf(dot, empty), 100, 100, 0f).isEmpty())
    }

    @Test
    fun rectangleIsClosedMiterAndRotatesAboutBoxCenter() {
        val shape = DrawObject.Shape(ShapeKind.RECTANGLE, 30f, 40f, 10f, 20f, false, red, 0.5f, 2f, InputType.FINGER, 30f)
        val group = VectorScene.build(listOf(shape), 100, 100, 0f).single() as VectorOp.Group
        assertEquals(30f, group.rotationDeg, 0f)
        assertEquals(20f, group.pivotX, 0f)
        assertEquals(30f, group.pivotY, 0f)
        assertEquals(128f / 255f, group.opacity, 1e-6f)
        val mark = group.marks.single()
        assertFalse(mark.roundJoin)
        val poly = mark.geometry as Geometry.Polyline
        assertTrue(poly.closed)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 20f, 30f, 40f, 10f, 40f), poly.xy, 0f)
    }

    @Test
    fun filledArrowHasShaftAndClosedHead() {
        val shape = DrawObject.Shape(ShapeKind.ARROW, 0f, 0f, 100f, 0f, true, red, 1f, 2f, InputType.FINGER)
        val group = VectorScene.build(listOf(shape), 200, 200, 12f).single() as VectorOp.Group
        assertEquals(2, group.marks.size)
        assertFalse(group.marks[0].filled)
        val head = group.marks[1]
        assertTrue(head.filled)
        val xy = (head.geometry as Geometry.Polyline).xy
        assertEquals(100f, xy[2], 1e-4f)
        assertEquals(88f, xy[0], 1e-4f)
    }

    @Test
    fun strokeHasOneQuadPerSegmentPlusTail() {
        val points = listOf(point(0f, 0f), point(10f, 0f), point(20f, 10f))
        val group = VectorScene.build(listOf(DrawObject.Stroke(points, red, 4f, InputType.FINGER)), 100, 100, 0f)
            .single() as VectorOp.Group
        assertEquals(3, group.marks.size)
        assertTrue(group.marks[0].geometry is Geometry.Quad)
        assertTrue(group.marks[1].geometry is Geometry.Quad)
        val tail = group.marks[2].geometry as Geometry.Polyline
        assertArrayEquals(floatArrayOf(15f, 5f, 20f, 10f), tail.xy, 0f)
        group.marks.forEach { assertEquals(4f, it.width, 0f) }
    }

    @Test
    fun singlePointStrokeIsFilledDot() {
        val stroke = DrawObject.Stroke(listOf(point(5f, 5f)), red, 4f, InputType.FINGER)
        val mark = (VectorScene.build(listOf(stroke), 100, 100, 0f).single() as VectorOp.Group).marks.single()
        assertTrue(mark.filled)
        val oval = mark.geometry as Geometry.Oval
        assertEquals(3f, oval.left, 0f)
        assertEquals(7f, oval.bottom, 0f)
    }

    @Test
    fun fillIsCroppedToRunsAndClippedToCanvas() {
        val runs = intArrayOf(2, 3, 2, 3, 1, 10, 50, 0, 1)
        val fill = DrawObject.Fill(0, 0, 0x00123456, 0.5f, 0, 0, 8, 8, runs)
        val image = VectorScene.build(listOf(fill), 8, 8, 0f).single() as VectorOp.FillImage
        assertEquals(1, image.left)
        assertEquals(2, image.top)
        assertEquals(7, image.width)
        assertEquals(2, image.height)
        val pixels = image.pixels()
        val argb = (128 shl 24) or 0x123456
        assertEquals(0, pixels[0])
        assertEquals(argb, pixels[2])
        assertEquals(argb, pixels[3])
        assertEquals(0, pixels[4])
        assertEquals(argb, pixels[7])
        assertEquals(argb, pixels[13])
    }

    @Test
    fun eraseFlattensEverythingBeforeItAndLaterStrokesStayVector() {
        val erase = DrawObject.EraseStroke(listOf(point(1f, 1f)), 4f, InputType.FINGER)
        val objects = listOf(line(), erase, line(), erase, line())
        assertEquals(4, VectorScene.flattenCount(objects))
        val ops = VectorScene.build(objects, 100, 100, 0f)
        assertEquals(2, ops.size)
        assertSame(VectorOp.Flattened, ops[0])
        assertTrue(ops[1] is VectorOp.Group)
    }

    @Test
    fun noEraseMeansNoFlattenedImage() {
        assertEquals(0, VectorScene.flattenCount(listOf(line())))
        assertNull(VectorScene.build(listOf(line()), 100, 100, 0f).firstOrNull { it is VectorOp.Flattened })
    }

    private fun line() = DrawObject.Line(10f, 20f, 30f, 40f, red, 1f, 3f, InputType.FINGER)

    private fun point(x: Float, y: Float) = StrokePoint(x, y, 1f, 0L)
}
