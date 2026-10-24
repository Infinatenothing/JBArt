package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.Tool

/**
 * Pure pointer routing. Integer values match [android.view.MotionEvent] so unit tests
 * can call this without the Android framework.
 */
object InputRouting {
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4
    const val BUTTON_STYLUS_PRIMARY = 32
    const val BUTTON_STYLUS_SECONDARY = 64

    /**
     * Tool to draw with for this pointer, or null when the event should not draw.
     * The eraser end and a held stylus button erase without changing the selected tool.
     * Mouse follows the selected brush or eraser so desktop and emulator input still draw.
     */
    fun toolForEvent(selectedTool: Tool, toolType: Int, buttonState: Int): Tool? {
        if (toolType == TOOL_TYPE_ERASER) return Tool.ERASER
        val stylusButtons = BUTTON_STYLUS_PRIMARY or BUTTON_STYLUS_SECONDARY
        if (buttonState and stylusButtons != 0) return Tool.ERASER
        if (!isDrawingPointer(toolType)) return null
        return when (selectedTool) {
            Tool.BRUSH -> Tool.BRUSH
            Tool.ERASER -> Tool.ERASER
            Tool.LINE, Tool.SHAPE, Tool.FILL -> null
        }
    }

    private fun isDrawingPointer(toolType: Int): Boolean {
        return toolType == TOOL_TYPE_FINGER ||
            toolType == TOOL_TYPE_STYLUS ||
            toolType == TOOL_TYPE_MOUSE
    }
}
