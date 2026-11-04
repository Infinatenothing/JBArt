package com.infinatenothing.jbart

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.ColorTarget
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import com.infinatenothing.jbart.model.colorForShape
import com.infinatenothing.jbart.model.opaque
import com.infinatenothing.jbart.model.pushRecent
import com.infinatenothing.jbart.session.FileSessionStore
import com.infinatenothing.jbart.session.SessionCodec
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import com.infinatenothing.jbart.session.SessionStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DrawingViewModel(
    private val savedState: SavedStateHandle,
    private val store: SessionStore,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jbart-session").apply { isDaemon = true }
    },
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    val history: HistoryManager = HistoryManager(),
) : ViewModel() {
    val document = DrawingDocument()
    var tool by mutableStateOf(Tool.BRUSH)
        private set
    var brushSettings by mutableStateOf(BrushSettings())
        private set
    var eraserSettings by mutableStateOf(BrushSettings(sizeDp = BrushSettings.DEFAULT_ERASER_SIZE_DP))
        private set
    var lineSnapEnabled by mutableStateOf(true)
        private set
    var shapeKind by mutableStateOf(ShapeKind.RECTANGLE)
        private set
    var shapeConstrain by mutableStateOf(false)
        private set
    var shapeFilled by mutableStateOf(false)
        private set
    var colorSettings by mutableStateOf(ColorSettings())
        private set
    var colorTarget by mutableStateOf(ColorTarget.STROKE)
        private set
    var eyedropperActive by mutableStateOf(false)
        private set
    var pending by mutableStateOf<DrawObject?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set

    @Volatile
    private var saveGeneration = 0
    private val lock = Any()
    private val saveLock = Any()
    private var toolBeforeEyedropper: Tool? = null

    init {
        history.onTrimmed = { dropped ->
            log("trim dropped=$dropped undo=${history.undoCount}")
        }
        SessionCodec.log = log
        // shortcut: blocks creation until the session file is read, upgrade if startup jank shows up.
        val loaded = try {
            io.submit<SessionLoad> { store.load() }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            SessionLoad.Failed(e.message ?: "load failed")
        }
        applyLoad(loaded)
    }

    fun selectTool(value: Tool) {
        if (eyedropperActive) cancelEyedropper()
        if (value != tool) commitPending()
        tool = value
        writeSettings()
        scheduleSave()
    }

    fun updateBrush(settings: BrushSettings) {
        brushSettings = clampBrushSettings(settings)
        writeSettings()
        scheduleSave()
    }

    fun updateEraser(settings: BrushSettings) {
        eraserSettings = clampBrushSettings(settings)
        writeSettings()
        scheduleSave()
    }

    fun setLineSnap(enabled: Boolean) {
        if (lineSnapEnabled == enabled) return
        lineSnapEnabled = enabled
        writeSettings()
        scheduleSave()
    }

    fun updateShapeKind(kind: ShapeKind) {
        if (shapeKind == kind) return
        shapeKind = kind
        writeSettings()
        scheduleSave()
    }

    fun updateShapeConstrain(enabled: Boolean) {
        if (shapeConstrain == enabled) return
        shapeConstrain = enabled
        writeSettings()
        scheduleSave()
    }

    fun updateShapeFilled(filled: Boolean) {
        if (shapeFilled == filled) return
        shapeFilled = filled
        synchronized(lock) {
            val current = pending
            if (current is DrawObject.Shape) {
                val color = colorForShape(colorSettings.stroke, colorSettings.fill, current.kind, filled)
                if (current.filled != filled || current.color != color) {
                    pending = current.copy(filled = filled, color = color)
                }
            }
        }
        writeSettings()
        scheduleSave()
    }

    fun chooseColorTarget(target: ColorTarget) {
        colorTarget = target
    }

    fun updateStrokeColor(color: Int) {
        val next = opaque(color)
        if (colorSettings.stroke == next) return
        colorSettings = colorSettings.copy(stroke = next)
        recolorPending()
        writeSettings()
        scheduleSave()
        log("color stroke=${ColorMath.toHex(next)}")
    }

    fun updateFillColor(color: Int) {
        val next = opaque(color)
        if (colorSettings.fill == next) return
        colorSettings = colorSettings.copy(fill = next)
        recolorPending()
        writeSettings()
        scheduleSave()
        log("color fill=${ColorMath.toHex(next)}")
    }

    /** Dropper mode is not a tool and is not written to the session. */
    fun startEyedropper() {
        if (eyedropperActive) return
        toolBeforeEyedropper = tool
        eyedropperActive = true
    }

    fun pickEyedropper(color: Int) {
        if (!eyedropperActive) return
        val next = opaque(color)
        val target = colorTarget
        colorSettings = if (target == ColorTarget.FILL) {
            colorSettings.copy(fill = next, recent = pushRecent(colorSettings.recent, next))
        } else {
            colorSettings.copy(stroke = next, recent = pushRecent(colorSettings.recent, next))
        }
        recolorPending()
        val previous = toolBeforeEyedropper ?: tool
        eyedropperActive = false
        toolBeforeEyedropper = null
        tool = previous
        writeSettings()
        scheduleSave()
    }

    fun cancelEyedropper() {
        if (!eyedropperActive) return
        val previous = toolBeforeEyedropper ?: tool
        eyedropperActive = false
        toolBeforeEyedropper = null
        tool = previous
    }

    /** Keeps [obj] editable. It is not in the document and not a history entry. */
    fun stagePending(obj: DrawObject) {
        synchronized(lock) {
            pending = obj
        }
        log("pending start ${describe(obj)}")
        scheduleSave()
    }

    /** Replaces the pending object after a finished move, resize, or rotate. */
    fun updatePending(obj: DrawObject) {
        synchronized(lock) {
            if (pending == null) return
            pending = obj
        }
        log("pending edit ${describe(obj)}")
        scheduleSave()
    }

    fun commitPending() {
        synchronized(lock) {
            val obj = pending ?: return
            pending = null
            document.objects.add(obj)
            history.push(obj)
            revision++
            rememberUsedColor(obj)
            log("pending commit objects=${document.objects.size} undo=${history.undoCount}")
        }
        scheduleSave()
    }

    fun commit(obj: DrawObject) {
        synchronized(lock) {
            document.objects.add(obj)
            history.push(obj)
            revision++
            rememberUsedColor(obj)
        }
        scheduleSave()
    }

    fun undo() {
        synchronized(lock) {
            if (pending != null) {
                pending = null
                log("pending discard")
            } else {
                val entry = history.undo() ?: return
                if (document.objects.lastOrNull() == entry) {
                    document.objects.removeAt(document.objects.lastIndex)
                }
                revision++
            }
        }
        scheduleSave()
    }

    fun redo() {
        if (pending != null) commitPending()
        synchronized(lock) {
            val entry = history.redo() ?: return
            document.objects.add(entry)
            revision++
        }
        scheduleSave()
    }

    fun onHostStop() {
        val snapshot = synchronized(lock) {
            saveGeneration++
            captureLocked()
        }
        val pending = io.submit {
            synchronized(saveLock) {
                store.save(snapshot)
                log(
                    "save onStop objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size} " +
                        "shape=${snapshot.shapeKind} constrain=${snapshot.shapeConstrain} filled=${snapshot.shapeFilled} " +
                        "pending=${snapshot.pending != null} stroke=${ColorMath.toHex(snapshot.colors.stroke)} " +
                        "fill=${ColorMath.toHex(snapshot.colors.fill)} recent=${snapshot.colors.recent.size}",
                )
            }
        }
        try {
            pending.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log("save onStop failed ${e.message}")
        }
    }

    override fun onCleared() {
        io.shutdownNow()
    }

    private fun applyLoad(loaded: SessionLoad) {
        val fromFile = when (loaded) {
            SessionLoad.Missing -> {
                log("restore none")
                null
            }
            is SessionLoad.Failed -> {
                log("discard session: ${loaded.reason}")
                null
            }
            is SessionLoad.Ok -> {
                val snap = loaded.snapshot
                synchronized(lock) {
                    document.width = snap.width
                    document.height = snap.height
                    document.backgroundColor = snap.backgroundColor
                    document.objects.clear()
                    document.objects.addAll(snap.objects)
                    val undoCount = snap.undoCount.coerceIn(0, snap.objects.size)
                    history.replace(snap.objects.takeLast(undoCount), snap.redo)
                    pending = snap.pending
                }
                log(
                    "restore objects=${snap.objects.size} undo=${history.undoCount} redo=${history.redoCount} " +
                        "shape=${snap.shapeKind} constrain=${snap.shapeConstrain} filled=${snap.shapeFilled} " +
                        "pending=${snap.pending != null}",
                )
                snap
            }
        }
        tool = savedEnum(KEY_TOOL, fromFile?.tool) ?: Tool.BRUSH
        brushSettings = savedBrush(KEY_BRUSH_SIZE, KEY_BRUSH_OPACITY, fromFile?.brush) ?: BrushSettings()
        eraserSettings = savedBrush(KEY_ERASER_SIZE, KEY_ERASER_OPACITY, fromFile?.eraser)
            ?: BrushSettings(sizeDp = BrushSettings.DEFAULT_ERASER_SIZE_DP)
        brushSettings = clampBrushSettings(brushSettings)
        eraserSettings = clampBrushSettings(eraserSettings)
        lineSnapEnabled = savedState.get<Boolean>(KEY_LINE_SNAP) ?: fromFile?.lineSnap ?: true
        shapeKind = savedShapeKind(fromFile?.shapeKind) ?: ShapeKind.RECTANGLE
        shapeConstrain = savedState.get<Boolean>(KEY_SHAPE_CONSTRAIN) ?: fromFile?.shapeConstrain ?: false
        shapeFilled = savedState.get<Boolean>(KEY_SHAPE_FILLED) ?: fromFile?.shapeFilled ?: false
        colorSettings = savedColors(fromFile?.colors)
        eyedropperActive = false
        toolBeforeEyedropper = null
        colorTarget = ColorTarget.STROKE
        writeSettings()
        log(
            "settings tool=$tool shape=$shapeKind constrain=$shapeConstrain filled=$shapeFilled snap=$lineSnapEnabled " +
                "stroke=${ColorMath.toHex(colorSettings.stroke)} fill=${ColorMath.toHex(colorSettings.fill)} " +
                "recent=${colorSettings.recent.size}",
        )
    }

    private fun savedColors(fallback: ColorSettings?): ColorSettings {
        val stroke = savedState.get<Int>(KEY_STROKE_COLOR)
        val fill = savedState.get<Int>(KEY_FILL_COLOR)
        val recent = savedState.get<IntArray>(KEY_RECENT_COLORS)
        if (stroke == null && fill == null && recent == null) {
            return (fallback ?: ColorSettings()).let { colors ->
                ColorSettings(
                    stroke = opaque(colors.stroke),
                    fill = opaque(colors.fill),
                    recent = colors.recent.map(::opaque).take(ColorSettings.MAX_RECENT),
                )
            }
        }
        return ColorSettings(
            stroke = opaque(stroke ?: fallback?.stroke ?: ColorSettings.BLACK),
            fill = opaque(fill ?: fallback?.fill ?: ColorSettings.BLACK),
            recent = (recent?.toList() ?: fallback?.recent.orEmpty())
                .map(::opaque)
                .take(ColorSettings.MAX_RECENT),
        )
    }

    private fun savedShapeKind(fallback: ShapeKind?): ShapeKind? {
        val name = savedState.get<String>(KEY_SHAPE_KIND) ?: return fallback
        return ShapeKind.entries.firstOrNull { it.name == name } ?: fallback
    }

    private fun savedEnum(key: String, fallback: Tool?): Tool? {
        val name = savedState.get<String>(key) ?: return fallback
        return Tool.entries.firstOrNull { it.name == name } ?: fallback
    }

    private fun savedBrush(sizeKey: String, opacityKey: String, fallback: BrushSettings?): BrushSettings? {
        val size = savedState.get<Float>(sizeKey) ?: return fallback
        val opacity = savedState.get<Float>(opacityKey) ?: return fallback
        return BrushSettings(sizeDp = size, opacity = opacity)
    }

    private fun writeSettings() {
        savedState[KEY_TOOL] = tool.name
        savedState[KEY_BRUSH_SIZE] = brushSettings.sizeDp
        savedState[KEY_BRUSH_OPACITY] = brushSettings.opacity
        savedState[KEY_ERASER_SIZE] = eraserSettings.sizeDp
        savedState[KEY_ERASER_OPACITY] = eraserSettings.opacity
        savedState[KEY_LINE_SNAP] = lineSnapEnabled
        savedState[KEY_SHAPE_KIND] = shapeKind.name
        savedState[KEY_SHAPE_CONSTRAIN] = shapeConstrain
        savedState[KEY_SHAPE_FILLED] = shapeFilled
        savedState[KEY_STROKE_COLOR] = colorSettings.stroke
        savedState[KEY_FILL_COLOR] = colorSettings.fill
        savedState[KEY_RECENT_COLORS] = colorSettings.recent.toIntArray()
    }

    private fun scheduleSave() {
        val generation = ++saveGeneration
        io.execute {
            try {
                if (debounceMs > 0) Thread.sleep(debounceMs)
            } catch (_: InterruptedException) {
                return@execute
            }
            synchronized(saveLock) {
                if (generation != saveGeneration) return@execute
                val snapshot = synchronized(lock) { captureLocked() }
                try {
                    store.save(snapshot)
                    log(
                    "save objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size} " +
                        "shape=${snapshot.shapeKind} constrain=${snapshot.shapeConstrain} filled=${snapshot.shapeFilled} " +
                        "pending=${snapshot.pending != null} stroke=${ColorMath.toHex(snapshot.colors.stroke)} " +
                        "fill=${ColorMath.toHex(snapshot.colors.fill)} recent=${snapshot.colors.recent.size}",
                    )
                } catch (e: Exception) {
                    log("save failed ${e.message}")
                }
            }
        }
    }

    private fun captureLocked(): SessionSnapshot {
        val objects = document.objects.toList()
        return SessionSnapshot(
            width = document.width,
            height = document.height,
            backgroundColor = document.backgroundColor,
            objects = objects,
            undoCount = history.undoCount.coerceAtMost(objects.size),
            redo = history.redoEntries(),
            tool = tool,
            brush = brushSettings,
            eraser = eraserSettings,
            lineSnap = lineSnapEnabled,
            shapeKind = shapeKind,
            shapeConstrain = shapeConstrain,
            shapeFilled = shapeFilled,
            pending = pending,
            colors = colorSettings,
        )
    }

    private fun recolorPending() {
        synchronized(lock) {
            when (val current = pending) {
                is DrawObject.Line -> {
                    val color = opaque(colorSettings.stroke)
                    if (current.color != color) pending = current.copy(color = color)
                }
                is DrawObject.Shape -> {
                    val color = colorForShape(
                        colorSettings.stroke,
                        colorSettings.fill,
                        current.kind,
                        current.filled,
                    )
                    if (current.color != color) pending = current.copy(color = color)
                }
                else -> Unit
            }
        }
    }

    private fun rememberUsedColor(obj: DrawObject) {
        val color = when (obj) {
            is DrawObject.Stroke -> obj.color
            is DrawObject.Line -> obj.color
            is DrawObject.Shape -> obj.color
            else -> return
        }
        val recent = pushRecent(colorSettings.recent, color)
        if (recent != colorSettings.recent) {
            colorSettings = colorSettings.copy(recent = recent)
            writeSettings()
        }
    }

    private fun describe(obj: DrawObject): String {
        return when (obj) {
            is DrawObject.Shape -> "kind=${obj.kind} rotation=${obj.rotationDeg} box=${obj.x1},${obj.y1},${obj.x2},${obj.y2}"
            is DrawObject.Line -> "line ${obj.x1},${obj.y1},${obj.x2},${obj.y2}"
            else -> obj.tool.name
        }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val TAG = "JBArtSession"
        const val KEY_TOOL = "tool"
        const val KEY_BRUSH_SIZE = "brush_size"
        const val KEY_BRUSH_OPACITY = "brush_opacity"
        const val KEY_ERASER_SIZE = "eraser_size"
        const val KEY_ERASER_OPACITY = "eraser_opacity"
        const val KEY_LINE_SNAP = "line_snap"
        const val KEY_SHAPE_KIND = "shape_kind"
        const val KEY_SHAPE_CONSTRAIN = "shape_constrain"
        const val KEY_SHAPE_FILLED = "shape_filled"
        const val KEY_STROKE_COLOR = "stroke_color"
        const val KEY_FILL_COLOR = "fill_color"
        const val KEY_RECENT_COLORS = "recent_colors"

        fun factory(filesDir: File): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return DrawingViewModel(
                        savedState = extras.createSavedStateHandle(),
                        store = FileSessionStore(filesDir),
                    ) as T
                }
            }
        }
    }
}
