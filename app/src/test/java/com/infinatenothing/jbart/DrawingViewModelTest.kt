package com.infinatenothing.jbart

import androidx.lifecycle.SavedStateHandle
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.ColorTarget
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.HistoryLimits
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import com.infinatenothing.jbart.session.SessionStore
import com.infinatenothing.jbart.session.SessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun lineSnapDefaultsOnAndSurvivesSavedState() {
        val store = MemorySessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        assertTrue(model.lineSnapEnabled)
        model.setLineSnap(false)
        model.commit(line())
        model.onHostStop()
        val copy = SavedStateHandle(
            mapOf(DrawingViewModel.KEY_LINE_SNAP to handle.get<Boolean>(DrawingViewModel.KEY_LINE_SNAP)),
        )
        val restored = model(copy, store)
        assertFalse(restored.lineSnapEnabled)
        assertEquals(listOf(line()), restored.document.objects.toList())
    }

    @Test
    fun shapeSettingsSurviveSavedStateAndSessionReload() {
        val store = MemorySessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        assertEquals(ShapeKind.RECTANGLE, model.shapeKind)
        assertFalse(model.shapeConstrain)
        assertFalse(model.shapeFilled)
        model.updateShapeKind(ShapeKind.ELLIPSE)
        model.updateShapeConstrain(true)
        model.updateShapeFilled(true)
        model.onHostStop()

        val fromHandle = model(
            SavedStateHandle(
                mapOf(
                    DrawingViewModel.KEY_SHAPE_KIND to handle.get<String>(DrawingViewModel.KEY_SHAPE_KIND),
                    DrawingViewModel.KEY_SHAPE_CONSTRAIN to handle.get<Boolean>(DrawingViewModel.KEY_SHAPE_CONSTRAIN),
                    DrawingViewModel.KEY_SHAPE_FILLED to handle.get<Boolean>(DrawingViewModel.KEY_SHAPE_FILLED),
                ),
            ),
            MemorySessionStore(),
        )
        assertEquals(ShapeKind.ELLIPSE, fromHandle.shapeKind)
        assertTrue(fromHandle.shapeConstrain)
        assertTrue(fromHandle.shapeFilled)

        val fromSession = model(SavedStateHandle(), store)
        assertEquals(ShapeKind.ELLIPSE, fromSession.shapeKind)
        assertTrue(fromSession.shapeConstrain)
        assertTrue(fromSession.shapeFilled)
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

    @Test
    fun pendingShapeIsOutsideHistoryUntilCommit() {
        val model = model(history = HistoryManager(maxEntries = 2))
        val kept = stroke(1f)
        model.commit(kept)
        model.stagePending(shape(0f))
        model.updatePending(shape(4f))
        assertEquals(listOf(kept), model.document.objects.toList())
        assertEquals(1, model.history.undoCount)
        assertFalse(model.history.canRedo)
        assertEquals(shape(4f), model.pending)
        model.updateShapeConstrain(true)
        assertEquals(shape(4f), model.pending)
        model.updateShapeFilled(true)
        assertEquals(true, (model.pending as DrawObject.Shape).filled)

        model.commitPending()
        assertNull(model.pending)
        assertEquals(2, model.document.objects.size)
        model.stagePending(shape(2f))
        model.commitPending()
        model.stagePending(shape(3f))
        model.commitPending()
        assertEquals(4, model.document.objects.size)
        assertEquals(2, model.history.undoCount)
    }

    @Test
    fun undoWhilePendingDiscardsItAndLeavesHistory() {
        val model = model()
        val kept = stroke(2f)
        model.commit(kept)
        model.undo()
        assertEquals(1, model.history.redoCount)
        model.stagePending(shape(1f))
        val undo = model.history.undoCount
        val redo = model.history.redoCount
        model.undo()
        assertNull(model.pending)
        assertEquals(undo, model.history.undoCount)
        assertEquals(redo, model.history.redoCount)
        assertTrue(model.document.objects.isEmpty())
    }

    @Test
    fun redoAndToolSwitchCommitPendingAndStartNothingElse() {
        val model = model()
        val kept = stroke(3f)
        model.commit(kept)
        model.undo()
        model.stagePending(shape(6f))
        model.redo()
        assertNull(model.pending)
        assertEquals(listOf(shape(6f)), model.document.objects.toList())
        assertFalse(model.history.canRedo)
        assertEquals(1, model.history.undoCount)

        model.stagePending(shape(7f))
        model.selectTool(Tool.LINE)
        assertEquals(Tool.LINE, model.tool)
        assertNull(model.pending)
        assertEquals(listOf(shape(6f), shape(7f)), model.document.objects.toList())
        model.undo()
        model.redo()
        assertEquals(shape(7f), model.document.objects.last())
    }

    @Test
    fun pendingLineStaysOutOfHistoryUntilCommitOrToolSwitch() {
        val model = model()
        val kept = stroke(1f)
        model.commit(kept)
        model.undo()
        val undo = model.history.undoCount
        val redo = model.history.redoCount
        model.stagePending(line())
        model.updatePending(line().copy(x2 = 30f))
        assertTrue(model.document.objects.isEmpty())
        assertEquals(undo, model.history.undoCount)
        assertEquals(redo, model.history.redoCount)
        assertEquals(30f, (model.pending as DrawObject.Line).x2, 0f)

        model.undo()
        assertNull(model.pending)
        assertEquals(undo, model.history.undoCount)
        assertEquals(redo, model.history.redoCount)
        assertTrue(model.document.objects.isEmpty())

        model.stagePending(line())
        model.updatePending(line().copy(x2 = 18f))
        model.commitPending()
        assertNull(model.pending)
        assertEquals(1, model.document.objects.size)
        assertEquals(18f, (model.document.objects.single() as DrawObject.Line).x2, 0f)
        assertEquals(1, model.history.undoCount)

        model.stagePending(line().copy(x2 = 22f))
        model.updatePending(line().copy(x2 = 24f))
        model.selectTool(Tool.LINE)
        assertEquals(Tool.LINE, model.tool)
        assertNull(model.pending)
        assertEquals(2, model.document.objects.size)
        assertEquals(24f, (model.document.objects.last() as DrawObject.Line).x2, 0f)
        assertEquals(2, model.history.undoCount)
    }

    @Test
    fun encodedPendingLineRestoresAsPendingThenCommits() {
        val store = CodecSessionStore()
        val model = model(store = store)
        val pending = line().copy(x2 = 40f, y2 = 6f)
        model.commit(stroke(1f))
        model.stagePending(pending)
        model.onHostStop()
        val restored = model(SavedStateHandle(), store)
        assertEquals(pending, restored.pending)
        assertEquals(listOf(stroke(1f)), restored.document.objects.toList())
        assertEquals(1, restored.history.undoCount)
        restored.commitPending()
        assertNull(restored.pending)
        assertEquals(listOf(stroke(1f), pending), restored.document.objects.toList())
        restored.undo()
        assertEquals(listOf(stroke(1f)), restored.document.objects.toList())
        restored.redo()
        assertEquals(pending, restored.document.objects.last())
    }

    @Test
    fun encodedPendingShapeRestoresAsPendingThenCommits() {
        val store = CodecSessionStore()
        val model = model(store = store)
        val pending = shape(9f).copy(rotationDeg = 40f)
        model.commit(stroke(1f))
        model.stagePending(pending)
        model.onHostStop()
        val restored = model(SavedStateHandle(), store)
        assertEquals(pending, restored.pending)
        assertEquals(listOf(stroke(1f)), restored.document.objects.toList())
        assertEquals(1, restored.history.undoCount)
        restored.commitPending()
        assertNull(restored.pending)
        assertEquals(listOf(stroke(1f), pending), restored.document.objects.toList())
        restored.undo()
        assertEquals(listOf(stroke(1f)), restored.document.objects.toList())
        restored.redo()
        assertEquals(pending, restored.document.objects.last())
    }

    @Test
    fun commitUsesStrokeOrFillColorAndRecentUpdatesOnlyOnCommit() {
        val model = model()
        model.updateStrokeColor(ColorSettings.RED)
        model.updateFillColor(ColorSettings.BLUE)
        assertTrue(model.colorSettings.recent.isEmpty())

        model.commit(stroke(1f).copy(color = model.colorSettings.stroke))
        model.commit(line().copy(color = model.colorSettings.stroke))
        model.commit(shape(2f).copy(color = model.colorSettings.stroke, filled = false))
        val filled = shape(3f).copy(
            kind = ShapeKind.ELLIPSE,
            filled = true,
            color = model.colorSettings.fill,
        )
        model.commit(filled)
        val arrow = shape(4f).copy(
            kind = ShapeKind.ARROW,
            filled = true,
            color = model.colorSettings.stroke,
        )
        model.commit(arrow)

        assertEquals(ColorSettings.RED, (model.document.objects[0] as DrawObject.Stroke).color)
        assertEquals(ColorSettings.RED, (model.document.objects[1] as DrawObject.Line).color)
        assertEquals(ColorSettings.RED, (model.document.objects[2] as DrawObject.Shape).color)
        assertEquals(ColorSettings.BLUE, (model.document.objects[3] as DrawObject.Shape).color)
        assertEquals(ColorSettings.RED, (model.document.objects[4] as DrawObject.Shape).color)
        assertEquals(listOf(ColorSettings.RED, ColorSettings.BLUE), model.colorSettings.recent)

        model.updateStrokeColor(ColorSettings.GREEN)
        assertEquals(ColorSettings.RED, (model.document.objects[0] as DrawObject.Stroke).color)
        assertEquals(listOf(ColorSettings.RED, ColorSettings.BLUE), model.colorSettings.recent)

        model.undo()
        model.undo()
        model.redo()
        assertEquals(ColorSettings.BLUE, (model.document.objects.last() as DrawObject.Shape).color)
        model.redo()
        assertEquals(ColorSettings.RED, (model.document.objects.last() as DrawObject.Shape).color)
    }

    @Test
    fun colorChangeRecolorsPendingShapeAndLineOnly() {
        val model = model()
        model.updateFillColor(ColorSettings.BLUE)
        val committed = shape(1f).copy(color = ColorSettings.RED)
        model.commit(committed)
        model.stagePending(line().copy(color = ColorSettings.BLACK))
        model.updateStrokeColor(ColorSettings.ORANGE)
        assertEquals(ColorSettings.ORANGE, (model.pending as DrawObject.Line).color)
        assertEquals(ColorSettings.RED, (model.document.objects.single() as DrawObject.Shape).color)

        model.commitPending()
        model.stagePending(shape(6f).copy(filled = false, color = ColorSettings.BLACK))
        model.updateStrokeColor(ColorSettings.GREEN)
        assertEquals(ColorSettings.GREEN, (model.pending as DrawObject.Shape).color)
        model.updateShapeFilled(true)
        val filled = model.pending as DrawObject.Shape
        assertTrue(filled.filled)
        assertEquals(ColorSettings.BLUE, filled.color)
        model.updateFillColor(ColorSettings.PURPLE)
        assertEquals(ColorSettings.PURPLE, (model.pending as DrawObject.Shape).color)

        model.updateShapeFilled(false)
        assertEquals(ColorSettings.GREEN, (model.pending as DrawObject.Shape).color)
        val arrow = shape(8f).copy(kind = ShapeKind.ARROW, filled = true, color = ColorSettings.BLACK)
        model.updatePending(arrow)
        model.updateStrokeColor(ColorSettings.TAN)
        assertEquals(ColorSettings.TAN, (model.pending as DrawObject.Shape).color)
        model.updateFillColor(ColorSettings.PINK)
        assertEquals(ColorSettings.TAN, (model.pending as DrawObject.Shape).color)
    }

    @Test
    fun eyedropperSetsTargetColorRecolorsPendingAndSkipsHistory() {
        val model = model()
        model.selectTool(Tool.LINE)
        model.updateFillColor(ColorSettings.BLUE)
        model.stagePending(line().copy(color = ColorSettings.BLACK))
        val objects = model.document.objects.size
        val undo = model.history.undoCount
        assertEquals(ColorTarget.STROKE, model.colorTarget)
        model.startEyedropper()
        assertTrue(model.eyedropperActive)
        assertEquals(Tool.LINE, model.tool)
        model.pickEyedropper(0x80FF00FF.toInt())
        assertFalse(model.eyedropperActive)
        assertEquals(Tool.LINE, model.tool)
        assertEquals(0xFFFF00FF.toInt(), model.colorSettings.stroke)
        assertEquals(listOf(0xFFFF00FF.toInt()), model.colorSettings.recent)
        assertEquals(0xFFFF00FF.toInt(), (model.pending as DrawObject.Line).color)
        assertEquals(objects, model.document.objects.size)
        assertEquals(undo, model.history.undoCount)

        val before = model.colorSettings
        val pending = model.pending
        model.startEyedropper()
        model.cancelEyedropper()
        assertEquals(before, model.colorSettings)
        assertEquals(pending, model.pending)
        assertEquals(Tool.LINE, model.tool)
        assertFalse(model.eyedropperActive)

        model.chooseColorTarget(ColorTarget.FILL)
        model.stagePending(
            shape(3f).copy(kind = ShapeKind.RECTANGLE, filled = true, color = ColorSettings.BLACK),
        )
        model.startEyedropper()
        model.pickEyedropper(ColorSettings.YELLOW)
        assertEquals(ColorSettings.YELLOW, model.colorSettings.fill)
        assertEquals(ColorSettings.YELLOW, (model.pending as DrawObject.Shape).color)
        assertEquals(0xFFFF00FF.toInt(), model.colorSettings.stroke)
        assertEquals(Tool.LINE, model.tool)
        assertEquals(undo, model.history.undoCount)
        assertTrue(model.colorSettings.recent.first() == ColorSettings.YELLOW)
    }

    @Test
    fun colorsRestoreFromSavedStateAndFromSessionAndDropperDoesNot() {
        val store = MemorySessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        model.selectTool(Tool.SHAPE)
        model.updateStrokeColor(ColorSettings.RED)
        model.updateFillColor(ColorSettings.BLUE)
        model.commit(stroke(1f).copy(color = ColorSettings.RED))
        model.chooseColorTarget(ColorTarget.FILL)
        model.startEyedropper()
        model.onHostStop()

        val fromHandle = model(
            SavedStateHandle(
                mapOf(
                    DrawingViewModel.KEY_STROKE_COLOR to handle.get<Int>(DrawingViewModel.KEY_STROKE_COLOR),
                    DrawingViewModel.KEY_FILL_COLOR to handle.get<Int>(DrawingViewModel.KEY_FILL_COLOR),
                    DrawingViewModel.KEY_RECENT_COLORS to handle.get<IntArray>(DrawingViewModel.KEY_RECENT_COLORS),
                    DrawingViewModel.KEY_TOOL to handle.get<String>(DrawingViewModel.KEY_TOOL),
                ),
            ),
            MemorySessionStore(),
        )
        assertEquals(ColorSettings.RED, fromHandle.colorSettings.stroke)
        assertEquals(ColorSettings.BLUE, fromHandle.colorSettings.fill)
        assertEquals(listOf(ColorSettings.RED), fromHandle.colorSettings.recent)
        assertEquals(Tool.SHAPE, fromHandle.tool)
        assertEquals(ColorTarget.STROKE, fromHandle.colorTarget)
        assertFalse(fromHandle.eyedropperActive)

        val fromSession = model(SavedStateHandle(), store)
        assertEquals(ColorSettings.RED, fromSession.colorSettings.stroke)
        assertEquals(ColorSettings.BLUE, fromSession.colorSettings.fill)
        assertEquals(listOf(ColorSettings.RED), fromSession.colorSettings.recent)
        assertEquals(ColorSettings.RED, (fromSession.document.objects.single() as DrawObject.Stroke).color)
        assertFalse(fromSession.eyedropperActive)
        assertEquals(ColorTarget.STROKE, fromSession.colorTarget)
    }

    @Test
    fun fillCommitPushesRecentAndUndoUsesRunEquality() {
        val model = model()
        model.updateFillColor(ColorSettings.BLUE)
        val runs = intArrayOf(0, 0, 2, 1, 1, 3)
        val fill = DrawObject.Fill(
            seedX = 1,
            seedY = 2,
            color = ColorSettings.BLUE,
            alpha = 1f,
            tolerance = 32,
            gapPx = 4,
            width = 8,
            height = 8,
            runs = runs,
        )
        model.commit(fill)
        assertEquals(listOf(fill), model.document.objects.toList())
        assertEquals(1, model.history.undoCount)
        assertEquals(listOf(ColorSettings.BLUE), model.colorSettings.recent)
        model.undo()
        assertTrue(model.document.objects.isEmpty())
        model.redo()
        val restored = model.document.objects.single() as DrawObject.Fill
        assertEquals(fill, restored)
        assertTrue(restored.runs.contentEquals(runs))
    }

    @Test
    fun pendingShapeIsCommittedBeforeAFill() {
        val model = model()
        val staged = shape(3f)
        model.stagePending(staged)
        val fill = DrawObject.Fill(
            seedX = 0,
            seedY = 0,
            color = ColorSettings.RED,
            alpha = 1f,
            tolerance = 8,
            gapPx = 0,
            width = 4,
            height = 4,
            runs = intArrayOf(0, 0, 1),
        )
        model.commit(fill)
        assertNull(model.pending)
        assertEquals(listOf(staged, fill), model.document.objects.toList())
        assertEquals(2, model.history.undoCount)
    }

    @Test
    fun fillSettingsSurviveSavedStateAndTheSessionFile() {
        val store = CodecSessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        assertEquals(32, model.fillTolerance)
        assertEquals(2, model.fillGapDp)
        model.updateFillTolerance(18)
        model.updateFillGap(7)
        val fill = DrawObject.Fill(
            seedX = 0,
            seedY = 0,
            color = ColorSettings.GREEN,
            alpha = 1f,
            tolerance = 18,
            gapPx = 3,
            width = 4,
            height = 4,
            runs = intArrayOf(0, 0, 2),
        )
        model.commit(fill)
        model.onHostStop()
        val fromHandle = model(
            SavedStateHandle(
                mapOf(
                    DrawingViewModel.KEY_FILL_TOLERANCE to handle.get<Int>(DrawingViewModel.KEY_FILL_TOLERANCE),
                    DrawingViewModel.KEY_FILL_GAP to handle.get<Int>(DrawingViewModel.KEY_FILL_GAP),
                ),
            ),
            store,
        )
        assertEquals(18, fromHandle.fillTolerance)
        assertEquals(7, fromHandle.fillGapDp)
        assertEquals(fill, fromHandle.document.objects.single())
        val fromSession = model(SavedStateHandle(), store)
        assertEquals(18, fromSession.fillTolerance)
        assertEquals(7, fromSession.fillGapDp)
        assertEquals(fill, fromSession.document.objects.single())
    }

    @Test
    fun viewSurvivesSavedStateAndStaysOutOfTheSession() {
        val store = CodecSessionStore()
        val handle = SavedStateHandle()
        val model = model(handle, store)
        assertEquals(1f, model.viewScale, 0f)
        assertEquals(null, model.viewCenterX)
        assertEquals(null, model.viewCenterY)
        model.updateView(2.5f, 40f, 80f)
        model.commit(stroke(3f))
        model.onHostStop()
        assertEquals(7, com.infinatenothing.jbart.session.SessionCodec.VERSION)
        val fromHandle = model(
            SavedStateHandle(
                mapOf(
                    DrawingViewModel.KEY_VIEW_SCALE to handle.get<Float>(DrawingViewModel.KEY_VIEW_SCALE),
                    DrawingViewModel.KEY_VIEW_CENTER_X to handle.get<Float>(DrawingViewModel.KEY_VIEW_CENTER_X),
                    DrawingViewModel.KEY_VIEW_CENTER_Y to handle.get<Float>(DrawingViewModel.KEY_VIEW_CENTER_Y),
                ),
            ),
            MemorySessionStore(),
        )
        assertEquals(2.5f, fromHandle.viewScale, 0f)
        assertEquals(40f, fromHandle.viewCenterX)
        assertEquals(80f, fromHandle.viewCenterY)
        val fromSession = model(SavedStateHandle(), store)
        assertEquals(1f, fromSession.viewScale, 0f)
        assertEquals(null, fromSession.viewCenterX)
        assertEquals(null, fromSession.viewCenterY)
        assertEquals(1, fromSession.document.objects.size)
    }

    @Test
    fun clearIsOneUndoStepAndRedoClearsAgain() {
        val model = model()
        model.commit(stroke(1f))
        model.commit(stroke(2f))
        model.clearCanvas()
        assertTrue(model.document.objects.last() is DrawObject.Clear)
        assertEquals(3, model.history.undoCount)
        model.undo()
        assertEquals(2, model.document.objects.size)
        assertTrue(model.document.objects.none { it is DrawObject.Clear })
        model.redo()
        assertTrue(model.document.objects.last() is DrawObject.Clear)
    }

    @Test
    fun clearStillTrimsToTheHistoryCap() {
        val model = model()
        repeat(HistoryLimits.MAX_ENTRIES) { model.commit(stroke(1f)) }
        model.clearCanvas()
        assertTrue(model.history.undoCount <= HistoryLimits.MAX_ENTRIES)
        assertTrue(model.document.objects.last() is DrawObject.Clear)
        model.undo()
        assertFalse(model.document.objects.last() is DrawObject.Clear)
    }

    @Test
    fun newCanvasResetsHistorySizeAndView() {
        val handle = SavedStateHandle()
        val model = model(handle)
        model.commit(stroke(1f))
        model.updateView(2f, 5f, 6f)
        model.newCanvas(0, 0, ColorSettings.RED)
        assertEquals(1, model.document.objects.size)
        model.newCanvas(5000, 5000, ColorSettings.RED)
        assertEquals(1, model.document.objects.size)
        model.newCanvas(2048, 1536, ColorSettings.BLUE)
        assertEquals(2048, model.document.width)
        assertEquals(1536, model.document.height)
        assertEquals(ColorSettings.BLUE, model.document.backgroundColor)
        assertTrue(model.document.objects.isEmpty())
        assertEquals(0, model.history.undoCount)
        assertEquals(0, model.history.redoCount)
        assertNull(model.pending)
        assertEquals(1, model.fitNonce)
        assertNull(model.viewCenterX)
        assertNull(handle.get<Float>(DrawingViewModel.KEY_VIEW_SCALE))
    }

    @Test
    fun backgroundColorIsImmediateAndNotAHistoryEntry() {
        val store = CodecSessionStore()
        val model = model(store = store)
        model.commit(stroke(1f))
        model.setBackgroundColor(ColorSettings.RED)
        assertEquals(1, model.history.undoCount)
        assertEquals(ColorSettings.RED, model.document.backgroundColor)
        model.onHostStop()
        val restored = model(SavedStateHandle(), store)
        assertEquals(ColorSettings.RED, restored.document.backgroundColor)
        assertEquals(100, restored.document.width)
        assertEquals(1, restored.document.objects.size)
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
        ).also { created ->
            if (created.document.width <= 0 || created.document.height <= 0) {
                created.document.width = 100
                created.document.height = 100
            }
        }
    }

    private fun line() = DrawObject.Line(
        x1 = 0f,
        y1 = 0f,
        x2 = 12f,
        y2 = 0f,
        color = 0xFF000000.toInt(),
        alpha = 0.4f,
        widthPx = 8f,
        inputType = InputType.FINGER,
    )

    private fun shape(x: Float) = DrawObject.Shape(
        kind = ShapeKind.RECTANGLE,
        x1 = x,
        y1 = 2f,
        x2 = x + 30f,
        y2 = 20f,
        filled = false,
        color = 0xFF000000.toInt(),
        alpha = 1f,
        widthPx = 4f,
        inputType = InputType.FINGER,
    )

    private fun stroke(x: Float) = DrawObject.Stroke(
        points = listOf(StrokePoint(x, 0f, 1f, 0L)),
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.STYLUS,
    )
}

private class CodecSessionStore : SessionStore {
    private var bytes: ByteArray? = null

    override fun load(): SessionLoad {
        val saved = bytes ?: return SessionLoad.Missing
        return SessionCodec.decode(saved)
    }

    override fun save(snapshot: SessionSnapshot) {
        bytes = SessionCodec.encode(snapshot)
    }

    override fun delete() {
        bytes = null
    }
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
