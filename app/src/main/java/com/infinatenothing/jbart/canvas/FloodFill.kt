package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.HistoryLimits
import com.infinatenothing.jbart.model.opaque
import kotlin.math.abs
import kotlin.math.max

/**
 * Scanline flood fill on a row-major ARGB buffer. No Android types.
 *
 * A pixel matches the seed when the max per-channel difference of the visible
 * composite (`ColorMath.compositeOver` of the ink pixel over [background]) is
 * at most [tolerance] (0 to 255). The fill is 4-connected. When [gapPx] is
 * above 0, gaps are closed with [GapClose] before the scan, then the region
 * is grown back by at most [gapPx] steps through non-boundary pixels.
 * [expandEdge] then adds the 4-connected 1 px ring so antialiased edges do not
 * leave a halo. Returns null when the seed is outside the bitmap, the region
 * is empty, every pixel in the region already shows [fillColor], or the run
 * count exceeds [HistoryLimits.MAX_FILL_RUNS] (that last case calls [report]).
 */
object FloodFill {
    var log: (String) -> Unit = {}

    fun floodFill(
        pixels: IntArray,
        w: Int,
        h: Int,
        seedX: Int,
        seedY: Int,
        background: Int,
        fillColor: Int,
        tolerance: Int,
        gapPx: Int,
        expandEdge: Boolean,
        report: (String) -> Unit = {},
    ): IntArray? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        if (seedX !in 0 until w || seedY !in 0 until h) return null
        val tol = tolerance.coerceIn(0, 255)
        val radius = gapPx.coerceIn(0, 32)
        val n = w * h
        val seedIndex = seedY * w + seedX
        val seedComposite = ColorMath.compositeOver(pixels[seedIndex], background)
        val match = BooleanArray(n)
        markMatches(pixels, n, background, seedComposite, tol, match)
        if (!match[seedIndex]) return null

