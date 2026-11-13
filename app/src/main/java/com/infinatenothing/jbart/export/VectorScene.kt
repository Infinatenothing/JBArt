package com.infinatenothing.jbart.export

import com.infinatenothing.jbart.canvas.SegmentBuffer
import com.infinatenothing.jbart.canvas.ShapeGeometry
import com.infinatenothing.jbart.canvas.StrokeMath
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.ShapeKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry in canvas pixels. No Android types. */
sealed interface Geometry {
    /** Straight segments through x,y pairs. */
    class Polyline(val xy: FloatArray, val closed: Boolean) : Geometry

    class Quad(
        val startX: Float,
        val startY: Float,
        val controlX: Float,
        val controlY: Float,
        val endX: Float,
        val endY: Float,
    ) : Geometry

    class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float) : Geometry
}

/** Stroked marks use round caps. [roundJoin] false means miter with [ShapeGeometry.MITER_LIMIT]. */
class Mark(val geometry: Geometry, val filled: Boolean, val width: Float, val roundJoin: Boolean = true)

sealed interface VectorOp {
    /**
     * One brush stroke, line, or shape: all marks share [rgb] and are composited as a group at
     * [opacity], the same as the scratch blit on screen. [rotationDeg] is clockwise about the pivot.
     */
    class Group(
        val rgb: Int,
        val opacity: Float,
        val rotationDeg: Float,
        val pivotX: Float,
        val pivotY: Float,
        val marks: List<Mark>,
    ) : VectorOp

    /** One flood fill, cropped to its runs. Pixels are made on demand so fills are not all held at once. */
    class FillImage(
        val fill: DrawObject.Fill,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        private val canvasWidth: Int,
    ) : VectorOp {
        /** Straight ARGB, transparent outside the runs. */
        fun pixels(): IntArray {
            val out = IntArray(width * height)
            val argb = (alphaByte(fill.alpha) shl 24) or (fill.color and 0xFFFFFF)
            forEachRun(fill, canvasWidth, top + height) { y, start, end ->
                val row = (y - top) * width - left
                for (x in start until end) out[row + x] = argb
            }
            return out
        }
    }

    /** Everything up to and including the last erase, as one canvas-size raster the caller supplies. */
    data object Flattened : VectorOp
}

/**
 * Builds export ops from the visible objects. Strokes, lines, and shapes stay vector.
 * Fills become cropped images. Each erase would flatten everything before it, so only the
 * last one matters: all objects through it become one [VectorOp.Flattened] raster.
 */
object VectorScene {
    fun flattenCount(objects: List<DrawObject>): Int = objects.indexOfLast { it is DrawObject.EraseStroke } + 1

    fun build(objects: List<DrawObject>, width: Int, height: Int, minArrowHeadPx: Float): List<VectorOp> {
        val start = flattenCount(objects)
        val ops = ArrayList<VectorOp>()
        if (start > 0) ops.add(VectorOp.Flattened)
        for (index in start until objects.size) {
            val op = when (val obj = objects[index]) {
                is DrawObject.Stroke -> stroke(obj)
                is DrawObject.Line -> line(obj)
                is DrawObject.Shape -> shape(obj, minArrowHeadPx)
                is DrawObject.Fill -> fill(obj, width, height)
                is DrawObject.EraseStroke, DrawObject.Clear -> null
            }
            if (op != null) ops.add(op)
        }
        return ops
    }

