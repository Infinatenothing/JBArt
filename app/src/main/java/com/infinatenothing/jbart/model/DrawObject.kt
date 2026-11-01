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

    data class Shape(override val tool: Tool = Tool.SHAPE) : DrawObject

    data class Fill(override val tool: Tool = Tool.FILL) : DrawObject
}
