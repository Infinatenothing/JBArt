package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {
    @Test
    fun hsvToArgbCoversPrimariesBlackWhiteAndGray() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorMath.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFF000000.toInt(), ColorMath.hsvToArgb(40f, 0.2f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.hsvToArgb(200f, 0f, 1f))
        assertEquals(0xFF808080.toInt(), ColorMath.hsvToArgb(0f, 0f, 0.5f))
    }

    @Test
    fun hueAt360MatchesZero() {
        assertEquals(ColorMath.hsvToArgb(0f, 1f, 1f), ColorMath.hsvToArgb(360f, 1f, 1f))
        val out = FloatArray(3)
        ColorMath.argbToHsv(0xFFFF0000.toInt(), out)
        assertEquals(0f, out[0], 0.001f)
    }

    @Test
    fun roundTripStaysWithinOneChannelStep() {
        val out = FloatArray(3)
        for (hue in 0 until 360 step 30) {
            for (saturation in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (value in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val argb = ColorMath.hsvToArgb(hue.toFloat(), saturation, value)
                    ColorMath.argbToHsv(argb, out)
                    val back = ColorMath.hsvToArgb(out[0], out[1], out[2])
                    assertChannel(argb, back, 16)
                    assertChannel(argb, back, 8)
                    assertChannel(argb, back, 0)
                }
            }
        }
    }

    @Test
    fun toHexDropsAlpha() {
        assertEquals("#FF0000", ColorMath.toHex(0x80FF0000.toInt()))
        assertEquals("#000000", ColorMath.toHex(0xFF000000.toInt()))
        assertEquals("#00FF80", ColorMath.toHex(0xFF00FF80.toInt()))
    }

    @Test
    fun contrastOnPicksBlackOrWhite() {
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastOn(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), ColorMath.contrastOn(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), ColorMath.contrastOn(0xFFFFFF00.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.contrastOn(0xFF000080.toInt()))
    }

    private fun assertChannel(left: Int, right: Int, shift: Int) {
        val delta = abs(((left shr shift) and 0xFF) - ((right shr shift) and 0xFF))
        assertTrue(delta <= 1)
    }
}
