package com.infinatenothing.jbart.model

/**
 * Maps a screen point into bitmap pixels. The view-size overload is the identity
 * scale used when the view and bitmap match. Zoom passes scale and translation
 * from [com.infinatenothing.jbart.canvas.ViewTransform] and does not change [sample].
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

    /**
     * Screen point through scale and translation.
     * [clamp] pulls the sample onto the nearest bitmap pixel. Fill passes false and
     * ignores a point that lands outside the canvas.
     */
    fun toBitmapCoords(
        x: Float,
        y: Float,
        scale: Float,
        translateX: Float,
        translateY: Float,
        bmpW: Int,
        bmpH: Int,
        out: FloatArray,
        clamp: Boolean,
    ) {
        require(out.size >= 2)
        val safeScale = if (scale == 0f) 1f else scale
        val canvasX = (x - translateX) / safeScale
        val canvasY = (y - translateY) / safeScale
        if (!clamp) {
            out[0] = canvasX
            out[1] = canvasY
            return
        }
        val maxX = (bmpW - 1).coerceAtLeast(0).toFloat()
        val maxY = (bmpH - 1).coerceAtLeast(0).toFloat()
        out[0] = canvasX.coerceIn(0f, maxX)
        out[1] = canvasY.coerceIn(0f, maxY)
    }

    /** Samples [pixels] in row-major order and composites the pixel over [bg]. */
    fun sample(pixels: IntArray, w: Int, h: Int, bx: Int, by: Int, bg: Int): Int {
        require(w > 0 && h > 0)
        val x = bx.coerceIn(0, w - 1)
        val y = by.coerceIn(0, h - 1)
        return ColorMath.compositeOver(pixels[y * w + x], bg)
    }
}
