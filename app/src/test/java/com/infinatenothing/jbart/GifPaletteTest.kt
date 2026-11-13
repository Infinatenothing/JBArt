package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.GifPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GifPaletteTest {
    @Test
    fun fewColorsAreKeptExactly() {
        val red = 0xFFFF0000.toInt()
        val nearRed = 0xFFFE0101.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(red, nearRed, white, white, red, nearRed)
        val palette = GifPalette.build(pixels)
        assertNull(palette.transparentIndex)
        assertEquals(setOf(red, nearRed, white), palette.colors.toSet())
        val indices = GifPalette.map(pixels, 3, 2, palette)
        for (at in pixels.indices) assertEquals(pixels[at], palette.colors[indices[at].toInt() and 0xFF])
    }

    @Test
    fun clearPixelsUseReservedLastIndex() {
        val ink = 0xFF102030.toInt()
        val pixels = intArrayOf(0, ink, 0x00FFFFFF, ink)
        val palette = GifPalette.build(pixels)
        assertEquals(1, palette.transparentIndex)
        assertEquals(2, palette.colors.size)
        val indices = GifPalette.map(pixels, 2, 2, palette)
        assertEquals(1, indices[0].toInt())
        assertEquals(1, indices[2].toInt())
        assertEquals(ink, palette.colors[indices[1].toInt()])
    }

    @Test
    fun allClearStillHasOneEntry() {
        val palette = GifPalette.build(IntArray(4))
        assertEquals(1, palette.colors.size)
        assertEquals(0, palette.transparentIndex)
    }

    @Test
    fun gradientIsCappedAt256AndDitherKeepsAverage() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { at ->
            val x = at % width
            val y = at / width
            0xFF000000.toInt() or ((x * 2) shl 16) or ((y * 2) shl 8) or ((x + y) / 2)
        }
        val palette = GifPalette.build(pixels)
        assertEquals(256, palette.colors.size)
        assertNull(palette.transparentIndex)
        val indices = GifPalette.map(pixels, width, height, palette)
        for (shift in intArrayOf(16, 8, 0)) {
            val source = pixels.sumOf { (it ushr shift) and 0xFF }.toDouble() / pixels.size
            val mapped = indices.sumOf { (palette.colors[it.toInt() and 0xFF] ushr shift) and 0xFF }.toDouble() / pixels.size
            assertTrue("channel $shift $source vs $mapped", abs(source - mapped) < 1.0)
        }
    }

    @Test
    fun manyColorsWithClearReserve255PlusTransparent() {
        val pixels = IntArray(100 * 100) { at -> if (at % 7 == 0) 0 else 0xFF000000.toInt() or (at * 997 and 0xFFFFFF) }
        val palette = GifPalette.build(pixels)
        assertEquals(256, palette.colors.size)
        assertEquals(255, palette.transparentIndex)
        val indices = GifPalette.map(pixels, 100, 100, palette)
        for (at in pixels.indices) {
            val index = indices[at].toInt() and 0xFF
            if (pixels[at] == 0) assertEquals(255, index) else assertTrue(index < 255)
        }
    }
}
