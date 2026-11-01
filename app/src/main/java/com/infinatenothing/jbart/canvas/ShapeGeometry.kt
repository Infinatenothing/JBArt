package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.ShapeKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Shape drag geometry. No Android types.
 *
 * Rectangle and ellipse share an axis-aligned box. Constrain makes that box a square
 * whose side is max(|dx|, |dy|), growing in the drag direction.
 * Triangle is upright in that box (apex on top, base on the bottom). Constrain makes it
 * equilateral: width = that side, height = side * sqrt(3) / 2, anchored at the start corner.
 * Arrow constrain reuses [LineSnap] (15 degree targets, 5 degree tolerance).
 * Head length is max(4 * width, [minHeadPx]) and at most 40% of the shaft. Half-angle is 25 degrees.
 */
object ShapeGeometry {
    const val ARROW_HEAD_WIDTH_FACTOR = 4f
    const val ARROW_HEAD_SHAFT_FRACTION = 0.4f
    const val ARROW_HEAD_HALF_ANGLE_DEG = 25f
    const val MIN_ARROW_HEAD_DP = 12f

    /** Bevels triangle corners sharper than an equilateral so a thick stroke cannot spike. */
    const val MITER_LIMIT = 2.5f

    private val equilateralHeight = sqrt(3.0).toFloat() / 2f
    private val arrowHeadTan = tan(Math.toRadians(ARROW_HEAD_HALF_ANGLE_DEG.toDouble())).toFloat()
    private val boxScratch = FloatArray(4)
    private val headScratch = FloatArray(6)

    fun constrainEnd(
        kind: ShapeKind,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        constrain: Boolean,
    ): Pair<Float, Float> {
        val out = FloatArray(2)
        constrainInto(kind, x1, y1, x2, y2, constrain, out)
        return out[0] to out[1]
    }

