package com.infinatenothing.jbart.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.infinatenothing.jbart.model.ColorMath

class ExportSource(
    val ink: Bitmap,
    val width: Int,
    val height: Int,
    val background: Int,
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

    fun compositeRow(ink: IntArray, background: Int, transparent: Boolean, out: IntArray) {
        require(out.size >= ink.size)
        for (index in ink.indices) {
            out[index] = compositePixel(ink[index], background, transparent)
        }
    }

    private const val OPAQUE = 0xFF shl 24
}
