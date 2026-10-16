package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryManagerTest {
    @Test
    fun emptyHistoryCannotUndoOrRedo() {
        val history = HistoryManager()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun undoThenRedoRestoresStroke() {
        val history = HistoryManager()
        val stroke = stroke(1f)
        history.push(stroke)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(stroke, history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(stroke, history.redo())
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun pushClearsRedo() {
        val history = HistoryManager()
        val first = stroke(1f)
        val second = stroke(2f)
        history.push(first)
        history.undo()
        history.push(second)
        assertFalse(history.canRedo)
        assertEquals(second, history.undo())
        assertFalse(history.canUndo)
    }

    private fun stroke(x: Float) = DrawObject.Stroke(
        points = listOf(StrokePoint(x, 0f, 1f, 0L)),
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.STYLUS,
    )
}