    /** Writes the constrained end into [out] (0 = x, 1 = y). */
    fun constrainInto(
        kind: ShapeKind,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        constrain: Boolean,
        out: FloatArray,
    ) {
        when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> squareInto(x1, y1, x2, y2, constrain, out)
            ShapeKind.TRIANGLE -> equilateralInto(x1, y1, x2, y2, constrain, out)
            ShapeKind.ARROW -> if (constrain) {
                LineSnap.snapInto(
                    x1,
                    y1,
                    x2,
                    y2,
                    LineSnap.SNAP_STEP_DEG,
                    LineSnap.SNAP_TOLERANCE_DEG,
                    true,
                    out,
                )
            } else {
                out[0] = x2
                out[1] = y2
            }
        }
    }

    fun normalizedBox(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
        val out = FloatArray(4)
        normalizedBoxInto(x1, y1, x2, y2, out)
        return out
    }

    fun normalizedBoxInto(x1: Float, y1: Float, x2: Float, y2: Float, out: FloatArray) {
        out[0] = min(x1, x2)
        out[1] = min(y1, y2)
        out[2] = max(x1, x2)
        out[3] = max(y1, y2)
    }

    /** Apex, then the two base corners. Y grows downward, so the apex is the top of the box. */
    fun trianglePoints(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
        val out = FloatArray(6)
        trianglePointsInto(x1, y1, x2, y2, out)
        return out
    }

    fun trianglePointsInto(x1: Float, y1: Float, x2: Float, y2: Float, out: FloatArray) {
        normalizedBoxInto(x1, y1, x2, y2, boxScratch)
        val left = boxScratch[0]
        val top = boxScratch[1]
        val right = boxScratch[2]
        val bottom = boxScratch[3]
        out[0] = (left + right) * 0.5f
        out[1] = top
        out[2] = left
        out[3] = bottom
        out[4] = right
        out[5] = bottom
    }

    /** Tip, then the left and right base corners of the head. */
    fun arrowHead(x1: Float, y1: Float, x2: Float, y2: Float, headLength: Float): FloatArray {
        val out = FloatArray(6)
        arrowHeadInto(x1, y1, x2, y2, headLength, out)
        return out
    }

    fun arrowHeadInto(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        headLength: Float,
        out: FloatArray,
    ) {
        out[0] = x2
        out[1] = y2
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx, dy)
        if (length == 0f || length.isNaN() || headLength <= 0f || headLength.isNaN()) {
            out[2] = x2
            out[3] = y2
            out[4] = x2
            out[5] = y2
            return
        }
        val ux = dx / length
        val uy = dy / length
        val backX = x2 - ux * headLength
        val backY = y2 - uy * headLength
        val half = headLength * arrowHeadTan
        val px = -uy
        val py = ux
        out[2] = backX + px * half
        out[3] = backY + py * half
        out[4] = backX - px * half
        out[5] = backY - py * half
    }

    fun arrowHeadLength(widthPx: Float, shaftLength: Float, minHeadPx: Float): Float {
        if (shaftLength <= 0f || shaftLength.isNaN()) return 0f
        val desired = max(ARROW_HEAD_WIDTH_FACTOR * widthPx, minHeadPx)
        val capped = min(desired, ARROW_HEAD_SHAFT_FRACTION * shaftLength)
        return if (capped.isNaN()) 0f else capped.coerceAtLeast(0f)
    }

    /** Left, top, right, bottom. Includes the arrowhead and half the stroke (more when a miter is used). */
    fun bounds(shape: DrawObject.Shape, widthPx: Float, minHeadPx: Float = 0f): FloatArray {
        val out = FloatArray(4)
        boundsInto(
            shape.kind,
            shape.x1,
            shape.y1,
            shape.x2,
            shape.y2,
            shape.filled,
            widthPx,
            minHeadPx,
            out,
        )
        return out
    }

    fun boundsInto(
        kind: ShapeKind,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        filled: Boolean,
        widthPx: Float,
        minHeadPx: Float,
        out: FloatArray,
    ) {
        var left = min(x1, x2)
        var top = min(y1, y2)
        var right = max(x1, x2)
        var bottom = max(y1, y2)
        when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> Unit
            ShapeKind.TRIANGLE -> {
                trianglePointsInto(x1, y1, x2, y2, headScratch)
                left = min(headScratch[0], min(headScratch[2], headScratch[4]))
                top = min(headScratch[1], min(headScratch[3], headScratch[5]))
                right = max(headScratch[0], max(headScratch[2], headScratch[4]))
                bottom = max(headScratch[1], max(headScratch[3], headScratch[5]))
            }
            ShapeKind.ARROW -> {
                val shaft = hypot(x2 - x1, y2 - y1)
                val head = arrowHeadLength(widthPx, shaft, minHeadPx)
                arrowHeadInto(x1, y1, x2, y2, head, headScratch)
                left = min(left, min(headScratch[0], min(headScratch[2], headScratch[4])))
                top = min(top, min(headScratch[1], min(headScratch[3], headScratch[5])))
                right = max(right, max(headScratch[0], max(headScratch[2], headScratch[4])))
                bottom = max(bottom, max(headScratch[1], max(headScratch[3], headScratch[5])))
            }
        }
        val pad = boundsPad(kind, filled, widthPx)
        out[0] = left - pad
        out[1] = top - pad
        out[2] = right + pad
        out[3] = bottom + pad
    }

    private fun boundsPad(kind: ShapeKind, filled: Boolean, widthPx: Float): Float {
        val half = widthPx.coerceAtLeast(0f) * 0.5f
        if (filled) return half
        return when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.TRIANGLE -> half * MITER_LIMIT
            ShapeKind.ELLIPSE, ShapeKind.ARROW -> half
        }
    }

    private fun squareInto(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        constrain: Boolean,
        out: FloatArray,
    ) {
        if (!constrain) {
            out[0] = x2
            out[1] = y2
            return
        }
        val dx = x2 - x1
        val dy = y2 - y1
        val side = max(abs(dx), abs(dy))
        out[0] = x1 + directed(dx, side)
        out[1] = y1 + directed(dy, side)
    }

    private fun equilateralInto(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        constrain: Boolean,
        out: FloatArray,
    ) {
        if (!constrain) {
            out[0] = x2
            out[1] = y2
            return
        }
        val dx = x2 - x1
        val dy = y2 - y1
        val side = max(abs(dx), abs(dy))
        val height = side * equilateralHeight
        out[0] = x1 + directed(dx, side, fallback = if (dy == 0f) 0f else 1f)
        out[1] = y1 + directed(dy, height, fallback = if (dx == 0f) 0f else 1f)
    }

    /** Zero delta stays put unless [fallback] supplies a direction so a one-axis drag still has area. */
    private fun directed(delta: Float, length: Float, fallback: Float = 0f): Float {
        val sign = when {
            delta > 0f -> 1f
            delta < 0f -> -1f
            else -> fallback
        }
        return sign * length
    }
}
