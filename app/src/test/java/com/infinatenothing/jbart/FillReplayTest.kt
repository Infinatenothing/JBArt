package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.FloodFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillReplayTest {
    @Test
    fun applyingRunsTwiceMatchesAndClipsToASmallerBitmap() {
        val runs = FloodFill.floodFill(
            pixels = IntArray(16),
            w = 4,
            h = 4,
            seedX = 0,
            seedY = 0,
            background = WHITE,
            fillColor = RED,
            tolerance = 0,
            gapPx = 0,
            expandEdge = false,
        )!!
        val first = IntArray(16)
        val second = IntArray(16)
        FloodFill.applyRuns(first, 4, 4, runs, RED)
        FloodFill.applyRuns(first, 4, 4, runs, RED)
        FloodFill.applyRuns(second, 4, 4, runs, RED)
        assertTrue(first.contentEquals(second))
        assertEquals(RED, first[0])
        assertEquals(RED, first[15])

        val clipped = IntArray(4)
        FloodFill.applyRuns(clipped, 2, 2, runs, RED)
        assertEquals(RED, clipped[0])
        assertEquals(RED, clipped[1])
        assertEquals(RED, clipped[2])
        assertEquals(RED, clipped[3])
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
