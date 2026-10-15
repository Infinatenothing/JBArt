package com.infinatenothing.jbart.model

class HistoryManager {
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun push(entry: String) {
        undoStack.addLast(entry)
        redoStack.clear()
        onChanged?.invoke()
    }

    fun undo(): String? {
        if (!canUndo) return null
        val entry = undoStack.removeLast()
        redoStack.addLast(entry)
        onChanged?.invoke()
        return entry
    }

    fun redo(): String? {
        if (!canRedo) return null
        val entry = redoStack.removeLast()
        undoStack.addLast(entry)
        onChanged?.invoke()
        return entry
    }
}
