package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.DrawObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pending-shape edit math. No Android types.
 *
 * The stored box stays unrotated, same as [ShapeGeometry]. [DrawObject.Shape.rotationDeg]
 * is clockwise about the box center in screen coordinates (Y grows downward).
 * Resize keeps the opposite corner or edge fixed in that rotated frame.
 * Constrain keeps the box aspect ratio and applies to corner handles.
 * Scratch arrays are reused; do not call these from more than one thread.
 */
object ShapeTransform {
    const val ROTATE_OFFSET_DP = 24f
    const val HANDLE_TOUCH_DP = 24f
    const val MIN_SIDE_DP = 4f

    private val boxScratch = FloatArray(4)
    private val pointScratch = FloatArray(2)
    private val handleScratch = FloatArray(18)
    private val cornerScratch = FloatArray(8)
    private val snapScratch = FloatArray(2)

    /**
     * Eight resize handles, then the rotate handle, in [Handle] order.
     * [rotateOffsetPx] is how far the rotate handle sits above the top edge.
     */
    fun handlePositions(shape: DrawObject.Shape, rotateOffsetPx: Float, out: FloatArray) {
        require(out.size >= 18)
        writeLocalHandles(shape, rotateOffsetPx, out)
        pivot(shape, pointScratch)
        rotateAll(out, 9, pointScratch[0], pointScratch[1], shape.rotationDeg)
    }

    fun hitHandle(
        shape: DrawObject.Shape,
        x: Float,
        y: Float,
        touchRadiusPx: Float,
        rotateOffsetPx: Float,
    ): Handle? {
        if (!x.isFinite() || !y.isFinite() || touchRadiusPx < 0f) return null
        pivot(shape, pointScratch)
        val cx = pointScratch[0]
        val cy = pointScratch[1]
        inverseRotateInto(x, y, cx, cy, shape.rotationDeg, pointScratch)
        val localX = pointScratch[0]
        val localY = pointScratch[1]
        writeLocalHandles(shape, rotateOffsetPx, handleScratch)
        var best: Handle? = null
        var bestDist = touchRadiusPx
        for (handle in Handle.entries) {
            val hx = handleScratch[handle.ordinal * 2]
            val hy = handleScratch[handle.ordinal * 2 + 1]
            val dist = hypot(localX - hx, localY - hy)
            if (dist <= bestDist) {
                best = handle
                bestDist = dist
            }
        }
        return best
    }

