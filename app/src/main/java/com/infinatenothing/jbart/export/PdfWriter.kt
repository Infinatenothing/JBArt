package com.infinatenothing.jbart.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.infinatenothing.jbart.canvas.ShapeGeometry
import java.io.OutputStream

/** One-page PDF; 1 canvas pixel = 1 point. The page always has the background (decision 7). */
object PdfWriter {
    fun write(
        ops: List<VectorOp>,
        width: Int,
        height: Int,
        background: Int,
        flattened: Bitmap?,
        out: OutputStream,
    ) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            val canvas = page.canvas
            canvas.drawColor(background or OPAQUE)
            draw(canvas, ops, flattened)
            document.finishPage(page)
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private fun draw(canvas: Canvas, ops: List<VectorOp>, flattened: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeMiter = ShapeGeometry.MITER_LIMIT
        }
        val path = Path()
        for (op in ops) {
            when (op) {
                is VectorOp.Flattened -> if (flattened != null) canvas.drawBitmap(flattened, 0f, 0f, null)
                is VectorOp.FillImage -> {
                    val bitmap = fillBitmap(op)
                    try {
                        canvas.drawBitmap(bitmap, op.left.toFloat(), op.top.toFloat(), null)
                    } finally {
                        bitmap.recycle()
                    }
                }
                is VectorOp.Group -> drawGroup(canvas, op, paint, path)
            }
        }
    }

    private fun drawGroup(canvas: Canvas, op: VectorOp.Group, paint: Paint, path: Path) {
        val save = if (op.opacity < 1f) {
            canvas.saveLayerAlpha(null, alphaByte(op.opacity))
        } else {
            canvas.save()
        }
        if (op.rotationDeg != 0f) canvas.rotate(op.rotationDeg, op.pivotX, op.pivotY)
        paint.color = op.rgb or OPAQUE
        for (mark in op.marks) {
            paint.style = if (mark.filled) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = mark.width
            paint.strokeJoin = if (mark.roundJoin) Paint.Join.ROUND else Paint.Join.MITER
            when (val geometry = mark.geometry) {
                is Geometry.Oval -> canvas.drawOval(geometry.left, geometry.top, geometry.right, geometry.bottom, paint)
                is Geometry.Quad -> {
                    path.rewind()
                    path.moveTo(geometry.startX, geometry.startY)
                    path.quadTo(geometry.controlX, geometry.controlY, geometry.endX, geometry.endY)
                    canvas.drawPath(path, paint)
                }
                is Geometry.Polyline -> {
                    path.rewind()
                    path.moveTo(geometry.xy[0], geometry.xy[1])
                    for (i in 2 until geometry.xy.size step 2) path.lineTo(geometry.xy[i], geometry.xy[i + 1])
                    if (geometry.closed) path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    /** Caller recycles. */
    fun fillBitmap(op: VectorOp.FillImage): Bitmap {
        return Bitmap.createBitmap(op.pixels(), op.width, op.height, Bitmap.Config.ARGB_8888)
    }

    private const val OPAQUE = 0xFF shl 24
}
