package com.infinatenothing.jbart.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.DrawObject

/**
 * Raster export: [ink] is a full copy. Vector export: [objects] are the visible objects and
 * [ink] is the replay through the last erase, or null when there is no erase.
 */
class ExportSource(
    val ink: Bitmap?,
    val width: Int,
    val height: Int,
    val background: Int,
    val objects: List<DrawObject> = emptyList(),
)

object ExportRenderer {
    fun render(ink: Bitmap, width: Int, height: Int, background: Int, transparent: Boolean): Bitmap {
        require(width > 0 && height > 0)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (!transparent) canvas.drawColor(background or OPAQUE)
        canvas.drawBitmap(ink, 0f, 0f, null)
        return out
    }

    /** One straight-ARGB pixel. Transparent keeps [src], including alpha. Otherwise source-over [background]. */
    fun compositePixel(src: Int, background: Int, transparent: Boolean): Int {
        if (transparent) return src
        return ColorMath.compositeOver(src, background)
    }

    /** GIF alpha is on or off: alpha-0 stays clear, anything else is composited over [background]. */
    fun gifPixel(src: Int, background: Int): Int {
        if (src ushr 24 == 0) return 0
        return ColorMath.compositeOver(src, background)
    }

    /** Applies [gifPixel] to a transparent render in place, one row at a time. */
    fun flattenForGif(bitmap: Bitmap, background: Int) {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) row[x] = gifPixel(row[x], background)
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
    }

    fun compositeRow(ink: IntArray, background: Int, transparent: Boolean, out: IntArray) {
        require(out.size >= ink.size)
        for (index in ink.indices) {
            out[index] = compositePixel(ink[index], background, transparent)
        }
    }

    private const val OPAQUE = 0xFF shl 24
}
