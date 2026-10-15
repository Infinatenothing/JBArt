package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
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
        document.objects += DrawObject.Stroke(Tool.BRUSH)
        document.objects += DrawObject.Line()
        assertEquals(Tool.BRUSH, document.objects[0].tool)
        assertTrue(document.objects[1] is DrawObject.Line)
    }
}
