package com.infinatenothing.jbart.canvas

import kotlin.math.min

/**
 * Morphological closing for flood-fill gaps, via a 3-4 chamfer distance transform.
 * An orthogonal step costs 3 and a diagonal step costs 4, so a pixel is within
 * [radius] bitmap pixels of a boundary when its distance is at most `3 * radius`.
 * Pixels outside the bitmap count as boundary: the canvas edge is a wall for
 * gap closing. A gap wider than about `2 * radius` stays open.
 */
object GapClose {
    private const val INF = 1 shl 28

    /**
     * Writes the chamfer distance from each pixel to the nearest boundary into [dist].
     * [boundary] is true for stroke (or other) pixels that block the fill.
     * [dist] must already be `width * height` long.
     */
    fun chamfer(boundary: BooleanArray, width: Int, height: Int, dist: IntArray) {
        val rowStride = width
        for (i in dist.indices) {
            dist[i] = if (boundary[i]) 0 else INF
        }
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val i = row + x
                var value = dist[i]
                if (y > 0) {
                    value = min(value, dist[i - rowStride] + 3)
                    if (x > 0) value = min(value, dist[i - rowStride - 1] + 4) else value = min(value, 4)
                    if (x + 1 < width) value = min(value, dist[i - rowStride + 1] + 4) else value = min(value, 4)
                } else {
                    value = min(value, 3)
                    value = min(value, 4)
                }
                if (x > 0) value = min(value, dist[i - 1] + 3) else value = min(value, 3)
                dist[i] = value
            }
        }
        for (y in height - 1 downTo 0) {
            val row = y * rowStride
            for (x in width - 1 downTo 0) {
                val i = row + x
                var value = dist[i]
                if (y + 1 < height) {
                    value = min(value, dist[i + rowStride] + 3)
                    if (x + 1 < width) value = min(value, dist[i + rowStride + 1] + 4) else value = min(value, 4)
                    if (x > 0) value = min(value, dist[i + rowStride - 1] + 4) else value = min(value, 4)
                } else {
                    value = min(value, 3)
                    value = min(value, 4)
                }
                if (x + 1 < width) value = min(value, dist[i + 1] + 3) else value = min(value, 3)
                dist[i] = value
            }
        }
    }
}
