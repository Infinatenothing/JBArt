package com.infinatenothing.jbart.model

sealed interface DrawObject {
    val tool: Tool

    data class Stroke(
        val points: List<StrokePoint>,
        val color: Int,
        val baseWidthPx: Float,
        val inputType: InputType,
        val alpha: Float = 1f,
        override val tool: Tool = Tool.BRUSH,
    ) : DrawObject

    data class EraseStroke(
        val points: List<StrokePoint>,
        val baseWidthPx: Float,
        val inputType: InputType,
        override val tool: Tool = Tool.ERASER,
    ) : DrawObject

    /**
     * Straight segment. [widthPx] is constant (no pressure). Caps are always round.
     * Endpoints are the committed ones, already snapped when snap was on.
     */
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: Int,
        val alpha: Float,
        val widthPx: Float,
        val inputType: InputType,
        override val tool: Tool = Tool.LINE,
    ) : DrawObject

    /**
     * Axis-aligned shape, or an arrow along the drag.
     * [x1], [y1] is the drag start. [x2], [y2] is the committed end, already constrained.
     * The two points stay an unrotated box. [rotationDeg] is clockwise about that box's center,
     * in screen coordinates. Zero matches a shape drawn before pending edit.
     * [widthPx] is constant (no pressure). Closed shapes are either filled or stroked, not both.
     */
    data class Shape(
        val kind: ShapeKind,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val filled: Boolean,
        val color: Int,
        val alpha: Float,
        val widthPx: Float,
        val inputType: InputType,
        val rotationDeg: Float = 0f,
        override val tool: Tool = Tool.SHAPE,
    ) : DrawObject

    /**
     * One flood fill. [runs] is the region at the time of the tap, as triples
     * (y, xStart, length), rows ascending and runs within a row non-overlapping.
     * Replay paints [runs] and does not look at [gapPx]. [width] and [height] are
     * the ink bitmap size when the fill was made.
     */
    data class Fill(
        val seedX: Int,
        val seedY: Int,
        val color: Int,
        val alpha: Float,
        val tolerance: Int,
        val gapPx: Int,
        val width: Int,
        val height: Int,
        val runs: IntArray,
        override val tool: Tool = Tool.FILL,
    ) : DrawObject {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fill) return false
            return seedX == other.seedX &&
                seedY == other.seedY &&
                color == other.color &&
                alpha == other.alpha &&
                tolerance == other.tolerance &&
                gapPx == other.gapPx &&
                width == other.width &&
                height == other.height &&
                tool == other.tool &&
                runs.contentEquals(other.runs)
        }

        override fun hashCode(): Int {
            var result = seedX
            result = 31 * result + seedY
            result = 31 * result + color
            result = 31 * result + alpha.hashCode()
            result = 31 * result + tolerance
            result = 31 * result + gapPx
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + tool.hashCode()
            result = 31 * result + runs.contentHashCode()
            return result
        }
    }

    /** Blank slate for replay. Size and background stay on the document. */
    data object Clear : DrawObject {
        override val tool: Tool = Tool.BRUSH
    }
}
