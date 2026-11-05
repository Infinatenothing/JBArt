package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.FloodFill
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.HistoryLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloodFillTest {
    @Test
    fun emptyCanvasFillsEveryRow() {
        val runs = fill(blank(8, 8), 8, 8, 1, 1, BLACK, tolerance = 32, gapPx = 0, expandEdge = false)
        assertNotNull(runs)
        assertEquals(8, runs!!.size / 3)
        for (row in 0 until 8) {
            assertEquals(row, runs[row * 3])
            assertEquals(0, runs[row * 3 + 1])
            assertEquals(8, runs[row * 3 + 2])
        }
        assertTrue(runsAreOrdered(runs))
    }

    @Test
    fun closedSquareStaysInside() {
        val pixels = blank(10, 10)
        rectOutline(pixels, 10, 2, 2, 7, 7)
        val inside = fill(pixels, 10, 10, 4, 4, RED, tolerance = 0, gapPx = 0, expandEdge = false)
        val mask = mask(10, 10, inside!!)
        assertTrue(mask[4 * 10 + 4])
        assertTrue(!mask[0])
        assertTrue(!mask[5 * 10 + 2])
        for (y in 3..6) {
            for (x in 3..6) assertTrue(mask[y * 10 + x])
        }
    }

    @Test
    fun diagonalGapDoesNotLeak() {
        val pixels = blank(4, 4)
        pixels[0] = BLACK
        pixels[1 * 4 + 1] = BLACK
        pixels[2 * 4 + 2] = BLACK
        pixels[3 * 4 + 3] = BLACK
        val runs = fill(pixels, 4, 4, 0, 0, RED, tolerance = 0, gapPx = 0, expandEdge = false)
        val mask = mask(4, 4, runs!!)
        assertTrue(mask[0])
        assertTrue(!mask[1 * 4 + 1])
        assertTrue(!mask[3])
    }

    @Test
    fun toleranceZeroThirtyTwoAndFull() {
        val values = intArrayOf(0, 10, 20, 40, 80, 120, 200, 255)
        val pixels = IntArray(8) { index -> gray(values[index]) }
        val tight = mask(8, 1, fill(pixels, 8, 1, 0, 0, RED, tolerance = 0, gapPx = 0, expandEdge = false)!!)
        assertTrue(tight[0] && !tight[1])
        val mid = mask(8, 1, fill(pixels, 8, 1, 0, 0, RED, tolerance = 32, gapPx = 0, expandEdge = false)!!)
        assertTrue(mid[0] && mid[1] && mid[2] && !mid[3])
        val open = mask(8, 1, fill(pixels, 8, 1, 0, 0, RED, tolerance = 255, gapPx = 0, expandEdge = false)!!)
        assertTrue(open.all { it })
    }

    @Test
    fun compositeComparesVisibleColor() {
        val background = WHITE
        val pixels = IntArray(3)
        pixels[0] = 0
        pixels[1] = 0x80FF0000.toInt()
        val composite = ColorMath.compositeOver(pixels[1], background)
        pixels[2] = composite
        val runs = fill(pixels, 3, 1, 1, 0, RED, tolerance = 0, gapPx = 0, expandEdge = false, background = background)
        val mask = mask(3, 1, runs!!)
        assertTrue(!mask[0])
        assertTrue(mask[1])
        assertTrue(mask[2])
        val clear = fill(pixels, 3, 1, 0, 0, RED, tolerance = 0, gapPx = 0, expandEdge = false, background = background)
        val clearMask = mask(3, 1, clear!!)
        assertTrue(clearMask[0] && !clearMask[1])
    }

    @Test
    fun edgeExpansionAddsOnlyTheOrthogonalRing() {
        val pixels = blank(5, 5)
        pixels[2 * 5 + 2] = BLACK
        val plain = mask(5, 5, fill(pixels, 5, 5, 2, 2, RED, tolerance = 0, gapPx = 0, expandEdge = false)!!)
        val grown = mask(5, 5, fill(pixels, 5, 5, 2, 2, RED, tolerance = 0, gapPx = 0, expandEdge = true)!!)
        assertEquals(1, plain.count { it })
        val added = mutableListOf<Int>()
        for (i in grown.indices) {
            if (grown[i] && !plain[i]) added.add(i)
        }
        assertEquals(listOf(2 * 5 + 1, 1 * 5 + 2, 2 * 5 + 3, 3 * 5 + 2).sorted(), added.sorted())
        for (index in added) {
            val x = index % 5
            val y = index / 5
            val touches = (x > 0 && plain[index - 1]) ||
                (x + 1 < 5 && plain[index + 1]) ||
                (y > 0 && plain[index - 5]) ||
                (y + 1 < 5 && plain[index + 5])
            assertTrue(touches)
        }
    }

    @Test
    fun seedOutOfBoundsAndNoOpReturnNull() {
        assertNull(fill(blank(4, 4), 4, 4, -1, 0, RED, tolerance = 0, gapPx = 0, expandEdge = false))
        assertNull(fill(blank(4, 4), 4, 4, 0, 9, RED, tolerance = 0, gapPx = 0, expandEdge = false))
        val white = IntArray(4) { WHITE }
        var reported = false
        val none = FloodFill.floodFill(
            pixels = white,
            w = 2,
            h = 2,
            seedX = 0,
            seedY = 0,
            background = WHITE,
            fillColor = WHITE,
            tolerance = 0,
            gapPx = 0,
            expandEdge = true,
            report = { reported = true },
        )
        assertNull(none)
        assertTrue(!reported)
    }

    @Test
    fun runCapReturnsNullAndIsReported() {
        val width = 1200
        val height = 501
        val pixels = IntArray(width * height) { WHITE }
        for (x in 0 until width) pixels[x] = BLACK
        for (y in 1 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                pixels[row + x] = BLACK
                x += 4
            }
        }
        var reported = false
        val runs = FloodFill.floodFill(
            pixels = pixels,
            w = width,
            h = height,
            seedX = 0,
            seedY = 0,
            background = WHITE,
            fillColor = RED,
            tolerance = 0,
            gapPx = 0,
            expandEdge = false,
            report = { reported = true },
        )
        assertNull(runs)
        assertTrue(reported)
        assertTrue(HistoryLimits.MAX_FILL_RUNS == 150_000)
    }

    private fun fill(
        pixels: IntArray,
        w: Int,
        h: Int,
        x: Int,
        y: Int,
        color: Int,
        tolerance: Int,
        gapPx: Int,
        expandEdge: Boolean,
        background: Int = WHITE,
    ): IntArray? {
        return FloodFill.floodFill(
            pixels = pixels,
            w = w,
            h = h,
            seedX = x,
            seedY = y,
            background = background,
            fillColor = color,
            tolerance = tolerance,
            gapPx = gapPx,
            expandEdge = expandEdge,
        )
    }

    private fun blank(w: Int, h: Int) = IntArray(w * h)

    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun rectOutline(pixels: IntArray, w: Int, left: Int, top: Int, right: Int, bottom: Int) {
        for (x in left..right) {
            pixels[top * w + x] = BLACK
            pixels[bottom * w + x] = BLACK
        }
        for (y in top..bottom) {
            pixels[y * w + left] = BLACK
            pixels[y * w + right] = BLACK
        }
    }

    private fun mask(w: Int, h: Int, runs: IntArray): BooleanArray {
        val painted = BooleanArray(w * h)
        val target = IntArray(w * h)
        FloodFill.applyRuns(target, w, h, runs, 1)
        for (i in target.indices) painted[i] = target[i] == 1
        return painted
    }

    private fun runsAreOrdered(runs: IntArray): Boolean {
        var previousY = Int.MIN_VALUE
        var previousEnd = 0
        var index = 0
        while (index < runs.size) {
            val y = runs[index]
            val x = runs[index + 1]
            val length = runs[index + 2]
            if (length <= 0 || y < previousY) return false
            if (y == previousY && x < previousEnd) return false
            previousY = y
            previousEnd = x + length
            index += 3
        }
        return true
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
