package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.DrawObject
import kotlin.math.hypot

/**
 * Pending-line edit math. No Android types.
 *
 * Endpoint drags keep the other end fixed. Snap uses [LineSnap] around that anchor.
 * Scratch arrays are reused; do not call these from more than one thread.
 */
object LineTransform {
    const val BODY_SLOP_DP = 24f

    private val snapScratch = FloatArray(2)

    /** Closer handle wins. On a tie, [LineHandle.START]. */
    fun hitLineHandle(
        line: DrawObject.Line,
        x: Float,
        y: Float,
        touchRadiusPx: Float,
    ): LineHandle? {
        if (!x.isFinite() || !y.isFinite() || touchRadiusPx < 0f) return null
        var best: LineHandle? = null
        var bestDist = touchRadiusPx
        for (handle in LineHandle.entries) {
            val hx = if (handle == LineHandle.START) line.x1 else line.x2
            val hy = if (handle == LineHandle.START) line.y1 else line.y2
            val dist = hypot(x - hx, y - hy)
            if (!dist.isFinite()) continue
            if (best == null || dist < bestDist) {
                if (dist <= touchRadiusPx) {
                    best = handle
                    bestDist = dist
                }
            }
        }
        return best
    }

    /** Distance to the segment, not the infinite line. [slopPx] is max(width / 2, 24 dp). */
    fun nearLine(line: DrawObject.Line, x: Float, y: Float, slopPx: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val slop = if (slopPx.isFinite() && slopPx > 0f) slopPx else 0f
        val dist = distanceToSegment(x, y, line.x1, line.y1, line.x2, line.y2)
        return dist.isFinite() && dist <= slop
    }

    fun movedLine(line: DrawObject.Line, dx: Float, dy: Float): DrawObject.Line {
        if (!dx.isFinite() || !dy.isFinite()) return line
        return line.copy(
            x1 = line.x1 + dx,
            y1 = line.y1 + dy,
            x2 = line.x2 + dx,
            y2 = line.y2 + dy,
        )
    }

    /**
     * Writes x1, y1, x2, y2 into [out]. The anchor endpoint is copied through.
     * Length is at least [minLenPx]. A zero-length drag keeps the old direction, or +X.
     */
    fun draggedEndpoint(
        line: DrawObject.Line,
        handle: LineHandle,
        x: Float,
        y: Float,
        snap: Boolean,
        minLenPx: Float,
        out: FloatArray,
    ) {
        require(out.size >= 4)
        if (!x.isFinite() || !y.isFinite()) {
            write(out, line.x1, line.y1, line.x2, line.y2)
            return
        }
        val anchorX = if (handle == LineHandle.END) line.x1 else line.x2
        val anchorY = if (handle == LineHandle.END) line.y1 else line.y2
        val movingX = if (handle == LineHandle.END) line.x2 else line.x1
        val movingY = if (handle == LineHandle.END) line.y2 else line.y1
        LineSnap.snapInto(
            anchorX,
            anchorY,
            x,
            y,
            LineSnap.SNAP_STEP_DEG,
            LineSnap.SNAP_TOLERANCE_DEG,
            snap,
            snapScratch,
        )
        var mx = snapScratch[0]
        var my = snapScratch[1]
        var dx = mx - anchorX
        var dy = my - anchorY
        var len = hypot(dx, dy)
        val minLen = if (minLenPx.isFinite() && minLenPx > 0f) minLenPx else 0f
        if (!len.isFinite()) {
            write(out, line.x1, line.y1, line.x2, line.y2)
            return
        }
        if (len < minLen) {
            if (len <= 1e-3f) {
                dx = movingX - anchorX
                dy = movingY - anchorY
                len = hypot(dx, dy)
                if (!len.isFinite() || len <= 1e-3f) {
                    dx = 1f
                    dy = 0f
                    len = 1f
                }
            }
            val scale = minLen / len
            mx = anchorX + dx * scale
            my = anchorY + dy * scale
        }
        if (!mx.isFinite() || !my.isFinite()) {
            write(out, line.x1, line.y1, line.x2, line.y2)
            return
        }
        if (handle == LineHandle.END) write(out, anchorX, anchorY, mx, my)
        else write(out, mx, my, anchorX, anchorY)
    }

    private fun write(out: FloatArray, x1: Float, y1: Float, x2: Float, y2: Float) {
        out[0] = x1
        out[1] = y1
        out[2] = x2
        out[3] = y2
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }
}

enum class LineHandle {
    START,
    END,
}
