package com.infinatenothing.jbart.model

data class ColorSettings(
    val stroke: Int = BLACK,
    val fill: Int = BLACK,
    val recent: List<Int> = emptyList(),
) {
    companion object {
        const val MAX_RECENT = 8
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY_DARK = 0xFF404040.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val GRAY_LIGHT = 0xFFC0C0C0.toInt()
        const val RED = 0xFFE53935.toInt()
        const val ORANGE = 0xFFFB8C00.toInt()
        const val YELLOW = 0xFFFDD835.toInt()
        const val GREEN = 0xFF43A047.toInt()
        const val TEAL = 0xFF00897B.toInt()
        const val BLUE = 0xFF1E88E5.toInt()
        const val INDIGO = 0xFF3949AB.toInt()
        const val PURPLE = 0xFF8E24AA.toInt()
        const val PINK = 0xFFD81B60.toInt()
        const val BROWN = 0xFF6D4C41.toInt()
        const val TAN = 0xFFD2B48C.toInt()

        val PALETTE: List<Int> = listOf(
            BLACK,
            WHITE,
            GRAY_DARK,
            GRAY,
            GRAY_LIGHT,
            RED,
            ORANGE,
            YELLOW,
            GREEN,
            TEAL,
            BLUE,
            INDIGO,
            PURPLE,
            PINK,
            BROWN,
            TAN,
        )
    }
}

enum class ColorTarget {
    STROKE,
    FILL,
}

/** Forces the alpha byte to opaque. Brush opacity stays on the object, not the color. */
fun opaque(argb: Int): Int = (argb and 0x00FFFFFF) or 0xFF000000.toInt()

/**
 * Most recent first, duplicates removed, capped at [ColorSettings.MAX_RECENT].
 * The stored color is opaque.
 */
fun pushRecent(recent: List<Int>, color: Int): List<Int> {
    val next = opaque(color)
    val kept = ArrayList<Int>(ColorSettings.MAX_RECENT)
    kept.add(next)
    for (existing in recent) {
        val value = opaque(existing)
        if (value == next) continue
        if (kept.size == ColorSettings.MAX_RECENT) break
        kept.add(value)
    }
    return kept
}

/** Filled closed shapes use [fill]. Lines, outlines, and arrows (filled or not) use [stroke]. */
fun colorForShape(stroke: Int, fill: Int, kind: ShapeKind, filled: Boolean): Int {
    return if (filled && kind != ShapeKind.ARROW) opaque(fill) else opaque(stroke)
}
