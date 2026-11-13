package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.SvgWriter
import com.infinatenothing.jbart.export.VectorOp
import com.infinatenothing.jbart.export.VectorScene
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class SvgWriterTest {
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun lineWithBackground() {
        val line = DrawObject.Line(0f, 0f, 10.5f, 20f, blue, 1f, 3f, InputType.FINGER)
        val svg = encode(listOf(line), transparent = false)
        assertTrue(svg.contains("viewBox=\"0 0 40 30\""))
        assertTrue(svg.contains("<title>JBArt</title>"))
        assertTrue(svg.contains("<rect width=\"40\" height=\"30\" fill=\"#FFEECC\"/>"))
        assertTrue(svg.contains("stroke=\"#0000FF\""))
        assertTrue(svg.contains("<path d=\"M 0 0 L 10.5 20\" stroke-width=\"3\"/>"))
        assertFalse(svg.contains("opacity"))
        parse(svg)
    }

    @Test
    fun rotatedTranslucentFilledRectangle() {
        val shape = DrawObject.Shape(ShapeKind.RECTANGLE, 0f, 0f, 10f, 10f, true, blue, 0.5f, 2f, InputType.FINGER, 45f)
        val svg = encode(listOf(shape), transparent = true)
        assertFalse(svg.contains("<rect"))
        assertTrue(svg.contains("opacity=\"0.502\""))
        assertTrue(svg.contains("transform=\"rotate(45 5 5)\""))
        assertTrue(svg.contains("<path d=\"M 0 0 L 10 0 L 10 10 L 0 10 Z\" fill=\"#0000FF\" stroke=\"none\"/>"))
        parse(svg)
    }

    @Test
    fun strokedTriangleUsesMiter() {
        val shape = DrawObject.Shape(ShapeKind.TRIANGLE, 0f, 0f, 10f, 10f, false, blue, 1f, 2f, InputType.FINGER)
        val svg = encode(listOf(shape), transparent = true)
        assertTrue(svg.contains("stroke-linejoin=\"miter\" stroke-miterlimit=\"2.5\""))
        parse(svg)
    }

    @Test
    fun fillRunEmbedsImageAtItsBounds() {
        val fill = DrawObject.Fill(0, 0, blue, 1f, 0, 0, 40, 30, intArrayOf(5, 2, 3))
        val svg = encode(listOf(fill), transparent = false)
        assertTrue(svg.contains("<image x=\"2\" y=\"5\" width=\"3\" height=\"1\" xlink:href=\"data:image/png;base64,AQI=\"/>"))
        parse(svg)
    }

    @Test
    fun eraseThenStrokeDrawsFlattenedImageFirst() {
        val point = StrokePoint(1f, 1f, 1f, 0L)
        val objects = listOf(
            DrawObject.Stroke(listOf(point), blue, 4f, InputType.FINGER),
            DrawObject.EraseStroke(listOf(point), 4f, InputType.FINGER),
            DrawObject.Stroke(listOf(point, StrokePoint(5f, 5f, 1f, 0L)), blue, 4f, InputType.FINGER),
        )
        val svg = encode(objects, transparent = true)
        val image = svg.indexOf("<image x=\"0\" y=\"0\" width=\"40\" height=\"30\"")
        assertTrue(image > 0)
        assertTrue(svg.indexOf("<g ") > image)
        assertEquals(1, Regex("<image").findAll(svg).count())
        parse(svg)
    }

    @Test
    fun numbersAreCompact() {
        assertEquals("3", SvgWriter.num(3f))
        assertEquals("2.5", SvgWriter.num(2.5f))
        assertEquals("0.1", SvgWriter.num(0.1f))
        assertEquals("-1.23", SvgWriter.num(-1.234f))
        assertEquals("0", SvgWriter.num(-0.001f))
        assertEquals("#00ABCD", SvgWriter.hex(0x8000ABCD.toInt()))
    }

    private fun encode(objects: List<DrawObject>, transparent: Boolean): String {
        val ops = VectorScene.build(objects, 40, 30, 0f)
        val out = ByteArrayOutputStream()
        SvgWriter.encode(ops, 40, 30, 0xFFFFEECC.toInt(), transparent, { op: VectorOp -> byteArrayOf(1, 2) }, out)
        return out.toString("UTF-8")
    }

    private fun parse(svg: String) {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(svg.byteInputStream())
        assertEquals("svg", doc.documentElement.localName)
    }
}
