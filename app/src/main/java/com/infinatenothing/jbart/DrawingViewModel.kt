package com.infinatenothing.jbart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.infinatenothing.jbart.canvas.ViewTransform
import com.infinatenothing.jbart.export.ExportFormat
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.CanvasLimits
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
import com.infinatenothing.jbart.session.ContentProjectFiles
import com.infinatenothing.jbart.session.FileSessionStore
import com.infinatenothing.jbart.session.ProjectArchive
import com.infinatenothing.jbart.session.ProjectFiles
import com.infinatenothing.jbart.session.ProjectLink
import com.infinatenothing.jbart.session.ProjectLinkState
import com.infinatenothing.jbart.session.ProjectRead
import com.infinatenothing.jbart.session.RecentProject
import com.infinatenothing.jbart.session.RecentProjects
import com.infinatenothing.jbart.session.SessionCodec
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import com.infinatenothing.jbart.session.SessionStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ProjectEvent { SAVED, SAVE_FAILED, OPEN_NEWER, OPEN_FAILED }

class DrawingViewModel(
    private val savedState: SavedStateHandle,
    private val store: SessionStore,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jbart-session").apply { isDaemon = true }
    },
    private val debounceMs: Long = SAVE_DEBOUNCE_MS,
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
    val history: HistoryManager = HistoryManager(),
    private val projects: ProjectFiles? = null,
    private val link: ProjectLink? = null,
    private val recents: RecentProjects? = null,
    private val projectIo: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jbart-project").apply { isDaemon = true }
    },
    private val post: (() -> Unit) -> Unit = { block -> Handler(Looper.getMainLooper()).post(block) },
    private val now: () -> Long = System::currentTimeMillis,
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
    var fillTolerance by mutableIntStateOf(32)
        private set
    var fillGapDp by mutableIntStateOf(2)
        private set
    var fillBusy by mutableStateOf(false)
        private set
    var viewScale by mutableFloatStateOf(1f)
        private set
    var viewCenterX by mutableStateOf<Float?>(null)
        private set
    var viewCenterY by mutableStateOf<Float?>(null)
        private set
    var pending by mutableStateOf<DrawObject?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var fitNonce by mutableIntStateOf(0)
        private set

    /** UI chrome only: kept in SavedStateHandle, never in a project file or the autosave. */
    var toolbarHidden by mutableStateOf(savedState.get<Boolean>(KEY_TOOLBAR_HIDDEN) ?: false)
        private set
    var exportFormat by mutableStateOf(ExportFormat.PNG)
        private set
    var exportQuality by mutableIntStateOf(90)
        private set
    var exportTransparent by mutableStateOf(false)
        private set

    /** Null means Untitled: Save runs Save As. */
    var projectUri by mutableStateOf<String?>(null)
        private set
    var projectName by mutableStateOf<String?>(null)
        private set

    /** Changed since the last successful user Save or Open, not since the autosave. */
    var projectDirty by mutableStateOf(false)
        private set
    var projectBusy by mutableStateOf(false)
        private set
    var recentProjects by mutableStateOf<List<RecentProject>>(emptyList())
        private set

    /** One-shot result for a Toast. The screen clears it with [consumeProjectEvent]. */
    var projectEvent by mutableStateOf<ProjectEvent?>(null)
        private set

    /** A VIEW intent uri waiting for the screen to run the unsaved-changes prompt. */
    var incomingProject by mutableStateOf<String?>(null)
        private set
    private var editGeneration = 0

    @Volatile
    private var saveGeneration = 0
    private val lock = Any()
    private val saveLock = Any()
    private var toolBeforeEyedropper: Tool? = null
    var cancelInFlightFill: (() -> Unit)? = null

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
        link?.read()?.let { state ->
            projectUri = state.uri
            projectName = state.name
            projectDirty = state.dirty
        }
        recentProjects = recents?.list().orEmpty()
    }

    fun offerIncomingProject(uri: String) {
        incomingProject = uri
    }

    fun consumeIncomingProject() {
        incomingProject = null
    }

    fun consumeProjectEvent() {
        projectEvent = null
    }

    /** Writes the full snapshot, pending object included, to [uri]. [onDone] runs on the main thread. */
    fun saveProject(uri: String, thumbPng: ByteArray?, fresh: Boolean, onDone: (Boolean) -> Unit = {}) {
        val files = projects ?: return
        if (projectBusy) return
        cancelInFlightFill?.invoke()
        val snapshot = synchronized(lock) { captureLocked() }
        val generation = editGeneration
        val fallbackName = projectName.takeIf { uri == projectUri } ?: suggestedProjectName()
        projectBusy = true
        val started = System.nanoTime()
        projectIo.execute {
            val name = try {
                val name = files.displayName(uri) ?: fallbackName
                files.write(uri, ProjectArchive.pack(snapshot, name, thumbPng), fresh)
                name
            } catch (e: Exception) {
                log("project save failed ${e.javaClass.simpleName} ${e.message}")
                null
            }
            val list = name?.let { recentsOrNull { it.add(uri, name, thumbPng, now()) } }
            if (name != null) {
                log("project save objects=${snapshot.objects.size} ms=${(System.nanoTime() - started) / 1_000_000L}")
            }
            post {
                projectBusy = false
                if (name != null) {
                    projectUri = uri
                    projectName = name
                    projectDirty = editGeneration != generation
                    persistLink()
                    list?.let { recentProjects = it }
                    projectEvent = ProjectEvent.SAVED
                } else {
                    projectEvent = ProjectEvent.SAVE_FAILED
                }
                onDone(name != null)
            }
        }
    }

    /** A failed read keeps the current drawing and the autosave. */
    fun openProject(uri: String) {
        val files = projects ?: return
        if (projectBusy) return
        projectBusy = true
        val started = System.nanoTime()
        projectIo.execute {
            var unreadable = false
            val read = try {
                ProjectArchive.unpack(files.read(uri))
            } catch (e: Exception) {
                log("project open failed ${e.javaClass.simpleName} ${e.message}")
                unreadable = true
                ProjectRead.Corrupt
            }
            val name = if (read is ProjectRead.Ok) files.displayName(uri) ?: suggestedProjectName() else null
            val list = when {
                read is ProjectRead.Ok -> recentsOrNull { it.add(uri, name!!, read.thumb, now()) }
                unreadable -> recentsOrNull { it.remove(uri) }
                else -> null
            }
            log("project open result=${read.javaClass.simpleName} ms=${(System.nanoTime() - started) / 1_000_000L}")
            post {
                projectBusy = false
                list?.let { recentProjects = it }
                when (read) {
                    is ProjectRead.Ok -> {
                        applyProject(read.snapshot)
                        projectUri = uri
                        projectName = name
                        projectDirty = false
                        persistLink()
                    }
                    ProjectRead.Newer -> projectEvent = ProjectEvent.OPEN_NEWER
                    ProjectRead.Corrupt -> projectEvent = ProjectEvent.OPEN_FAILED
                }
            }
        }
    }

    fun removeRecentProject(uri: String) {
        projectIo.execute {
            val list = recentsOrNull { it.remove(uri) } ?: return@execute
            post { recentProjects = list }
        }
    }

    fun suggestedProjectName(): String {
        return projectName ?: "JBArt_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}" +
            ".${ProjectArchive.EXTENSION}"
    }

    private fun <T> recentsOrNull(block: (RecentProjects) -> T): T? {
        val store = recents ?: return null
        return try {
            block(store)
        } catch (e: Exception) {
            log("recents failed ${e.message}")
            null
        }
    }

    /** File settings win over [savedState], and the view is fit instead of restored. */
    private fun applyProject(snapshot: SessionSnapshot) {
        cancelInFlightFill?.invoke()
        PROJECT_KEYS.forEach { savedState.remove<Any>(it) }
        viewScale = 1f
        viewCenterX = null
        viewCenterY = null
        applyLoad(SessionLoad.Ok(snapshot))
        synchronized(lock) {
            fitNonce++
            revision++
        }
        scheduleSave()
    }

    private fun markDirty() {
        editGeneration++
        if (projectDirty) return
        projectDirty = true
        persistLink()
    }

    private fun persistLink() {
        val store = link ?: return
        val state = ProjectLinkState(projectUri, projectName, projectDirty)
        projectIo.execute {
            try {
                store.write(state)
            } catch (e: Exception) {
                log("project link failed ${e.message}")
            }
        }
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

    fun updateFillTolerance(value: Int) {
        val next = value.coerceIn(0, 255)
        if (fillTolerance == next) return
        fillTolerance = next
        writeSettings()
        scheduleSave()
        log("fill tolerance=$next")
    }

    fun updateFillGap(value: Int) {
        val next = value.coerceIn(0, 10)
        if (fillGapDp == next) return
        fillGapDp = next
        writeSettings()
        scheduleSave()
        log("fill gapDp=$next")
    }

    fun setFillRunning(busy: Boolean) {
        if (fillBusy == busy) return
        fillBusy = busy
    }

    fun updateToolbarHidden(hidden: Boolean) {
        toolbarHidden = hidden
        savedState[KEY_TOOLBAR_HIDDEN] = hidden
    }

    /** View center is a canvas point. Absent centers mean the identity transform. */
    fun updateView(scale: Float, centerX: Float, centerY: Float) {
        val next = scale.coerceIn(ViewTransform.MIN_SCALE, ViewTransform.MAX_SCALE)
        if (viewScale == next && viewCenterX == centerX && viewCenterY == centerY) return
        viewScale = next
        viewCenterX = centerX
        viewCenterY = centerY
        savedState[KEY_VIEW_SCALE] = next
        savedState[KEY_VIEW_CENTER_X] = centerX
        savedState[KEY_VIEW_CENTER_Y] = centerY
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
        markDirty()
        scheduleSave()
    }

    /** Replaces the pending object after a finished move, resize, or rotate. */
    fun updatePending(obj: DrawObject) {
        synchronized(lock) {
            if (pending == null) return
            pending = obj
        }
        log("pending edit ${describe(obj)}")
        markDirty()
        scheduleSave()
    }

    fun updateExportFormat(value: ExportFormat) {
        exportFormat = value
        writeSettings()
    }

    fun updateExportQuality(value: Int) {
        exportQuality = value.coerceIn(50, 100)
        writeSettings()
    }

    fun updateExportTransparent(value: Boolean) {
        exportTransparent = value
        writeSettings()
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
        markDirty()
        scheduleSave()
    }

    fun commit(obj: DrawObject) {
        synchronized(lock) {
            if (obj is DrawObject.Fill) {
                val staged = pending
                if (staged != null) {
                    pending = null
                    document.objects.add(staged)
                    history.push(staged)
                    rememberUsedColor(staged)
                    log("pending commit before fill")
                }
            }
            document.objects.add(obj)
            history.push(obj)
            revision++
            rememberUsedColor(obj)
        }
        markDirty()
        scheduleSave()
    }

    fun newCanvas(width: Int, height: Int, background: Int) {
        if (!CanvasLimits.withinCap(width, height)) return
        cancelInFlightFill?.invoke()
        synchronized(lock) {
            pending = null
            document.objects.clear()
            document.width = width
            document.height = height
            document.backgroundColor = opaque(background)
            history.replace(emptyList(), emptyList())
            viewScale = 1f
            viewCenterX = null
            viewCenterY = null
            savedState.remove<Float>(KEY_VIEW_SCALE)
            savedState.remove<Float>(KEY_VIEW_CENTER_X)
            savedState.remove<Float>(KEY_VIEW_CENTER_Y)
            fitNonce++
            revision++
        }
        projectUri = null
        projectName = null
        projectDirty = false
        persistLink()
        scheduleSave()
        log("new canvas ${width}x$height background=${ColorMath.toHex(opaque(background))}")
    }

    fun clearCanvas() {
        cancelInFlightFill?.invoke()
        synchronized(lock) {
            val staged = pending
            if (staged != null) {
                pending = null
                document.objects.add(staged)
                history.push(staged)
                rememberUsedColor(staged)
            }
            val start = document.objects.indexOfLast { it is DrawObject.Clear } + 1
            if (start < document.objects.size) {
                document.objects.add(DrawObject.Clear)
                history.push(DrawObject.Clear)
            }
            revision++
        }
        markDirty()
        scheduleSave()
        log("clear objects=${document.objects.size} undo=${history.undoCount}")
    }

    fun setBackgroundColor(color: Int) {
        val next = opaque(color)
        synchronized(lock) {
            if (document.backgroundColor == next) return
            document.backgroundColor = next
            revision++
        }
        markDirty()
        scheduleSave()
        log("background=${ColorMath.toHex(next)}")
    }

    fun undo() {
        if (fillBusy) return
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
        markDirty()
        scheduleSave()
    }

    fun redo() {
        if (fillBusy) return
        if (pending != null) commitPending()
        synchronized(lock) {
            val entry = history.redo() ?: return
            document.objects.add(entry)
            revision++
        }
        markDirty()
        scheduleSave()
    }

    fun onHostStop() {
        cancelInFlightFill?.invoke()
        val snapshot = synchronized(lock) {
            saveGeneration++
            captureLocked()
        }
        if (!CanvasLimits.withinCap(snapshot.width, snapshot.height)) {
            log("save onStop skipped size=${snapshot.width}x${snapshot.height}")
            return
        }
        val pending = io.submit {
            synchronized(saveLock) {
                store.save(snapshot)
                log(
                    "save onStop objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size} " +
                        "shape=${snapshot.shapeKind} constrain=${snapshot.shapeConstrain} filled=${snapshot.shapeFilled} " +
                        "pending=${snapshot.pending != null} stroke=${ColorMath.toHex(snapshot.colors.stroke)} " +
                        "fill=${ColorMath.toHex(snapshot.colors.fill)} recent=${snapshot.colors.recent.size} " +
                        "tolerance=${snapshot.fillTolerance} gapDp=${snapshot.fillGapDp}",
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
        projectIo.shutdown()
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
                        "pending=${snap.pending != null} tolerance=${snap.fillTolerance} gapDp=${snap.fillGapDp}",
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
        fillTolerance = (savedState.get<Int>(KEY_FILL_TOLERANCE) ?: fromFile?.fillTolerance ?: 32).coerceIn(0, 255)
        fillGapDp = (savedState.get<Int>(KEY_FILL_GAP) ?: fromFile?.fillGapDp ?: 2).coerceIn(0, 10)
        exportFormat = savedExportFormat()
        exportQuality = (savedState.get<Int>(KEY_EXPORT_QUALITY) ?: 90).coerceIn(50, 100)
        exportTransparent = savedState.get<Boolean>(KEY_EXPORT_TRANSPARENT) ?: false
        val savedScale = savedState.get<Float>(KEY_VIEW_SCALE)
        val savedCenterX = savedState.get<Float>(KEY_VIEW_CENTER_X)
        val savedCenterY = savedState.get<Float>(KEY_VIEW_CENTER_Y)
        if (savedScale != null && savedCenterX != null && savedCenterY != null &&
            savedScale.isFinite() && savedCenterX.isFinite() && savedCenterY.isFinite()
        ) {
            viewScale = savedScale.coerceIn(ViewTransform.MIN_SCALE, ViewTransform.MAX_SCALE)
            viewCenterX = savedCenterX
            viewCenterY = savedCenterY
        }
        fillBusy = false
        eyedropperActive = false
        toolBeforeEyedropper = null
        colorTarget = ColorTarget.STROKE
        writeSettings()
        log(
            "settings tool=$tool shape=$shapeKind constrain=$shapeConstrain filled=$shapeFilled snap=$lineSnapEnabled " +
                "stroke=${ColorMath.toHex(colorSettings.stroke)} fill=${ColorMath.toHex(colorSettings.fill)} " +
                "recent=${colorSettings.recent.size} tolerance=$fillTolerance gapDp=$fillGapDp",
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

    private fun savedExportFormat(): ExportFormat {
        val name = savedState.get<String>(KEY_EXPORT_FORMAT) ?: return ExportFormat.PNG
        return ExportFormat.entries.firstOrNull { it.name == name } ?: ExportFormat.PNG
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
        savedState[KEY_FILL_TOLERANCE] = fillTolerance
        savedState[KEY_FILL_GAP] = fillGapDp
        savedState[KEY_EXPORT_FORMAT] = exportFormat.name
        savedState[KEY_EXPORT_QUALITY] = exportQuality
        savedState[KEY_EXPORT_TRANSPARENT] = exportTransparent
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
                if (!CanvasLimits.withinCap(snapshot.width, snapshot.height)) return@execute
                try {
                    store.save(snapshot)
                    log(
                    "save objects=${snapshot.objects.size} undo=${snapshot.undoCount} redo=${snapshot.redo.size} " +
                        "shape=${snapshot.shapeKind} constrain=${snapshot.shapeConstrain} filled=${snapshot.shapeFilled} " +
                        "pending=${snapshot.pending != null} stroke=${ColorMath.toHex(snapshot.colors.stroke)} " +
                        "fill=${ColorMath.toHex(snapshot.colors.fill)} recent=${snapshot.colors.recent.size} " +
                        "tolerance=${snapshot.fillTolerance} gapDp=${snapshot.fillGapDp}",
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
            fillTolerance = fillTolerance,
            fillGapDp = fillGapDp,
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
            is DrawObject.Fill -> obj.color
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
            is DrawObject.Clear -> "clear"
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
        const val KEY_FILL_TOLERANCE = "fill_tolerance"
        const val KEY_FILL_GAP = "fill_gap"
        const val KEY_VIEW_SCALE = "view_scale"
        const val KEY_VIEW_CENTER_X = "view_center_x"
        const val KEY_VIEW_CENTER_Y = "view_center_y"
        const val KEY_EXPORT_FORMAT = "export_format"
        const val KEY_EXPORT_QUALITY = "export_quality"
        const val KEY_EXPORT_TRANSPARENT = "export_transparent"
        const val KEY_TOOLBAR_HIDDEN = "toolbar_hidden"

        /** Settings a project file carries. Export settings stay with the app. */
        private val PROJECT_KEYS = listOf(
            KEY_TOOL, KEY_BRUSH_SIZE, KEY_BRUSH_OPACITY, KEY_ERASER_SIZE, KEY_ERASER_OPACITY, KEY_LINE_SNAP,
            KEY_SHAPE_KIND, KEY_SHAPE_CONSTRAIN, KEY_SHAPE_FILLED, KEY_STROKE_COLOR, KEY_FILL_COLOR,
            KEY_RECENT_COLORS, KEY_FILL_TOLERANCE, KEY_FILL_GAP, KEY_VIEW_SCALE, KEY_VIEW_CENTER_X, KEY_VIEW_CENTER_Y,
        )

        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            val filesDir = app.filesDir
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    @Suppress("UNCHECKED_CAST")
                    return DrawingViewModel(
                        savedState = extras.createSavedStateHandle(),
                        store = FileSessionStore(filesDir),
                        projects = ContentProjectFiles(app),
                        link = ProjectLink(filesDir),
                        recents = RecentProjects(filesDir),
                    ) as T
                }
            }
        }
    }
}
