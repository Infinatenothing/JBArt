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
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import com.infinatenothing.jbart.session.FileSessionStore
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
    var revision by mutableIntStateOf(0)
        private set

    @Volatile
    private var saveGeneration = 0
    private val lock = Any()
    private val saveLock = Any()

    init {
        history.onTrimmed = { dropped ->
            log("trim dropped=$dropped undo=${history.undoCount}")
        }
        // shortcut: blocks creation until the session file is read, upgrade if startup jank shows up.
        val loaded = try {
            io.submit<SessionLoad> { store.load() }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            SessionLoad.Failed(e.message ?: "load failed")
        }
        applyLoad(loaded)
    }

    fun selectTool(value: Tool) {
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

    fun commit(obj: DrawObject) {
        synchronized(lock) {
            document.objects.add(obj)
            history.push(obj)
            revision++
        }
        scheduleSave()
    }

    fun undo() {
        synchronized(lock) {
            val entry = history.undo() ?: return
            if (document.objects.lastOrNull() == entry) {
                document.objects.removeAt(document.objects.lastIndex)
            }
            revision++
        }
        scheduleSave()
    }

    fun redo() {
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
                log("save onStop objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size}")
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
                }
                log("restore objects=${snap.objects.size} undo=${history.undoCount} redo=${history.redoCount}")
                snap
            }
        }
        tool = savedEnum(KEY_TOOL, fromFile?.tool) ?: Tool.BRUSH
        brushSettings = savedBrush(KEY_BRUSH_SIZE, KEY_BRUSH_OPACITY, fromFile?.brush) ?: BrushSettings()
        eraserSettings = savedBrush(KEY_ERASER_SIZE, KEY_ERASER_OPACITY, fromFile?.eraser)
            ?: BrushSettings(sizeDp = BrushSettings.DEFAULT_ERASER_SIZE_DP)
        brushSettings = clampBrushSettings(brushSettings)
        eraserSettings = clampBrushSettings(eraserSettings)
        writeSettings()
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
                    log("save objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size}")
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
        )
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val TAG = "JBArtSession"
        const val KEY_TOOL = "tool"
        const val KEY_BRUSH_SIZE = "brush_size"
        const val KEY_BRUSH_OPACITY = "brush_opacity"
        const val KEY_ERASER_SIZE = "eraser_size"
        const val KEY_ERASER_OPACITY = "eraser_opacity"

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
