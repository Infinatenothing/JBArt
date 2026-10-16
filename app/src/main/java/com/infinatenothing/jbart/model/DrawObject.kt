package com.infinatenothing.jbart.model

sealed interface DrawObject {
    val tool: Tool

    data class Stroke(
        val points: List<StrokePoint>,
        val color: Int,
        val baseWidthPx: Float,
        val inputType: InputType,
        override val tool: Tool = Tool.BRUSH,
    ) : DrawObject

    data class Line(override val tool: Tool = Tool.LINE) : DrawObject

    data class Shape(override val tool: Tool = Tool.SHAPE) : DrawObject

    data class Fill(override val tool: Tool = Tool.FILL) : DrawObject
}
