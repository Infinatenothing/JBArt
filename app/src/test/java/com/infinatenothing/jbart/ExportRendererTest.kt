package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.ExportRenderer
import com.infinatenothing.jbart.model.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportRendererTest {
    @Test
    fun opaqueCompositeMatchesSourceOver() {
        val ink = 0x80FF0000.toInt()
        val background = 0xFF00FF00.toInt()
        val out = IntArray(1)
        ExportRenderer.compositeRow(intArrayOf(ink), background, transparent = false, out)
        assertEquals(ColorMath.compositeOver(ink, background), out[0])
    }

    @Test
    fun transparentKeepsSourceAlpha() {
        val ink = intArrayOf(0x00000000, 0x80FFFFFF.toInt())
        val out = IntArray(2)
        ExportRenderer.compositeRow(ink, 0xFFFF0000.toInt(), transparent = true, out)
        assertEquals(ink[0], out[0])
        assertEquals(ink[1], out[1])
    }

    @Test
    fun gifPixelKeepsClearAndFlattensPartialAlpha() {
        val background = 0xFF00FF00.toInt()
        assertEquals(0, ExportRenderer.gifPixel(0x00123456, background))
        val half = 0x80FF0000.toInt()
        assertEquals(ColorMath.compositeOver(half, background), ExportRenderer.gifPixel(half, background))
        assertEquals(0xFF112233.toInt(), ExportRenderer.gifPixel(0xFF112233.toInt(), background))
    }
}
