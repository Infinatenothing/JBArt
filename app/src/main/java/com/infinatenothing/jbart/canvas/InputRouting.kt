package com.infinatenothing.jbart.canvas

import com.infinatenothing.jbart.model.Tool

/**
 * Pure pointer routing. Integer values match [android.view.MotionEvent] so unit tests
 * can call this without the Android framework.
 */
enum class GestureMode {
    DRAW,
    NAVIGATE,
    IGNORE,
}

object InputRouting {
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4
    const val BUTTON_STYLUS_PRIMARY = 32
    const val BUTTON_STYLUS_SECONDARY = 64

    /**
     * Draw or pinch. A stylus always draws. Two fingers navigate only when no stylus is already down.
     */
    fun modeForPointer(
        toolType: Int,
        pointerCount: Int,
        stylusDown: Boolean,
    ): GestureMode {
        val stylus = toolType == TOOL_TYPE_STYLUS || toolType == TOOL_TYPE_ERASER
        if (stylus) return GestureMode.DRAW
        if (stylusDown) return GestureMode.IGNORE
        if (toolType == TOOL_TYPE_MOUSE) {
            return if (pointerCount >= 2) GestureMode.IGNORE else GestureMode.DRAW
        }
        if (toolType != TOOL_TYPE_FINGER) return GestureMode.IGNORE
        if (pointerCount >= 2) return GestureMode.NAVIGATE
        return GestureMode.DRAW
    }

    /**
     * Tool to draw with for this pointer, or null when the event should not draw.
     * The eraser end and a held stylus button erase without changing the selected tool.
     * Mouse follows the selected brush, eraser, line, shape, or fill so desktop and emulator input still draw.
     */
    fun toolForEvent(selectedTool: Tool, toolType: Int, buttonState: Int): Tool? {
        if (toolType == TOOL_TYPE_ERASER) return Tool.ERASER
        val stylusButtons = BUTTON_STYLUS_PRIMARY or BUTTON_STYLUS_SECONDARY
        if (buttonState and stylusButtons != 0) return Tool.ERASER
        if (!isDrawingPointer(toolType)) return null
        return when (selectedTool) {
            Tool.BRUSH -> Tool.BRUSH
            Tool.ERASER -> Tool.ERASER
            Tool.LINE -> Tool.LINE
            Tool.SHAPE -> Tool.SHAPE
            Tool.FILL -> Tool.FILL
        }
    }

    private fun isDrawingPointer(toolType: Int): Boolean {
        return toolType == TOOL_TYPE_FINGER ||
            toolType == TOOL_TYPE_STYLUS ||
            toolType == TOOL_TYPE_MOUSE
    }
}
