package com.infinatenothing.jbart.model

/**
 * Caps a canvas so two ARGB_8888 bitmaps cannot exhaust a tablet heap.
 * [MAX_EDGE] is the long side. [MAX_PIXELS] is 16 megapixels.
 */
object CanvasLimits {
    const val MAX_EDGE = 4096
    const val MAX_PIXELS = 16_000_000
    const val MODE_LEGACY = 0
    const val MODE_FIXED = 1

    data class Preset(val width: Int, val height: Int, val screen: Boolean = false)

    val PRESETS = listOf(
        Preset(0, 0, screen = true),
        Preset(2048, 1536),
        Preset(2560, 1600),
        Preset(2048, 2048),
    )

    fun withinCap(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val longEdge = if (width > height) width else height
        if (longEdge > MAX_EDGE) return false
        return width.toLong() * height <= MAX_PIXELS
    }

    /** [memoryClassMb] is [android.app.ActivityManager.memoryClass]. Two bitmaps must fit in half of it. */
    fun offered(width: Int, height: Int, memoryClassMb: Int): Boolean {
        if (!withinCap(width, height)) return false
        val halfBytes = memoryClassMb.toLong() * 1024L * 1024L / 2L
        return width.toLong() * height * 8L <= halfBytes
    }
}

/** Objects drawn after the last [DrawObject.Clear]. Earlier marks stay in the list for undo. */
fun visibleObjects(objects: List<DrawObject>): List<DrawObject> {
    val start = objects.indexOfLast { it is DrawObject.Clear } + 1
    return if (start == 0) objects else objects.subList(start, objects.size)
}
