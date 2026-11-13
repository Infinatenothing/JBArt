package com.infinatenothing.jbart.export

import java.util.BitSet

/**
 * GIF palette: at most 256 colors by median cut, then Floyd-Steinberg mapping to indices.
 * Pixels are straight ARGB. Alpha-0 pixels map to a reserved transparent index; every
 * other pixel is treated as opaque (callers composite partial alpha first).
 */
object GifPalette {
    /** [colors] are opaque 0xFFRRGGBB. [transparentIndex] is the last entry when present. */
    class Palette(val colors: IntArray, val transparentIndex: Int?)

    fun build(pixels: IntArray): Palette {
        val count = IntArray(BINS)
        val red = LongArray(BINS)
        val green = LongArray(BINS)
        val blue = LongArray(BINS)
        var anyClear = false
        for (pixel in pixels) {
            if (pixel ushr 24 == 0) {
                anyClear = true
                continue
            }
            val bin = binOf(pixel)
            count[bin]++
            red[bin] += ((pixel ushr 16) and 0xFF).toLong()
            green[bin] += ((pixel ushr 8) and 0xFF).toLong()
            blue[bin] += (pixel and 0xFF).toLong()
        }
        val maxColors = if (anyClear) MAX_COLORS - 1 else MAX_COLORS
        val opaque = exactColors(pixels, maxColors)
            ?: medianCut(count, red, green, blue, maxColors)
        if (!anyClear) return Palette(if (opaque.isEmpty()) intArrayOf(OPAQUE) else opaque, null)
        return Palette(opaque + 0, opaque.size)
    }

    fun map(pixels: IntArray, width: Int, height: Int, palette: Palette): ByteArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val colors = palette.colors
        val clear = palette.transparentIndex
        val opaqueCount = if (clear == null) colors.size else colors.size - 1
        val bySorted = (0 until opaqueCount).sortedBy { colors[it] and RGB }.toIntArray()
        val sortedRgb = IntArray(opaqueCount) { colors[bySorted[it]] and RGB }
        val cache = IntArray(1 shl 18) { -1 }
        val out = ByteArray(pixels.size)
        // Errors are stored times 16 so the 7/3/5/1 weights stay integer.
        var current = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            next.fill(0)
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                if (clear != null && pixel ushr 24 == 0) {
                    out[index] = clear.toByte()
                    continue
                }
                val e = (x + 1) * 3
                val r = clamp(((pixel ushr 16) and 0xFF) + current[e] / 16)
                val g = clamp(((pixel ushr 8) and 0xFF) + current[e + 1] / 16)
                val b = clamp((pixel and 0xFF) + current[e + 2] / 16)
                val rgb = (r shl 16) or (g shl 8) or b
                val exact = sortedRgb.binarySearch(rgb)
                val chosen = if (exact >= 0) {
                    bySorted[exact]
                } else {
                    val key = ((r ushr 2) shl 12) or ((g ushr 2) shl 6) or (b ushr 2)
                    if (cache[key] < 0) {
                        cache[key] = nearest(colors, opaqueCount, (r and 0xFC) + 2, (g and 0xFC) + 2, (b and 0xFC) + 2)
                    }
                    cache[key]
                }
                out[index] = chosen.toByte()
                val color = colors[chosen]
                spread(r - ((color ushr 16) and 0xFF), e, current, next)
                spread(g - ((color ushr 8) and 0xFF), e + 1, current, next)
                spread(b - (color and 0xFF), e + 2, current, next)
            }
            val swap = current
            current = next
            next = swap
        }
        return out
    }

    private fun spread(error: Int, at: Int, current: IntArray, next: IntArray) {
        current[at + 3] += error * 7
        next[at - 3] += error * 3
        next[at] += error * 5
        next[at + 3] += error
    }

    private fun nearest(colors: IntArray, count: Int, r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (index in 0 until count) {
            val color = colors[index]
            val dr = ((color ushr 16) and 0xFF) - r
            val dg = ((color ushr 8) and 0xFF) - g
            val db = (color and 0xFF) - b
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** All distinct opaque colors when there are at most [maxColors], else null. */
    private fun exactColors(pixels: IntArray, maxColors: Int): IntArray? {
        val seen = BitSet(1 shl 24)
        val found = IntArray(maxColors)
        var size = 0
        for (pixel in pixels) {
            if (pixel ushr 24 == 0) continue
            val rgb = pixel and RGB
            if (seen.get(rgb)) continue
            if (size == maxColors) return null
            seen.set(rgb)
            found[size++] = OPAQUE or rgb
        }
        return found.copyOf(size)
    }

    private fun medianCut(count: IntArray, red: LongArray, green: LongArray, blue: LongArray, maxColors: Int): IntArray {
        val order = (0 until BINS).filter { count[it] > 0 }.toIntArray()
        val starts = IntArray(maxColors)
        val ends = IntArray(maxColors)
        val population = LongArray(maxColors)
        var boxes = 1
        ends[0] = order.size
        population[0] = order.sumOf { count[it].toLong() }
        while (boxes < maxColors) {
            var pick = -1
            for (box in 0 until boxes) {
                if (ends[box] - starts[box] > 1 && (pick < 0 || population[box] > population[pick])) pick = box
            }
            if (pick < 0) break
            val start = starts[pick]
            val end = ends[pick]
            sortByWidestChannel(order, start, end)
            val half = population[pick] / 2
            var running = 0L
            var split = start + 1
            for (at in start until end - 1) {
                running += count[order[at]]
                split = at + 1
                if (running >= half) break
            }
            val left = (start until split).sumOf { count[order[it]].toLong() }
            starts[boxes] = split
            ends[boxes] = end
            population[boxes] = population[pick] - left
            ends[pick] = split
            population[pick] = left
            boxes++
        }
        return IntArray(boxes) { box ->
            var n = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (at in starts[box] until ends[box]) {
                val bin = order[at]
                n += count[bin]
                r += red[bin]
                g += green[bin]
                b += blue[bin]
            }
            OPAQUE or (((r + n / 2) / n).toInt() shl 16) or (((g + n / 2) / n).toInt() shl 8) or ((b + n / 2) / n).toInt()
        }
    }

    /** Counting sort of order[start, end) by whichever 5-bit channel spans the widest range. */
    private fun sortByWidestChannel(order: IntArray, start: Int, end: Int) {
        var shift = 0
        var widest = -1
        for (candidate in intArrayOf(10, 5, 0)) {
            var low = 31
            var high = 0
            for (at in start until end) {
                val value = (order[at] ushr candidate) and 31
                if (value < low) low = value
                if (value > high) high = value
            }
            if (high - low > widest) {
                widest = high - low
                shift = candidate
            }
        }
        val buckets = IntArray(33)
        for (at in start until end) buckets[((order[at] ushr shift) and 31) + 1]++
        for (value in 1..32) buckets[value] += buckets[value - 1]
        val sorted = IntArray(end - start)
        for (at in start until end) {
            val bin = order[at]
            sorted[buckets[(bin ushr shift) and 31]++] = bin
        }
        sorted.copyInto(order, start)
    }

    private fun binOf(pixel: Int): Int {
        return (((pixel ushr 19) and 31) shl 10) or (((pixel ushr 11) and 31) shl 5) or ((pixel ushr 3) and 31)
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)

    private const val BINS = 1 shl 15
    private const val MAX_COLORS = 256
    private const val RGB = 0xFFFFFF
    private const val OPAQUE = 0xFF shl 24
}
