package com.infinatenothing.jbart.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * HSV and source-over helpers with no Android types.
 * Hue is in [0, 360), saturation and value are in [0, 1].
 */
object ColorMath {
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        val chroma = value * sat
        val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val match = value - chroma
        val (red, green, blue) = when {
            hue < 60f -> Triple(chroma, x, 0f)
            hue < 120f -> Triple(x, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, x)
            hue < 240f -> Triple(0f, x, chroma)
            hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        return argb(red + match, green + match, blue + match)
    }

    fun argbToHsv(argb: Int, out: FloatArray) {
        require(out.size >= 3)
        val red = ((argb shr 16) and 0xFF) / 255f
        val green = ((argb shr 8) and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        val delta = maxChannel - minChannel
        var hue = when {
            delta == 0f -> 0f
            maxChannel == red -> 60f * (((green - blue) / delta) % 6f)
            maxChannel == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        if (hue >= 360f) hue = 0f
        val saturation = if (maxChannel == 0f) 0f else delta / maxChannel
        out[0] = hue
        out[1] = saturation
        out[2] = maxChannel
    }

    /**
     * Source-over of a straight (not premultiplied) ARGB [src] on an opaque [bg].
     * [bg] alpha is ignored. The result is opaque.
     * Channels use integer rounding: (src * sa + bg * (255 - sa) + 127) / 255.
     */
    fun compositeOver(src: Int, bg: Int): Int {
        val sourceAlpha = (src ushr 24) and 0xFF
        val bgRed = (bg ushr 16) and 0xFF
        val bgGreen = (bg ushr 8) and 0xFF
        val bgBlue = bg and 0xFF
        if (sourceAlpha == 0) {
            return (0xFF shl 24) or (bgRed shl 16) or (bgGreen shl 8) or bgBlue
        }
        val srcRed = (src ushr 16) and 0xFF
        val srcGreen = (src ushr 8) and 0xFF
        val srcBlue = src and 0xFF
        if (sourceAlpha == 255) {
            return (0xFF shl 24) or (srcRed shl 16) or (srcGreen shl 8) or srcBlue
        }
        val inverse = 255 - sourceAlpha
        val red = (srcRed * sourceAlpha + bgRed * inverse + 127) / 255
        val green = (srcGreen * sourceAlpha + bgGreen * inverse + 127) / 255
        val blue = (srcBlue * sourceAlpha + bgBlue * inverse + 127) / 255
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Black or white, chosen by WCAG relative luminance, for a mark on [argb]. */
    fun contrastOn(argb: Int): Int {
        val luminance = 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
        return if (luminance > 0.179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun channel(value: Int): Double {
        val scaled = value / 255.0
        return if (scaled <= 0.04045) scaled / 12.92 else ((scaled + 0.055) / 1.055).pow(2.4)
    }

    private fun argb(red: Float, green: Float, blue: Float): Int {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