    /** True when the point is inside the rotated box, expanded by [slopPx]. */
    fun insideBox(shape: DrawObject.Shape, x: Float, y: Float, slopPx: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        pivot(shape, pointScratch)
        inverseRotateInto(x, y, pointScratch[0], pointScratch[1], shape.rotationDeg, pointScratch)
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, boxScratch)
        val slop = if (slopPx.isFinite() && slopPx > 0f) slopPx else 0f
        val localX = pointScratch[0]
        val localY = pointScratch[1]
        return localX >= boxScratch[0] - slop &&
            localX <= boxScratch[2] + slop &&
            localY >= boxScratch[1] - slop &&
            localY <= boxScratch[3] + slop
    }

    fun moved(shape: DrawObject.Shape, dx: Float, dy: Float): DrawObject.Shape {
        if (!dx.isFinite() || !dy.isFinite()) return shape
        return shape.copy(
            x1 = shape.x1 + dx,
            y1 = shape.y1 + dy,
            x2 = shape.x2 + dx,
            y2 = shape.y2 + dy,
        )
    }

    fun resized(
        shape: DrawObject.Shape,
        handle: Handle,
        x: Float,
        y: Float,
        constrain: Boolean,
        minSidePx: Float = MIN_SIDE_DP,
    ): DrawObject.Shape {
        if (handle == Handle.ROTATE) return shape
        if (!x.isFinite() || !y.isFinite()) return shape
        val minSide = if (minSidePx.isFinite() && minSidePx > 0f) minSidePx else 0f
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, boxScratch)
        val left = boxScratch[0]
        val top = boxScratch[1]
        val right = boxScratch[2]
        val bottom = boxScratch[3]
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        inverseRotateInto(x, y, cx, cy, shape.rotationDeg, pointScratch)
        val localX = pointScratch[0]
        val localY = pointScratch[1]
        if (!localX.isFinite() || !localY.isFinite()) return shape

        val moveLeft = handle == Handle.LEFT || handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
        val moveRight = handle == Handle.RIGHT || handle == Handle.TOP_RIGHT || handle == Handle.BOTTOM_RIGHT
        val moveTop = handle == Handle.TOP || handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT
        val moveBottom = handle == Handle.BOTTOM || handle == Handle.BOTTOM_LEFT || handle == Handle.BOTTOM_RIGHT

        var newLeft = left
        var newTop = top
        var newRight = right
        var newBottom = bottom
        if (moveLeft) newLeft = localX
        if (moveRight) newRight = localX
        if (moveTop) newTop = localY
        if (moveBottom) newBottom = localY

        val corner = (moveLeft || moveRight) && (moveTop || moveBottom)
        val baseW = abs(right - left)
        val baseH = abs(bottom - top)
        if (constrain && corner && baseW > 0f && baseH > 0f) {
            val ratio = baseW / baseH
            val anchorX = if (moveLeft) right else left
            val anchorY = if (moveTop) bottom else top
            val signX = nonzeroSign(
                (if (moveLeft) newLeft else newRight) - anchorX,
                if (moveLeft) left - right else right - left,
            )
            val signY = nonzeroSign(
                (if (moveTop) newTop else newBottom) - anchorY,
                if (moveTop) top - bottom else bottom - top,
            )
            var adx = abs((if (moveLeft) newLeft else newRight) - anchorX)
            var ady = abs((if (moveTop) newTop else newBottom) - anchorY)
            if (ady * ratio > adx) adx = ady * ratio else ady = adx / ratio
            if (adx < minSide) {
                adx = minSide
                ady = adx / ratio
            }
            if (ady < minSide) {
                ady = minSide
                adx = ady * ratio
            }
            if (moveLeft) newLeft = anchorX + signX * adx else newRight = anchorX + signX * adx
            if (moveTop) newTop = anchorY + signY * ady else newBottom = anchorY + signY * ady
        } else {
            if (moveLeft || moveRight) {
                val anchorX = if (moveLeft) right else left
                val moving = if (moveLeft) newLeft else newRight
                val original = if (moveLeft) left else right
                val fixed = pushMin(moving, anchorX, minSide, original)
                if (moveLeft) newLeft = fixed else newRight = fixed
            }
            if (moveTop || moveBottom) {
                val anchorY = if (moveTop) bottom else top
                val moving = if (moveTop) newTop else newBottom
                val original = if (moveTop) top else bottom
                val fixed = pushMin(moving, anchorY, minSide, original)
                if (moveTop) newTop = fixed else newBottom = fixed
            }
        }

        val newCxLocal = (newLeft + newRight) * 0.5f
        val newCyLocal = (newTop + newBottom) * 0.5f
        if (!newCxLocal.isFinite() || !newCyLocal.isFinite()) return shape
        rotateAroundInto(newCxLocal, newCyLocal, cx, cy, shape.rotationDeg, pointScratch)
        val worldCx = pointScratch[0]
        val worldCy = pointScratch[1]
        if (!worldCx.isFinite() || !worldCy.isFinite()) return shape
        val x1 = place(if (shape.x1 <= shape.x2) newLeft else newRight, newCxLocal, worldCx)
        val x2 = place(if (shape.x1 <= shape.x2) newRight else newLeft, newCxLocal, worldCx)
        val y1 = place(if (shape.y1 <= shape.y2) newTop else newBottom, newCyLocal, worldCy)
        val y2 = place(if (shape.y1 <= shape.y2) newBottom else newTop, newCyLocal, worldCy)
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return shape
        return shape.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2)
    }

    /** Sets the rotation so the top of the box points at the touch. Snap uses [LineSnap]. */
    fun rotated(shape: DrawObject.Shape, x: Float, y: Float, snap: Boolean): DrawObject.Shape {
        if (!x.isFinite() || !y.isFinite()) return shape
        pivot(shape, pointScratch)
        val cx = pointScratch[0]
        val cy = pointScratch[1]
        if (x == cx && y == cy) return shape
        LineSnap.snapInto(
            cx,
            cy,
            x,
            y,
            LineSnap.SNAP_STEP_DEG,
            LineSnap.SNAP_TOLERANCE_DEG,
            snap,
            snapScratch,
        )
        val deg = normalizeDeg(angleClockwiseFromUp(cx, cy, snapScratch[0], snapScratch[1]))
        if (!deg.isFinite()) return shape
        return shape.copy(rotationDeg = deg)
    }

    fun rotatedBounds(
        shape: DrawObject.Shape,
        widthPx: Float,
        rotateOffsetPx: Float,
        minHeadPx: Float = 0f,
        handleRadiusPx: Float = 0f,
    ): FloatArray {
        val out = FloatArray(4)
        rotatedBoundsInto(shape, widthPx, rotateOffsetPx, minHeadPx, handleRadiusPx, out)
        return out
    }

    /** Axis-aligned dirty rect of the rotated shape, its stroke, arrowhead, and handles. */
    fun rotatedBoundsInto(
        shape: DrawObject.Shape,
        widthPx: Float,
        rotateOffsetPx: Float,
        minHeadPx: Float,
        handleRadiusPx: Float,
        out: FloatArray,
    ) {
        ShapeGeometry.boundsInto(
            shape.kind,
            shape.x1,
            shape.y1,
            shape.x2,
            shape.y2,
            shape.filled,
            widthPx,
            minHeadPx,
            boxScratch,
        )
        cornerScratch[0] = boxScratch[0]
        cornerScratch[1] = boxScratch[1]
        cornerScratch[2] = boxScratch[2]
        cornerScratch[3] = boxScratch[1]
        cornerScratch[4] = boxScratch[2]
        cornerScratch[5] = boxScratch[3]
        cornerScratch[6] = boxScratch[0]
        cornerScratch[7] = boxScratch[3]
        pivot(shape, pointScratch)
        val cx = pointScratch[0]
        val cy = pointScratch[1]
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            rotateAroundInto(cornerScratch[i * 2], cornerScratch[i * 2 + 1], cx, cy, shape.rotationDeg, pointScratch)
            minX = minOf(minX, pointScratch[0])
            minY = minOf(minY, pointScratch[1])
            maxX = maxOf(maxX, pointScratch[0])
            maxY = maxOf(maxY, pointScratch[1])
        }
        handlePositions(shape, rotateOffsetPx, handleScratch)
        for (i in 0 until 9) {
            minX = minOf(minX, handleScratch[i * 2])
            minY = minOf(minY, handleScratch[i * 2 + 1])
            maxX = maxOf(maxX, handleScratch[i * 2])
            maxY = maxOf(maxY, handleScratch[i * 2 + 1])
        }
        val pad = if (handleRadiusPx.isFinite() && handleRadiusPx > 0f) handleRadiusPx else 0f
        out[0] = minX - pad
        out[1] = minY - pad
        out[2] = maxX + pad
        out[3] = maxY + pad
    }

    private fun writeLocalHandles(shape: DrawObject.Shape, rotateOffsetPx: Float, out: FloatArray) {
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, boxScratch)
        val left = boxScratch[0]
        val top = boxScratch[1]
        val right = boxScratch[2]
        val bottom = boxScratch[3]
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        out[0] = left
        out[1] = top
        out[2] = cx
        out[3] = top
        out[4] = right
        out[5] = top
        out[6] = right
        out[7] = cy
        out[8] = right
        out[9] = bottom
        out[10] = cx
        out[11] = bottom
        out[12] = left
        out[13] = bottom
        out[14] = left
        out[15] = cy
        out[16] = cx
        out[17] = top - rotateOffsetPx
    }

    private fun pivot(shape: DrawObject.Shape, out: FloatArray) {
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, boxScratch)
        out[0] = (boxScratch[0] + boxScratch[2]) * 0.5f
        out[1] = (boxScratch[1] + boxScratch[3]) * 0.5f
    }

    private fun rotateAll(points: FloatArray, count: Int, cx: Float, cy: Float, deg: Float) {
        if (deg == 0f) return
        for (i in 0 until count) {
            rotateAroundInto(points[i * 2], points[i * 2 + 1], cx, cy, deg, pointScratch)
            points[i * 2] = pointScratch[0]
            points[i * 2 + 1] = pointScratch[1]
        }
    }

    private fun rotateAroundInto(px: Float, py: Float, cx: Float, cy: Float, deg: Float, out: FloatArray) {
        if (deg == 0f || !deg.isFinite()) {
            out[0] = px
            out[1] = py
            return
        }
        val rad = Math.toRadians(deg.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        val dx = px - cx
        val dy = py - cy
        out[0] = cx + dx * cos - dy * sin
        out[1] = cy + dx * sin + dy * cos
    }

    private fun inverseRotateInto(px: Float, py: Float, cx: Float, cy: Float, deg: Float, out: FloatArray) {
        if (deg == 0f || !deg.isFinite()) {
            out[0] = px
            out[1] = py
            return
        }
        val rad = Math.toRadians(deg.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        val dx = px - cx
        val dy = py - cy
        out[0] = cx + dx * cos + dy * sin
        out[1] = cy - dx * sin + dy * cos
    }

    private fun angleClockwiseFromUp(cx: Float, cy: Float, x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    }

    private fun normalizeDeg(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        var wrapped = deg % 360f
        if (wrapped < 0f) wrapped += 360f
        if (wrapped == 360f) wrapped = 0f
        return wrapped
    }

    private fun pushMin(moving: Float, anchor: Float, min: Float, originalMoving: Float): Float {
        if (min <= 0f) return moving
        val delta = moving - anchor
        if (abs(delta) >= min && delta != 0f) return moving
        val sign = nonzeroSign(delta, originalMoving - anchor)
        return anchor + sign * min
    }

    private fun nonzeroSign(delta: Float, fallback: Float): Float {
        return when {
            delta > 0f -> 1f
            delta < 0f -> -1f
            fallback > 0f -> 1f
            fallback < 0f -> -1f
            else -> 1f
        }
    }

    private fun place(local: Float, localCenter: Float, worldCenter: Float): Float {
        return worldCenter + (local - localCenter)
    }
}

enum class Handle {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT,
    ROTATE,
}
