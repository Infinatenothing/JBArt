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
    fun mixedStrokeAndEraseUndoRedoThenPushClearsRedo() {
        val history = HistoryManager()
        val brush = stroke(1f)
        val erase = DrawObject.EraseStroke(
            points = listOf(StrokePoint(2f, 0f, 1f, 0L)),
            baseWidthPx = 16f,
            inputType = InputType.FINGER,
        )
        val brushAgain = stroke(3f)
        history.push(brush)
        history.push(erase)
        history.push(brushAgain)

        assertEquals(brushAgain, history.undo())
        assertEquals(erase, history.undo())
        assertEquals(brush, history.undo())
        assertFalse(history.canUndo)
        assertEquals(brush, history.redo())
        assertEquals(erase, history.redo())
        assertEquals(brushAgain, history.redo())
        assertFalse(history.canRedo)

        assertEquals(brushAgain, history.undo())
        val replacement = stroke(4f)
        history.push(replacement)
        assertFalse(history.canRedo)
        assertEquals(replacement, history.undo())
        assertEquals(erase, history.undo())
        assertEquals(brush, history.undo())
    }

    @Test
    fun capByCountDropsOldestAndKeepsUndoRedo() {
        val history = HistoryManager(maxEntries = 2, maxPoints = 100)
        val first = stroke(1f)
        val second = stroke(2f)
        val third = stroke(3f)
        history.push(first)
        history.push(second)
        history.push(third)
        assertEquals(2, history.undoCount)
        assertEquals(third, history.undo())
        assertEquals(second, history.undo())
        assertNull(history.undo())
        assertEquals(second, history.redo())
        assertEquals(third, history.redo())
    }

    @Test
    fun capByPointsDropsOldest() {
        val history = HistoryManager(maxEntries = 10, maxPoints = 3)
        history.push(stroke(1f, points = 2))
        history.push(stroke(2f, points = 2))
        history.push(stroke(3f, points = 2))
        assertEquals(1, history.undoCount)
        assertEquals(stroke(3f, points = 2), history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun pushClearsRedoAfterTrim() {
        val history = HistoryManager(maxEntries = 2, maxPoints = 100)
        history.push(stroke(1f))
        history.push(stroke(2f))
        history.push(stroke(3f))
        history.undo()
        history.push(stroke(4f))
        assertFalse(history.canRedo)
        assertEquals(stroke(4f), history.undo())
        assertEquals(stroke(2f), history.undo())
        assertNull(history.undo())
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

    private fun stroke(x: Float, points: Int = 1) = DrawObject.Stroke(
        points = List(points) { index -> StrokePoint(x + index, 0f, 1f, 0L) },
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.STYLUS,
    )
}
