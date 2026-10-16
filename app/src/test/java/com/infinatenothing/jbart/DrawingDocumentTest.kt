package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingDocumentTest {
    @Test
    fun startsEmpty() {
        val document = DrawingDocument()
        assertTrue(document.objects.isEmpty())
        assertEquals(0, document.width)
        assertEquals(0, document.height)
        assertNull(document.bitmap)
    }

    @Test
    fun objectsStayInOrder() {
        val document = DrawingDocument()
        document.objects += stroke(1f)
        document.objects += DrawObject.Line()
        assertEquals(Tool.BRUSH, document.objects[0].tool)
        assertTrue(document.objects[0] is DrawObject.Stroke)
        assertTrue(document.objects[1] is DrawObject.Line)
    }

    @Test
    fun addThenRemovePreservesOrder() {
        val document = DrawingDocument()
        val first = stroke(1f)
        val second = stroke(2f)
        document.objects.add(first)
        document.objects.add(second)
        assertEquals(listOf(first, second), document.objects)
        document.objects.removeAt(document.objects.lastIndex)
        assertEquals(listOf(first), document.objects)
        document.objects.removeAt(document.objects.lastIndex)
        assertTrue(document.objects.isEmpty())
    }

    private fun stroke(x: Float) = DrawObject.Stroke(
        points = listOf(StrokePoint(x, 0f, 0.5f, 0L)),
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.FINGER,
    )
}
