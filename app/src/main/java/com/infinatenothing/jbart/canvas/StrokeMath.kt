package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint

/** Reused geometry for one quadratic segment so live drawing does not allocate one per sample. */
class SegmentBuffer {
    var startX: Float = 0f
    var startY: Float = 0f
    var controlX: Float = 0f
    var controlY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f
    var width: Float = 0f
}

object StrokeMath {
    const val MIN_PRESSURE_FACTOR = 0.25f

    /** Maps pressure to a width factor in [MIN_PRESSURE_FACTOR, 1]. Values outside 0..1 are clamped. */
    fun pressureCurve(p: Float): Float {
        val clamped = p.coerceIn(0f, 1f)
        return MIN_PRESSURE_FACTOR + (1f - MIN_PRESSURE_FACTOR) * clamped
    }

    fun widthFor(point: StrokePoint, baseWidth: Float, inputType: InputType): Float {
        return widthForPressure(point.pressure, baseWidth, inputType)
    }

    fun widthForPressure(pressure: Float, baseWidth: Float, inputType: InputType): Float {
        return when (inputType) {
            InputType.STYLUS -> baseWidth * pressureCurve(pressure)
            InputType.FINGER, InputType.MOUSE, InputType.OTHER -> baseWidth
        }
    }

    fun midpoint(a: StrokePoint, b: StrokePoint): StrokePoint {
        return StrokePoint(
            x = (a.x + b.x) * 0.5f,
            y = (a.y + b.y) * 0.5f,
            pressure = (a.pressure + b.pressure) * 0.5f,
            timeMs = (a.timeMs + b.timeMs) / 2,
        )
    }

    /**
     * Quadratic from the previous midpoint (or [from], for the first segment)
     * through [from] to the midpoint of [from] and [to]. Width is the average
     * of the widths at those two geometric endpoints.
     */
    fun fillQuadSegment(
        previous: StrokePoint?,
        from: StrokePoint,
        to: StrokePoint,
        baseWidth: Float,
        inputType: InputType,
        out: SegmentBuffer,
    ) {
        if (previous == null) {
            out.startX = from.x
            out.startY = from.y
        } else {
            out.startX = (previous.x + from.x) * 0.5f
            out.startY = (previous.y + from.y) * 0.5f
        }
        out.controlX = from.x
        out.controlY = from.y
        out.endX = (from.x + to.x) * 0.5f
        out.endY = (from.y + to.y) * 0.5f
        val startPressure = if (previous == null) {
            from.pressure
        } else {
            (previous.pressure + from.pressure) * 0.5f
        }
        val endPressure = (from.pressure + to.pressure) * 0.5f
        out.width = interpolatedWidth(startPressure, endPressure, baseWidth, inputType)
    }

    fun interpolatedWidth(
        startPressure: Float,
        endPressure: Float,
        baseWidth: Float,
        inputType: InputType,
    ): Float {
        val start = widthForPressure(startPressure, baseWidth, inputType)
        val end = widthForPressure(endPressure, baseWidth, inputType)
        return (start + end) * 0.5f
    }
}