        val region = BooleanArray(n)
        if (radius == 0) {
            scanline(w, h, seedX, seedY, match, region)
        } else {
            val boundary = BooleanArray(n)
            for (i in 0 until n) boundary[i] = !match[i]
            val dist = IntArray(n)
            GapClose.chamfer(boundary, w, h, dist)
            val limit = radius * 3
            val open = BooleanArray(n)
            for (i in 0 until n) open[i] = dist[i] > limit
            if (open[seedIndex]) {
                scanline(w, h, seedX, seedY, open, region)
                regrow(w, h, region, match, radius)
            } else {
                val fallback = nearestOpen(w, h, seedX, seedY, radius, open)
                if (fallback == null) {
                    log("fill gap fallback seed=$seedX,$seedY radius=$radius")
                    scanline(w, h, seedX, seedY, match, region)
                } else {
                    scanline(w, h, fallback[0], fallback[1], open, region)
                    regrow(w, h, region, match, radius)
                }
            }
        }
        if (!containsDifferingPixel(pixels, region, n, background, opaque(fillColor))) return null
        if (expandEdge) dilate4(region, w, h)
        return buildRuns(region, w, h, report)
    }

    /** Paints [runs] into [target]. Runs that leave the bitmap are clipped. */
    fun applyRuns(target: IntArray, w: Int, h: Int, runs: IntArray, color: Int) {
        if (w <= 0 || h <= 0 || target.size < w * h) return
        var i = 0
        while (i + 2 < runs.size) {
            val y = runs[i]
            val x = runs[i + 1]
            val length = runs[i + 2]
            i += 3
            if (y < 0 || y >= h || length <= 0) continue
            var start = x
            var end = x + length
            if (start < 0) start = 0
            if (end > w) end = w
            if (start >= end) continue
            val row = y * w
            for (px in start until end) target[row + px] = color
        }
    }

    private fun markMatches(
        pixels: IntArray,
        n: Int,
        background: Int,
        seedComposite: Int,
        tolerance: Int,
        match: BooleanArray,
    ) {
        val seedRed = (seedComposite ushr 16) and 0xFF
        val seedGreen = (seedComposite ushr 8) and 0xFF
        val seedBlue = seedComposite and 0xFF
        for (i in 0 until n) {
            val color = ColorMath.compositeOver(pixels[i], background)
            val red = abs(((color ushr 16) and 0xFF) - seedRed)
            val green = abs(((color ushr 8) and 0xFF) - seedGreen)
            val blue = abs((color and 0xFF) - seedBlue)
            match[i] = max(red, max(green, blue)) <= tolerance
        }
    }

    /**
     * True when the region is empty, or every pixel already composites to [target]
     * (painting would not change the picture).
     */
    private fun containsDifferingPixel(
        pixels: IntArray,
        region: BooleanArray,
        n: Int,
        background: Int,
        target: Int,
    ): Boolean {
        for (i in 0 until n) {
            if (!region[i]) continue
            if (ColorMath.compositeOver(pixels[i], background) != target) return true
        }
        return false
    }

    private fun nearestOpen(
        w: Int,
        h: Int,
        seedX: Int,
        seedY: Int,
        radius: Int,
        open: BooleanArray,
    ): IntArray? {
        var best = Int.MAX_VALUE
        var foundX = -1
        var foundY = -1
        val yStart = (seedY - radius).coerceAtLeast(0)
        val yEnd = (seedY + radius).coerceAtMost(h - 1)
        val xStart = (seedX - radius).coerceAtLeast(0)
        val xEnd = (seedX + radius).coerceAtMost(w - 1)
        for (y in yStart..yEnd) {
            val dy = abs(y - seedY)
            val row = y * w
            for (x in xStart..xEnd) {
                val dist = dy + abs(x - seedX)
                if (dist == 0 || dist > radius || dist >= best) continue
                if (!open[row + x]) continue
                best = dist
                foundX = x
                foundY = y
            }
        }
        if (foundX < 0) return null
        return intArrayOf(foundX, foundY)
    }

    private fun scanline(
        w: Int,
        h: Int,
        seedX: Int,
        seedY: Int,
        allowed: BooleanArray,
        region: BooleanArray,
    ) {
        val stack = SpanStack()
        if (!allowed[seedY * w + seedX]) return
        stack.push(seedX, seedY)
        while (stack.size > 0) {
            val x0 = stack.xs[stack.size - 1]
            val y0 = stack.ys[stack.size - 1]
            stack.size--
            val row = y0 * w
            if (region[row + x0] || !allowed[row + x0]) continue
            var left = x0
            while (left > 0 && allowed[row + left - 1] && !region[row + left - 1]) left--
            var right = x0
            while (right + 1 < w && allowed[row + right + 1] && !region[row + right + 1]) right++
            for (x in left..right) region[row + x] = true
            if (y0 > 0) pushSpans(w, left, right, y0 - 1, allowed, region, stack)
            if (y0 + 1 < h) pushSpans(w, left, right, y0 + 1, allowed, region, stack)
        }
    }

    private fun pushSpans(
        w: Int,
        left: Int,
        right: Int,
        y: Int,
        allowed: BooleanArray,
        region: BooleanArray,
        stack: SpanStack,
    ) {
        val row = y * w
        var x = left
        while (x <= right) {
            while (x <= right && (region[row + x] || !allowed[row + x])) x++
            if (x > right) break
            stack.push(x, y)
            while (x <= right && !region[row + x] && allowed[row + x]) x++
        }
    }

    private fun regrow(
        w: Int,
        h: Int,
        region: BooleanArray,
        match: BooleanArray,
        radius: Int,
    ) {
        val n = w * h
        val steps = ByteArray(n) { -1 }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) {
            if (!region[i]) continue
            steps[i] = 0
            queue[tail++] = i
        }
        while (head < tail) {
            val index = queue[head++]
            val distance = steps[index].toInt()
            if (distance >= radius) continue
            val x = index % w
            val y = index / w
            val next = (distance + 1).toByte()
            if (x > 0) offer(index - 1, next, match, steps, region, queue, tail).also { tail = it }
            if (x + 1 < w) offer(index + 1, next, match, steps, region, queue, tail).also { tail = it }
            if (y > 0) offer(index - w, next, match, steps, region, queue, tail).also { tail = it }
            if (y + 1 < h) offer(index + w, next, match, steps, region, queue, tail).also { tail = it }
        }
    }

    private fun offer(
        index: Int,
        next: Byte,
        match: BooleanArray,
        steps: ByteArray,
        region: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (steps[index] >= 0 || !match[index]) return tail
        steps[index] = next
        region[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun dilate4(region: BooleanArray, w: Int, h: Int) {
        val extra = BooleanArray(region.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!region[row + x]) continue
                if (x > 0) extra[row + x - 1] = true
                if (x + 1 < w) extra[row + x + 1] = true
                if (y > 0) extra[row - w + x] = true
                if (y + 1 < h) extra[row + w + x] = true
            }
        }
        for (i in region.indices) {
            if (extra[i]) region[i] = true
        }
    }

    private fun buildRuns(
        region: BooleanArray,
        w: Int,
        h: Int,
        report: (String) -> Unit,
    ): IntArray? {
        var count = 0
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (!region[row + x]) {
                    x++
                    continue
                }
                while (x < w && region[row + x]) x++
                count++
                if (count > HistoryLimits.MAX_FILL_RUNS) {
                    report("fill abort runs>$count")
                    log("fill abort runs=$count")
                    return null
                }
            }
        }
        if (count == 0) return null
        val runs = IntArray(count * 3)
        var cursor = 0
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (!region[row + x]) {
                    x++
                    continue
                }
                val start = x
                while (x < w && region[row + x]) x++
                runs[cursor++] = y
                runs[cursor++] = start
                runs[cursor++] = x - start
            }
        }
        return runs
    }

    private class SpanStack {
        var xs = IntArray(256)
        var ys = IntArray(256)
        var size = 0

        fun push(x: Int, y: Int) {
            if (size == xs.size) {
                val grown = xs.size * 2
                xs = xs.copyOf(grown)
                ys = ys.copyOf(grown)
            }
            xs[size] = x
            ys[size] = y
            size++
        }
    }
}
