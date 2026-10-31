package com.infinatenothing.jbart.canvas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Angle snap for the line tool. No Android types.
 *
 * [SNAP_STEP_DEG] is 15, so targets include 0, 45, 90, and 135.
 * [SNAP_STEP_ALT_DEG] (45) is the coarser alternative: pass it as `stepDeg`.
 * A raw angle snaps only when it is within [SNAP_TOLERANCE_DEG] of a target.
 * Length is kept: the end is the same distance from the start along the snapped direction.
 */
object LineSnap {
    const val SNAP_STEP_DEG = 15f

    /** Coarser alternative to [SNAP_STEP_DEG]. Pass as `stepDeg`. */
    const val SNAP_STEP_ALT_DEG = 45f
    const val SNAP_TOLERANCE_DEG = 5f

    fun angleDeg(startX: Float, startY: Float, endX: Float, endY: Float): Float {
        val dx = endX - startX
        val dy = endY - startY
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    fun snapEnd(
        startX: Float,
        startY: Float,
        rawX: Float,
        rawY: Float,
        stepDeg: Float,
        toleranceDeg: Float,
        enabled: Boolean = true,
    ): Pair<Float, Float> {
        val out = FloatArray(2)
        snapInto(startX, startY, rawX, rawY, stepDeg, toleranceDeg, enabled, out)
        return out[0] to out[1]
    }

    /**
     * Writes the end point into [out] (0 = x, 1 = y).
     * Returns true when the angle was pulled onto a snap target.
     */
    fun snapInto(
        startX: Float,
        startY: Float,
        rawX: Float,
        rawY: Float,
        stepDeg: Float,
        toleranceDeg: Float,
        enabled: Boolean,
        out: FloatArray,
    ): Boolean {
        val dx = rawX - startX
        val dy = rawY - startY
        val length = hypot(dx.toDouble(), dy.toDouble())
        if (length == 0.0 || length.isNaN()) {
            out[0] = startX
            out[1] = startY
            return false
        }
        if (!enabled || stepDeg <= 0f || toleranceDeg < 0f) {
            out[0] = rawX
            out[1] = rawY
            return false
        }
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val nearest = round(angle / stepDeg.toDouble()) * stepDeg.toDouble()
        if (abs(wrapDegrees(angle - nearest)) > toleranceDeg.toDouble()) {
            out[0] = rawX
            out[1] = rawY
            return false
        }
        val rad = Math.toRadians(nearest)
        out[0] = (startX.toDouble() + cos(rad) * length).toFloat()
        out[1] = (startY.toDouble() + sin(rad) * length).toFloat()
        return true
    }

    private fun wrapDegrees(delta: Double): Double {
        var wrapped = delta % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped <= -180.0) wrapped += 360.0
        return wrapped
    }
}
