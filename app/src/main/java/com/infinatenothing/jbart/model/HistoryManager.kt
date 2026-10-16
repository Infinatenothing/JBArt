package com.infinatenothing.jbart.model

class HistoryManager {
    private val undoStack = ArrayDeque<DrawObject>()
    private val redoStack = ArrayDeque<DrawObject>()

    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun push(entry: DrawObject) {
        undoStack.addLast(entry)
        redoStack.clear()
        onChanged?.invoke()
    }

    fun undo(): DrawObject? {
        if (!canUndo) return null
        val entry = undoStack.removeLast()
        redoStack.addLast(entry)
        onChanged?.invoke()
        return entry
    }

    fun redo(): DrawObject? {
        if (!canRedo) return null
        val entry = redoStack.removeLast()
        undoStack.addLast(entry)
        onChanged?.invoke()
        return entry
    }
}
