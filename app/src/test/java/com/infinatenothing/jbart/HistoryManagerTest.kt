package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.HistoryManager
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
    fun undoThenRedoRestoresEntry() {
        val history = HistoryManager()
        history.push("stroke")
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals("stroke", history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals("stroke", history.redo())
        assertTrue(history.canUndo)
    }

    @Test
    fun pushClearsRedo() {
        val history = HistoryManager()
        history.push("a")
        history.undo()
        history.push("b")
        assertFalse(history.canRedo)
        assertEquals("b", history.undo())
    }
}
