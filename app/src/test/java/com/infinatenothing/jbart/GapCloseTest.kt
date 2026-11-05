package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.FloodFill
import com.infinatenothing.jbart.canvas.GapClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GapCloseTest {
    @Test
    fun chamferMatchesAKnownInteriorMask() {
        val size = 21
        val boundary = BooleanArray(size * size)
        val center = 10
        boundary[center * size + center] = true
        val dist = IntArray(size * size)
        GapClose.chamfer(boundary, size, size, dist)
        assertEquals(0, dist[center * size + center])
        assertEquals(3, dist[center * size + (center - 1)])
        assertEquals(4, dist[(center - 1) * size + (center - 1)])
        assertEquals(6, dist[center * size + (center - 2)])
        assertEquals(7, dist[(center - 1) * size + (center - 2)])
        assertEquals(8, dist[(center - 2) * size + (center - 2)])
    }

    @Test
    fun smallCircleGapIsClosedAtRadiusTwoAndOpenAtZero() {
        val closed = circleMask(gap = 2, radius = 2)
        assertTrue(closed[16 * 32 + 16])
        assertTrue(!closed[16 * 32 + 28])
        val leaked = circleMask(gap = 2, radius = 0)
        assertTrue(leaked[16 * 32 + 28])
    }

    @Test
    fun gapWiderThanTwiceTheRadiusLeaks() {
        val leaked = circleMask(gap = 6, radius = 2)
        assertTrue(leaked[16 * 32 + 28])
    }

    @Test
    fun closedGapBulgeStaysWithinTheRadius() {
        val pixels = blank(24, 24)
        for (y in 0 until 24) pixels[y * 24 + 12] = BLACK
        for (y in 10..11) pixels[y * 24 + 12] = 0
        val mask = paint(pixels, 24, 24, 4, 12, radius = 2)
        val mouth = listOf(10 to 12, 11 to 12)
        for (y in 0 until 24) {
            for (x in 13 until 24) {
                if (!mask[y * 24 + x]) continue
                val near = mouth.any { (my, mx) -> abs(x - mx) + abs(y - my) <= 2 }
                assertTrue("pixel $x,$y", near)
            }
        }
    }

    @Test
    fun parallelLinesLeaveTheChannelUnfilled() {
        val pixels = blank(30, 30)
        for (x in 0 until 30) {
            pixels[12 * 30 + x] = BLACK
            pixels[16 * 30 + x] = BLACK
        }
        val mask = paint(pixels, 30, 30, 15, 5, radius = 2)
        assertTrue(mask[5 * 30 + 15])
        assertTrue(!mask[14 * 30 + 15])
        assertTrue(!mask[13 * 30 + 15])
        assertTrue(!mask[15 * 30 + 15])
        val wall = blank(20, 20)
        for (y in 0 until 20) wall[y * 20 + 8] = BLACK
        val blocked = paint(wall, 20, 20, 2, 10, radius = 2, expandEdge = false)
        assertTrue(blocked[10 * 20 + 2])
        assertTrue(!blocked[10 * 20 + 8])
        assertTrue(!blocked[10 * 20 + 14])
    }

    @Test
    fun regrowReachesTheInnerEdgeAndSkipsBoundaryPixels() {
        val pixels = blank(20, 20)
        rect(pixels, 20, 4, 4, 15, 15)
        val grown = paint(pixels, 20, 20, 9, 9, radius = 2, expandEdge = false)
        val plain = paint(pixels, 20, 20, 9, 9, radius = 0, expandEdge = false)
        for (i in grown.indices) {
            if (pixels[i] == BLACK) assertTrue(!grown[i])
        }
        for (x in 7..12) {
            assertEquals(plain[5 * 20 + x], grown[5 * 20 + x])
            assertTrue(grown[5 * 20 + x])
            assertEquals(plain[14 * 20 + x], grown[14 * 20 + x])
        }
        for (y in 7..12) {
            assertEquals(plain[y * 20 + 5], grown[y * 20 + 5])
            assertTrue(grown[y * 20 + 5])
        }
    }

    @Test
    fun canvasEdgeClosesANarrowMouthAndLeavesAWideMouthOpen() {
        val narrow = uShape(armTop = 1)
        assertTrue(narrow[12 * 20 + 9])
        assertTrue(!narrow[8 * 20 + 1])
        val wide = uShape(armTop = 10)
        assertTrue(wide[2 * 20 + 1])
    }

    @Test
    fun seedInTheDilatedBandFallsBackToTheOpenSide() {
        val pixels = blank(20, 16)
        for (y in 0 until 16) pixels[y * 20 + 10] = BLACK
        val mask = paint(pixels, 20, 16, 9, 8, radius = 2, expandEdge = false)
        assertTrue(mask[8 * 20 + 4])
        assertTrue(!mask[8 * 20 + 15])
    }

    @Test
    fun largeBitmapFinishesWithinTheJvmBudget() {
        val width = 2000
        val height = 2000
        val pixels = IntArray(width * height)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                pixels[y * width + x] = BLACK
                x += 50
            }
            y += 50
        }
        for (radius in intArrayOf(0, 2, 10)) {
            val started = System.nanoTime()
            val runs = FloodFill.floodFill(
                pixels = pixels,
                w = width,
                h = height,
                seedX = 1,
                seedY = 1,
                background = WHITE,
                fillColor = RED,
                tolerance = 32,
                gapPx = radius,
                expandEdge = radius == 0,
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            println("flood ${width}x$height gap=$radius ${elapsedMs}ms runs=${runs?.size?.div(3)}")
            assertNotNull(runs)
            assertTrue("gap $radius took ${elapsedMs}ms", elapsedMs < 2_000)
        }
    }

    private fun circleMask(gap: Int, radius: Int): BooleanArray {
        val size = 32
        val pixels = blank(size, size)
        val center = 16
        val ring = 10
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val distance = dx * dx + dy * dy
                if (distance <= ring * ring && distance >= (ring - 1) * (ring - 1)) {
                    pixels[y * size + x] = BLACK
                }
            }
        }
        val band = (gap / 2).coerceAtLeast(0)
        for (y in (center - band)..(center + band)) {
            for (x in center until size) {
                val index = y * size + x
                if (pixels[index] == BLACK) pixels[index] = 0
            }
        }
        return paint(pixels, size, size, center, center, radius, expandEdge = false)
    }

    private fun uShape(armTop: Int): BooleanArray {
        val pixels = blank(20, 16)
        for (y in armTop..14) {
            pixels[y * 20 + 4] = BLACK
            pixels[y * 20 + 14] = BLACK
        }
        for (x in 4..14) pixels[14 * 20 + x] = BLACK
        return paint(pixels, 20, 16, 9, 12, radius = 2, expandEdge = false)
    }

    private fun paint(
        pixels: IntArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        radius: Int,
        expandEdge: Boolean = false,
    ): BooleanArray {
        val runs = FloodFill.floodFill(
            pixels = pixels,
            w = w,
            h = h,
            seedX = x,
            seedY = y,
            background = WHITE,
            fillColor = RED,
            tolerance = 0,
            gapPx = radius,
            expandEdge = expandEdge,
        )
        assertNotNull(runs)
        val target = IntArray(w * h)
        FloodFill.applyRuns(target, w, h, runs!!, 1)
        return BooleanArray(target.size) { target[it] == 1 }
    }

    private fun blank(w: Int, h: Int) = IntArray(w * h)

    private fun rect(pixels: IntArray, w: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (x in left..right) {
            pixels[top * w + x] = BLACK
            pixels[bottom * w + x] = BLACK
        }
        for (y in top..bottom) {
            pixels[y * w + left] = BLACK
            pixels[y * w + right] = BLACK
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
