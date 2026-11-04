package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.colorForShape
import com.infinatenothing.jbart.model.opaque
import com.infinatenothing.jbart.model.pushRecent
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSettingsTest {
    @Test
    fun pushRecentOrdersDedupesCapsAndForcesOpaque() {
        val red = 0x80FF0000.toInt()
        val first = pushRecent(emptyList(), red)
        assertEquals(listOf(0xFFFF0000.toInt()), first)
        assertEquals(0xFFFF0000.toInt(), opaque(red))

        val blue = 0xFF0000FF.toInt()
        val green = 0xFF00FF00.toInt()
        val withBlue = pushRecent(first, blue)
        val withGreen = pushRecent(withBlue, green)
        val again = pushRecent(withGreen, 0xFFFF0000.toInt())
        assertEquals(listOf(0xFFFF0000.toInt(), green, blue), again)

        var recent = emptyList<Int>()
        val palette = List(9) { index -> 0xFF000000.toInt() or (index + 1) }
        for (color in palette) recent = pushRecent(recent, color)
        assertEquals(ColorSettings.MAX_RECENT, recent.size)
        assertEquals(palette.last(), recent.first())
        assertEquals(palette[1], recent.last())
        assertEquals(false, recent.contains(palette.first()))
    }

    @Test
    fun filledClosedShapesUseFillAndArrowsUseStroke() {
        val stroke = ColorSettings.RED
        val fill = ColorSettings.BLUE
        assertEquals(stroke, colorForShape(stroke, fill, ShapeKind.RECTANGLE, filled = false))
        assertEquals(fill, colorForShape(stroke, fill, ShapeKind.ELLIPSE, filled = true))
        assertEquals(fill, colorForShape(stroke, fill, ShapeKind.TRIANGLE, filled = true))
        assertEquals(stroke, colorForShape(stroke, fill, ShapeKind.ARROW, filled = true))
        assertEquals(16, ColorSettings.PALETTE.size)
        assertEquals(ColorSettings.PALETTE.toSet().size, ColorSettings.PALETTE.size)
    }
}