    fun stroke(obj: DrawObject.Stroke): VectorOp.Group? {
        val points = obj.points
        if (points.isEmpty()) return null
        val marks = ArrayList<Mark>(points.size + 1)
        if (points.size == 1) {
            val point = points[0]
            val radius = StrokeMath.widthFor(point, obj.baseWidthPx, obj.inputType) * 0.5f
            marks.add(Mark(Geometry.Oval(point.x - radius, point.y - radius, point.x + radius, point.y + radius), true, 0f))
        } else {
            val segment = SegmentBuffer()
            for (i in 1 until points.size) {
                val previous = if (i >= 2) points[i - 2] else null
                StrokeMath.fillQuadSegment(previous, points[i - 1], points[i], obj.baseWidthPx, obj.inputType, segment)
                val quad = Geometry.Quad(
                    segment.startX,
                    segment.startY,
                    segment.controlX,
                    segment.controlY,
                    segment.endX,
                    segment.endY,
                )
                marks.add(Mark(quad, false, segment.width))
            }
            // Same tail as DrawingView.drawTailOn: last midpoint to the last point.
            val from = points[points.lastIndex - 1]
            val to = points.last()
            val tailWidth = StrokeMath.interpolatedWidth(
                (from.pressure + to.pressure) * 0.5f,
                to.pressure,
                obj.baseWidthPx,
                obj.inputType,
            )
            val tail = floatArrayOf((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f, to.x, to.y)
            marks.add(Mark(Geometry.Polyline(tail, false), false, tailWidth))
        }
        return group(obj.color, obj.alpha, 0f, 0f, 0f, marks)
    }

    fun line(obj: DrawObject.Line): VectorOp.Group? {
        if (obj.x1 == obj.x2 && obj.y1 == obj.y2) return null
        val mark = Mark(Geometry.Polyline(floatArrayOf(obj.x1, obj.y1, obj.x2, obj.y2), false), false, obj.widthPx)
        return group(obj.color, obj.alpha, 0f, 0f, 0f, listOf(mark))
    }

    /** Mirrors DrawingView.drawShapeOn and drawShapeRotated. */
    fun shape(obj: DrawObject.Shape, minArrowHeadPx: Float): VectorOp.Group? {
        if (obj.x1 == obj.x2 && obj.y1 == obj.y2) return null
        val box = ShapeGeometry.normalizedBox(obj.x1, obj.y1, obj.x2, obj.y2)
        val width = obj.widthPx
        val marks = ArrayList<Mark>(2)
        when (obj.kind) {
            ShapeKind.RECTANGLE -> {
                val corners = floatArrayOf(box[0], box[1], box[2], box[1], box[2], box[3], box[0], box[3])
                marks.add(Mark(Geometry.Polyline(corners, true), obj.filled, width, roundJoin = false))
            }
            ShapeKind.ELLIPSE -> marks.add(Mark(Geometry.Oval(box[0], box[1], box[2], box[3]), obj.filled, width))
            ShapeKind.TRIANGLE -> {
                val points = ShapeGeometry.trianglePoints(obj.x1, obj.y1, obj.x2, obj.y2)
                marks.add(Mark(Geometry.Polyline(points, true), obj.filled, width, roundJoin = false))
            }
            ShapeKind.ARROW -> {
                marks.add(Mark(Geometry.Polyline(floatArrayOf(obj.x1, obj.y1, obj.x2, obj.y2), false), false, width))
                val shaft = hypot(obj.x2 - obj.x1, obj.y2 - obj.y1)
                val headLength = ShapeGeometry.arrowHeadLength(width, shaft, minArrowHeadPx)
                if (headLength > 0f) {
                    val head = ShapeGeometry.arrowHead(obj.x1, obj.y1, obj.x2, obj.y2, headLength)
                    val xy = floatArrayOf(head[2], head[3], head[0], head[1], head[4], head[5])
                    marks.add(Mark(Geometry.Polyline(xy, obj.filled), obj.filled, width))
                }
            }
        }
        val pivotX = (box[0] + box[2]) * 0.5f
        val pivotY = (box[1] + box[3]) * 0.5f
        return group(obj.color, obj.alpha, obj.rotationDeg, pivotX, pivotY, marks)
    }

    fun fill(obj: DrawObject.Fill, width: Int, height: Int): VectorOp.FillImage? {
        if (alphaByte(obj.alpha) == 0) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        forEachRun(obj, width, height) { y, start, end ->
            left = min(left, start)
            right = max(right, end)
            top = min(top, y)
            bottom = max(bottom, y + 1)
        }
        if (left >= right) return null
        return VectorOp.FillImage(obj, left, top, right - left, bottom - top, width)
    }

    private fun group(color: Int, alpha: Float, rotationDeg: Float, pivotX: Float, pivotY: Float, marks: List<Mark>): VectorOp.Group? {
        val opacity = alphaByte(alpha) / 255f * ((color ushr 24) / 255f)
        if (opacity <= 0f || marks.isEmpty()) return null
        return VectorOp.Group(color and 0xFFFFFF, opacity, rotationDeg, pivotX, pivotY, marks)
    }
}

/** Same rounding as DrawingView.alphaByte. */
internal fun alphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

/** Runs clipped the same way as DrawingView.stampFill. [end] is exclusive. */
private inline fun forEachRun(fill: DrawObject.Fill, width: Int, height: Int, action: (y: Int, start: Int, end: Int) -> Unit) {
    val runs = fill.runs
    var index = 0
    while (index + 2 < runs.size) {
        val y = runs[index]
        val x = runs[index + 1]
        val length = runs[index + 2]
        index += 3
        if (y < 0 || y >= height || length <= 0) continue
        val start = max(x, 0)
        val end = min(x + length, width)
        if (start < end) action(y, start, end)
    }
}
