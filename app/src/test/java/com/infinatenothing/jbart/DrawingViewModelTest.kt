package com.infinatenothing.jbart

import androidx.lifecycle.SavedStateHandle
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import com.infinatenothing.jbart.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class DrawingViewModelTest {
    @Test
    fun commitUndoRedoAndTrimLeavesOlderStrokes() {
        val model = model(history = HistoryManager(maxEntries = 2))
        val first = stroke(1f)
        val second = stroke(2f)
        val third = stroke(3f)
        model.commit(first)
        model.commit(second)
        model.commit(third)
        assertEquals(listOf(first, second, third), model.document.objects.toList())
        assertEquals(2, model.history.undoCount)
        model.undo()
        model.undo()
        assertEquals(listOf(first), model.document.objects.toList())
        assertFalse(model.history.canUndo)
        model.redo()
        assertEquals(listOf(first, second), model.document.objects.toList())
    }

    @Test
    fun smallStateSurvivesFreshSavedStateHandle() {
        val store = MemorySessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        model.selectTool(Tool.ERASER)
        model.updateBrush(BrushSettings(sizeDp = 12f, opacity = 0.4f))
        model.updateEraser(BrushSettings(sizeDp = 20f, opacity = 1f))
        model.commit(stroke(1f))
        model.undo()
        model.redo()
        model.onHostStop()
        val copy = SavedStateHandle(
            mapOf(
                DrawingViewModel.KEY_TOOL to handle.get<String>(DrawingViewModel.KEY_TOOL),
                DrawingViewModel.KEY_BRUSH_SIZE to handle.get<Float>(DrawingViewModel.KEY_BRUSH_SIZE),
                DrawingViewModel.KEY_BRUSH_OPACITY to handle.get<Float>(DrawingViewModel.KEY_BRUSH_OPACITY),
                DrawingViewModel.KEY_ERASER_SIZE to handle.get<Float>(DrawingViewModel.KEY_ERASER_SIZE),
                DrawingViewModel.KEY_ERASER_OPACITY to handle.get<Float>(DrawingViewModel.KEY_ERASER_OPACITY),
            ),
        )
        val restored = model(copy, store)
        assertEquals(Tool.ERASER, restored.tool)
        assertEquals(12f, restored.brushSettings.sizeDp, 0f)
        assertEquals(0.4f, restored.brushSettings.opacity, 0f)
        assertEquals(20f, restored.eraserSettings.sizeDp, 0f)
        assertEquals(listOf(stroke(1f)), restored.document.objects.toList())
        assertTrue(restored.history.canUndo)
        restored.undo()
        assertTrue(restored.document.objects.isEmpty())
    }

    private fun model(
        savedState: SavedStateHandle = SavedStateHandle(),
        store: SessionStore = MemorySessionStore(),
        history: HistoryManager = HistoryManager(),
    ): DrawingViewModel {
        return DrawingViewModel(
            savedState = savedState,
            store = store,
            io = inlineExecutor(),
            debounceMs = 0,
            log = {},
            history = history,
        )
    }

    private fun stroke(x: Float) = DrawObject.Stroke(
        points = listOf(StrokePoint(x, 0f, 1f, 0L)),
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.STYLUS,
    )
}

private class MemorySessionStore : SessionStore {
    private var saved: SessionSnapshot? = null

    override fun load(): SessionLoad {
        return saved?.let { SessionLoad.Ok(it) } ?: SessionLoad.Missing
    }

    override fun save(snapshot: SessionSnapshot) {
        saved = snapshot
    }

    override fun delete() {
        saved = null
    }
}

private fun inlineExecutor(): ExecutorService {
    return object : AbstractExecutorService() {
        @Volatile private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
