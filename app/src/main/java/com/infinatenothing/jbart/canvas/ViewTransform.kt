package com.infinatenothing.jbart.canvas

import kotlin.math.roundToInt

/**
 * Canvas-to-screen transform: screen = scale * canvas + translate.
 * No rotation and no skew. Identity (scale 1, translate 0) matches an unzoomed view.
 */
class ViewTransform(
    var scale: Float = 1f,
    var tx: Float = 0f,
    var ty: Float = 0f,
) {
    fun toCanvas(sx: Float, sy: Float, out: FloatArray) {
        require(out.size >= 2)
        val s = if (scale == 0f) 1f else scale
        out[0] = (sx - tx) / s
        out[1] = (sy - ty) / s
    }

    fun toScreen(cx: Float, cy: Float, out: FloatArray) {
        require(out.size >= 2)
        out[0] = cx * scale + tx
        out[1] = cy * scale + ty
    }

    /** Multiplies scale by [factor], keeping the canvas point under the focus fixed. */
    fun zoomAround(focusX: Float, focusY: Float, factor: Float) {
        pinch(focusX, focusY, focusX, focusY, factor)
    }

    /**
     * Pinch and pan together. The canvas point that was under [prevFocusX], [prevFocusY]
     * moves to [focusX], [focusY], and scale multiplies by [factor].
     */
    fun pinch(
        prevFocusX: Float,
        prevFocusY: Float,
        focusX: Float,
        focusY: Float,
        factor: Float,
    ) {
        if (factor <= 0f || scale <= 0f) return
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val applied = next / scale
        tx = focusX - applied * (prevFocusX - tx)
        ty = focusY - applied * (prevFocusY - ty)
        scale = next
    }

    fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
    }

    /** Fits the whole canvas in the view, centered, then leaves clamping to the caller. */
    fun fit(canvasW: Float, canvasH: Float, viewW: Float, viewH: Float, marginPx: Float) {
        if (canvasW <= 0f || canvasH <= 0f || viewW <= 0f || viewH <= 0f) return
        val availW = (viewW - 2f * marginPx).coerceAtLeast(1f)
        val availH = (viewH - 2f * marginPx).coerceAtLeast(1f)
        scale = minOf(availW / canvasW, availH / canvasH).coerceIn(MIN_SCALE, MAX_SCALE)
        tx = (viewW - canvasW * scale) / 2f
        ty = (viewH - canvasH * scale) / 2f
    }

    /** Places the canvas point at the center of the view. */
    fun centerOn(canvasX: Float, canvasY: Float, viewW: Float, viewH: Float) {
        tx = viewW / 2f - canvasX * scale
        ty = viewH / 2f - canvasY * scale
    }

    fun centerX(viewW: Float): Float = (viewW / 2f - tx) / scale

    fun centerY(viewH: Float): Float = (viewH / 2f - ty) / scale

    /**
     * Keeps at least 25 percent of the canvas on screen, or [minVisiblePx], whichever is larger.
     * The required length is capped by the canvas and by the view.
     */
    fun clamp(canvasW: Float, canvasH: Float, viewW: Float, viewH: Float, minVisiblePx: Float) {
        if (canvasW <= 0f || canvasH <= 0f || viewW <= 0f || viewH <= 0f || scale <= 0f) return
        tx = clampAxis(tx, canvasW * scale, viewW, minVisiblePx)
        ty = clampAxis(ty, canvasH * scale, viewH, minVisiblePx)
    }

    fun zoomPercent(): Int = (scale * 100f).roundToInt()

    private fun clampAxis(translate: Float, screenSpan: Float, viewSpan: Float, minVisiblePx: Float): Float {
        val quarter = 0.25f * screenSpan
        val keepRaw = if (quarter > minVisiblePx) quarter else minVisiblePx
        val cap = if (screenSpan < viewSpan) screenSpan else viewSpan
        val keep = if (keepRaw < cap) keepRaw else cap
        val minT = keep - screenSpan
        val maxT = viewSpan - keep
        if (minT > maxT) return (viewSpan - screenSpan) / 2f
        return translate.coerceIn(minT, maxT)
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 8f
        const val STEP = 1.25f
        const val FIT_MARGIN_DP = 16f
        const val MIN_VISIBLE_DP = 48f
        const val CRISP_SCALE = 2f
    }
}
