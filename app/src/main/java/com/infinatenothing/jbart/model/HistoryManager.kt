package com.infinatenothing.jbart.model

class HistoryManager(
    private val maxEntries: Int = HistoryLimits.MAX_ENTRIES,
    private val maxPoints: Int = HistoryLimits.MAX_POINTS,
) {
    private val undoStack = ArrayDeque<DrawObject>()
    private val redoStack = ArrayDeque<DrawObject>()

    var onChanged: (() -> Unit)? = null
    var onTrimmed: ((droppedEntries: Int) -> Unit)? = null

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    val undoCount: Int
        get() = undoStack.size

    val redoCount: Int
        get() = redoStack.size

    fun push(entry: DrawObject) {
        undoStack.addLast(entry)
        redoStack.clear()
        trimUndo()
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

    fun redoEntries(): List<DrawObject> = redoStack.toList()

    /** Replaces both stacks, then applies the same caps as [push]. */
    fun replace(undo: List<DrawObject>, redo: List<DrawObject>) {
        undoStack.clear()
        undoStack.addAll(undo)
        redoStack.clear()
        redoStack.addAll(redo)
        trimUndo()
        onChanged?.invoke()
    }

    private fun trimUndo() {
        var dropped = 0
        while (undoStack.isNotEmpty() && (undoStack.size > maxEntries || pointCount() > maxPoints)) {
            // Undo steps only. Callers keep the visible drawing.
            undoStack.removeFirst()
            dropped++
        }
        if (dropped > 0) onTrimmed?.invoke(dropped)
    }

    private fun pointCount(): Int {
        var total = 0
        for (entry in undoStack) {
            total += when (entry) {
                is DrawObject.Stroke -> entry.points.size
                is DrawObject.EraseStroke -> entry.points.size
                is DrawObject.Line, is DrawObject.Shape, is DrawObject.Fill -> 0
            }
        }
        return total
    }
}
