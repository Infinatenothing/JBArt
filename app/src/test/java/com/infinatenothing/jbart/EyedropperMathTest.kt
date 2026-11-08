package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.EyedropperMath
import org.junit.Assert.assertEquals
import org.junit.Test

class EyedropperMathTest {
    @Test
    fun mapsCenterCornersAndClampsEdges() {
        val out = FloatArray(2)
        EyedropperMath.toBitmapCoords(50f, 40f, 100, 80, 100, 80, out)
        assertEquals(50f, out[0], 0.01f)
        assertEquals(40f, out[1], 0.01f)

        EyedropperMath.toBitmapCoords(0f, 0f, 100, 80, 100, 80, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)

        EyedropperMath.toBitmapCoords(100f, 80f, 100, 80, 100, 80, out)
        assertEquals(99f, out[0], 0.01f)
        assertEquals(79f, out[1], 0.01f)

        EyedropperMath.toBitmapCoords(-20f, 500f, 100, 80, 100, 80, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(79f, out[1], 0.01f)
    }

    @Test
    fun scalesWhenViewAndBitmapSizesDiffer() {
        val out = FloatArray(2)
        EyedropperMath.toBitmapCoords(50f, 25f, 100, 50, 200, 100, out)
        assertEquals(100f, out[0], 0.01f)
        assertEquals(50f, out[1], 0.01f)
        EyedropperMath.toBitmapCoords(100f, 50f, 100, 50, 200, 100, out)
        assertEquals(199f, out[0], 0.01f)
        assertEquals(99f, out[1], 0.01f)
    }

    @Test
    fun transformMapsZoomAndLeavesOutsidePointsWhenUnclamped() {
        val out = FloatArray(2)
        EyedropperMath.toBitmapCoords(110f, 120f, 2f, 10f, 20f, 100, 80, out, clamp = true)
        assertEquals(50f, out[0], 0.01f)
        assertEquals(50f, out[1], 0.01f)

        EyedropperMath.toBitmapCoords(100f, 80f, 1f, 0f, 0f, 100, 80, out, clamp = true)
        assertEquals(99f, out[0], 0.01f)
        assertEquals(79f, out[1], 0.01f)

        EyedropperMath.toBitmapCoords(-40f, 200f, 2f, 10f, -20f, 100, 80, out, clamp = true)
        assertEquals(0f, out[0], 0f)
        assertEquals(79f, out[1], 0.01f)

        EyedropperMath.toBitmapCoords(0f, 0f, 2f, 10f, 20f, 100, 80, out, clamp = false)
        assertEquals(-5f, out[0], 0.01f)
        assertEquals(-10f, out[1], 0.01f)
    }

    @Test
    fun compositeOverCoversOpaqueTransparentAndHalfAlpha() {
        val blue = 0xFF0000FF.toInt()
        assertEquals(0xFFFF0000.toInt(), ColorMath.compositeOver(0xFFFF0000.toInt(), blue))
        assertEquals(blue, ColorMath.compositeOver(0x00FFFFFF.toInt(), blue))
        assertEquals(0xFF80007F.toInt(), ColorMath.compositeOver(0x80FF0000.toInt(), blue))
    }

    @Test
    fun sampleReadsStrokeFillErasedAndEdgePixels() {
        val background = 0xFFFFFFFF.toInt()
        val stroke = 0xFFE53935.toInt()
        val filled = 0xFF1E88E5.toInt()
        val half = 0x80FF0000.toInt()
        val pixels = intArrayOf(
            stroke, filled, 0x00000000,
            stroke, filled, half,
        )
        assertEquals(stroke, EyedropperMath.sample(pixels, 3, 2, 0, 0, background))
        assertEquals(filled, EyedropperMath.sample(pixels, 3, 2, 1, 1, background))
        assertEquals(background, EyedropperMath.sample(pixels, 3, 2, 2, 0, background))
        assertEquals(
            ColorMath.compositeOver(half, background),
            EyedropperMath.sample(pixels, 3, 2, 9, 9, background),
        )
        assertEquals(stroke, EyedropperMath.sample(pixels, 3, 2, -4, 0, background))
    }
}
