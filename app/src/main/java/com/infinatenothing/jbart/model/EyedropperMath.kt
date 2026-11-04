package com.infinatenothing.jbart.model

/**
 * Maps a view point into bitmap pixels. Today this is a uniform scale, which is
 * identity when the view and bitmap are the same size. A later zoom can replace
 * the scale with a matrix without changing the sampler.
 */
object EyedropperMath {
    fun toBitmapCoords(
        x: Float,
        y: Float,
        viewW: Int,
        viewH: Int,
        bmpW: Int,
        bmpH: Int,
        out: FloatArray,
    ) {
        require(out.size >= 2)
        val scaledX = if (viewW <= 0 || bmpW <= 0) 0f else x * bmpW.toFloat() / viewW.toFloat()
        val scaledY = if (viewH <= 0 || bmpH <= 0) 0f else y * bmpH.toFloat() / viewH.toFloat()
        val maxX = (bmpW - 1).coerceAtLeast(0).toFloat()
        val maxY = (bmpH - 1).coerceAtLeast(0).toFloat()
        out[0] = scaledX.coerceIn(0f, maxX)
        out[1] = scaledY.coerceIn(0f, maxY)
    }

    /** Samples [pixels] in row-major order and composites the pixel over [bg]. */
    fun sample(pixels: IntArray, w: Int, h: Int, bx: Int, by: Int, bg: Int): Int {
        require(w > 0 && h > 0)
        val x = bx.coerceIn(0, w - 1)
        val y = by.coerceIn(0, h - 1)
        return ColorMath.compositeOver(pixels[y * w + x], bg)
    }
}
