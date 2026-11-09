package com.infinatenothing.jbart.canvas

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Xfermode
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.export.ExportFormat
import com.infinatenothing.jbart.export.ExportNaming
import com.infinatenothing.jbart.export.ExportRenderer
import com.infinatenothing.jbart.export.ExportSource
import com.infinatenothing.jbart.export.ExportWriter
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.CanvasLimits
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.visibleObjects
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.EyedropperMath
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import com.infinatenothing.jbart.model.colorForShape
import com.infinatenothing.jbart.model.opaque
import java.time.LocalDateTime
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Document background composited under a transparent ink bitmap.
 * Brush strokes are built opaque on a scratch bitmap and blitted once with the stroke alpha,
 * so a stroke does not darken where it crosses itself. A shape is drawn the same way:
 * opaque on the scratch bitmap, then blitted once with the brush alpha.
 * Erases punch that ink with CLEAR.
 * A shape or line that just finished drawing stays pending on the scratch layer, with handles
 * drawn above it. Those handles are never copied onto the ink bitmap.
 * A pending line has two endpoint handles and no box.
 */
class DrawingView(context: Context) : View(context) {
    var document: DrawingDocument? = null
    var onCommit: (DrawObject) -> Unit = {}
    var onStagePending: (DrawObject) -> Unit = {}
    var onUpdatePending: (DrawObject) -> Unit = {}
    var onCommitPending: () -> Unit = {}
    var renderedRevision: Int = -1
    var tool = Tool.BRUSH
    var brushSettings: BrushSettings = BrushSettings()
    var eraserSettings: BrushSettings = BrushSettings(sizeDp = BrushSettings.DEFAULT_ERASER_SIZE_DP)
    var lineSnapEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (drawing && activeDrawTool == Tool.LINE) {
                moveLine(lastRawX, lastRawY)
            }
        }
    var shapeKind: ShapeKind = ShapeKind.RECTANGLE
        set(value) {
            if (field == value) return
            field = value
            refreshShapePreview("kind=$value")
        }
    var shapeConstrain: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshShapePreview("constrain=$value")
        }
    var shapeFilled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshShapePreview("filled=$value")
            val current = pendingShape ?: return
            val nextColor = colorForShape(strokeColor, fillColor, current.kind, value)
            if (current.filled == value && current.color == nextColor) return
            pendingShape = current.copy(filled = value, color = nextColor)
            dragOrigin = dragOrigin?.let { origin ->
                val originColor = colorForShape(strokeColor, fillColor, origin.kind, value)
                if (origin.filled == value && origin.color == originColor) {
                    origin
                } else {
                    origin.copy(filled = value, color = originColor)
                }
            }
            if (!drawing) {
                paintPendingShape()
                invalidate()
            }
        }
    var strokeColor: Int = Color.BLACK
        set(value) {
            val next = opaque(value)
            if (field == next) return
            field = next
            refreshLiveColor()
        }
    var fillColor: Int = Color.BLACK
        set(value) {
            val next = opaque(value)
            if (field == next) return
            field = next
            refreshLiveColor()
        }
    var eyedropperActive: Boolean = false
        set(value) {
            if (field == value) return
            val starting = value
            field = value
            if (starting) {
                if (drawing) discardActive()
                if (pendingDrag) cancelPendingDrag()
                Log.d(TAG, "eyedropper start")
            } else {
                loupeVisible = false
                dropperPointer = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
    var eyedropperTarget: String = "stroke"
    var onEyedropperPick: (Int) -> Unit = {}
    var onEyedropperCancel: () -> Unit = {}
    var fillTolerance: Int = 32
    var fillGapDp: Int = 2
    var onFillBusy: (Boolean) -> Unit = {}
    var onViewChanged: (Float, Float, Float) -> Unit = { _, _, _ -> }

    private var ink: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val blitPaint = Paint()
    private val path = Path()
    private val shapeRect = RectF()
    private val segment = SegmentBuffer()
    private val strokePoints = ArrayList<StrokePoint>(64)
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private var geometryXfer: Xfermode? = null

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var inputType = InputType.OTHER
    private var stylusActive = false
    private var drawing = false
    private var activeDrawTool = Tool.BRUSH
    private var strokeAlpha = 1f
    private var strokeBaseWidthPx = 4f
    private var lineStartX = 0f
    private var lineStartY = 0f
    private var lineEndX = 0f
    private var lineEndY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lineMagnet = false
    private val snapOut = FloatArray(2)
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapeEndX = 0f
    private var shapeEndY = 0f
    private val shapeEndOut = FloatArray(2)
    private val shapeBox = FloatArray(4)
    private val shapeTri = FloatArray(6)
    private val shapeHead = FloatArray(6)
    private val shapeBounds = FloatArray(4)
    private val prevShapeBounds = FloatArray(4)
    private val showSnapLabel =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val labelBuilder = StringBuilder(32)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private var pendingShape: DrawObject.Shape? = null
    private var pendingLine: DrawObject.Line? = null
    private var pendingDrag = false
    private var dragHandle: Handle? = null
    private var dragOrigin: DrawObject.Shape? = null
    private var lineHandle: LineHandle? = null
    private var lineDragOrigin: DrawObject.Line? = null
    private var endpointSnap = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private val handleOut = FloatArray(18)
    private val lineOut = FloatArray(4)
    private var loupeVisible = false
    private var loupeX = 0f
    private var loupeY = 0f
    private var loupeColor = Color.BLACK
    private var dropperPointer = MotionEvent.INVALID_POINTER_ID
    private val eyedropperOut = FloatArray(2)
    private val loupePath = Path()
    private val loupeSrc = Rect()
    private val loupeDst = RectF()
    private val loupeChip = RectF()
    private val hexChars = charArrayOf('#', '0', '0', '0', '0', '0', '0')
    private val loupeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
    }
    private val loupeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val loupeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        isDither = false
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fillExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jbart-fill").apply { isDaemon = true }
    }
    private val exportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jbart-export").apply { isDaemon = true }
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var fillGeneration = 0
    private var fillInFlight = false
    private var exportInFlight = false
    private var fillTracking = false
    private var fillMoved = false
    private var fillStartX = 0f
    private var fillStartY = 0f
    private var fillScreenX = 0f
    private var fillScreenY = 0f
    private val viewTransform = ViewTransform()
    private val viewMatrix = Matrix()
    private val mapped = FloatArray(2)
    private val cornerScratch = FloatArray(8)
    private val cornerMatrix = Matrix()
    private val outsidePaint = Paint().apply { color = Color.rgb(189, 189, 189) }
    private val canvasFillPaint = Paint()
    private val crispBitmapPaint = Paint().apply { isFilterBitmap = false }
    private val smoothBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var viewScaleRequest = 1f
    private var viewCenterXRequest = Float.NaN
    private var viewCenterYRequest = Float.NaN
    private var fitNonce = 0
    private var appliedFitNonce = 0
    private var navigating = false
    private var navFrozen = false
    private var navFocusX = 0f
    private var navFocusY = 0f
    private var navSpan = 0f

    init {
        setWillNotDraw(false)
        isClickable = true
        check(MotionEvent.TOOL_TYPE_FINGER == InputRouting.TOOL_TYPE_FINGER)
        check(MotionEvent.TOOL_TYPE_STYLUS == InputRouting.TOOL_TYPE_STYLUS)
        check(MotionEvent.TOOL_TYPE_MOUSE == InputRouting.TOOL_TYPE_MOUSE)
        check(MotionEvent.TOOL_TYPE_ERASER == InputRouting.TOOL_TYPE_ERASER)
        check(MotionEvent.BUTTON_STYLUS_PRIMARY == InputRouting.BUTTON_STYLUS_PRIMARY)
        check(MotionEvent.BUTTON_STYLUS_SECONDARY == InputRouting.BUTTON_STYLUS_SECONDARY)
        FloodFill.log = { message -> Log.d(TAG, message) }
        labelPaint.textSize = 12f * resources.displayMetrics.density
        loupeTextPaint.textSize = 12f * resources.displayMetrics.density
        handleLinePaint.strokeWidth = dp(1.5f)
        syncMatrix()
    }

    /** Restores a saved view. NaN centers keep the identity transform. */
    fun setViewState(scale: Float, centerX: Float, centerY: Float) {
        val nextScale = scale.coerceIn(ViewTransform.MIN_SCALE, ViewTransform.MAX_SCALE)
        if (viewScaleRequest == nextScale && sameCenter(viewCenterXRequest, centerX) &&
            sameCenter(viewCenterYRequest, centerY)
        ) {
            return
        }
        viewScaleRequest = nextScale
        viewCenterXRequest = centerX
        viewCenterYRequest = centerY
        if (width > 0 && height > 0) applyViewRequest(publish = false)
    }

    fun zoomIn() = stepZoom(ViewTransform.STEP)

    fun zoomOut() = stepZoom(1f / ViewTransform.STEP)

    /** Drops an in-progress stroke so a new or cleared canvas cannot commit it. */
    fun abandonInput() {
        cancelOutstandingFill()
        dropLiveGesture()
        clearScratch()
        invalidate()
    }

    fun setFitNonce(nonce: Int) {
        fitNonce = nonce
        applyPendingFit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        if (ensureCanvasBitmaps()) renderAll()
        applyViewRequest(publish = !viewCenterXRequest.isNaN() && !viewCenterYRequest.isNaN())
        applyPendingFit()
    }

    override fun onDraw(canvas: Canvas) {
        val identity = identityView()
        if (!identity) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), outsidePaint)
        }
        canvas.save()
        canvas.concat(viewMatrix)
        canvasFillPaint.color = document?.backgroundColor ?: Color.WHITE
        canvas.drawRect(0f, 0f, canvasWidth(), canvasHeight(), canvasFillPaint)
        val contentPaint = bitmapPaint()
        ink?.let { bitmap ->
            // CPU draws into this bitmap after the GPU has cached it. Without
            // prepareToDraw the screen keeps the first blank upload.
            bitmap.prepareToDraw()
            canvas.drawBitmap(bitmap, 0f, 0f, contentPaint)
        }
        val pending = pendingShape
        val line = pendingLine
        if (pending != null || line != null || (drawing && activeDrawTool != Tool.ERASER)) {
            scratch?.let { bitmap ->
                bitmap.prepareToDraw()
                blitPaint.xfermode = null
                blitPaint.isFilterBitmap = contentPaint?.isFilterBitmap == true
                val alpha = if (drawing) strokeAlpha else pending?.alpha ?: line?.alpha ?: strokeAlpha
                blitPaint.alpha = alphaByte(alpha)
                canvas.drawBitmap(bitmap, 0f, 0f, blitPaint)
            }
        }
        canvas.restore()
        if (!drawing) {
            if (pending != null) drawHandles(canvas, pending)
            else if (line != null) drawLineHandles(canvas, line)
        }
        if (drawing && activeDrawTool == Tool.LINE && labelBuilder.isNotEmpty()) {
            val offset = labelPaint.textSize
            viewTransform.toScreen(lineEndX, lineEndY, mapped)
            canvas.drawText(
                labelBuilder,
                0,
                labelBuilder.length,
                mapped[0] + offset,
                mapped[1] - offset,
                labelPaint,
            )
        }
        if (eyedropperActive && loupeVisible) drawLoupe(canvas)
    }

    fun requestRedraw() = invalidate()

    fun renderAll() {
        Log.d(TAG, "renderAll")
        val canvas = inkCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (obj in visibleObjects(document?.objects.orEmpty())) {
            when (obj) {
                is DrawObject.Stroke -> stampBrush(obj.points, obj.baseWidthPx, obj.alpha, obj.inputType, obj.color)
                is DrawObject.EraseStroke -> stampErase(obj.points, obj.baseWidthPx, obj.inputType)
                is DrawObject.Line -> stampLine(obj.x1, obj.y1, obj.x2, obj.y2, obj.color, obj.widthPx, obj.alpha)
                is DrawObject.Shape -> stampShape(obj)
                is DrawObject.Fill -> stampFill(obj)
                is DrawObject.Clear -> Unit
            }
        }
        clearScratch()
        paintPendingShape()
        paintPendingLine()
        ink?.prepareToDraw()
        invalidate()
    }

    /** Shows the model pending shape or line. A null value discards the local edit. */
    fun bindPending(obj: DrawObject?) {
        if (obj == null) {
            val had = pendingShape != null || pendingLine != null || pendingDrag
            pendingDrag = false
            dragOrigin = null
            dragHandle = null
            lineDragOrigin = null
            lineHandle = null
            pendingShape = null
            pendingLine = null
            if (had) {
                stylusActive = false
                if (!drawing) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    clearScratch()
                    invalidate()
                }
            }
            return
        }
        if (pendingDrag || drawing) return
        when (obj) {
            is DrawObject.Shape -> {
                pendingLine = null
                if (pendingShape == obj) return
                pendingShape = obj
                paintPendingShape()
                invalidate()
            }
            is DrawObject.Line -> {
                pendingShape = null
                if (pendingLine == obj) return
                pendingLine = obj
                paintPendingLine()
                invalidate()
            }
            else -> Unit
        }
    }

    /** Writes an in-progress drag into the pending object before a commit. */
    fun flushPendingEdit() {
        if (!pendingDrag) return
        if (lineDragOrigin != null) {
            val line = pendingLine
            pendingDrag = false
            lineDragOrigin = null
            lineHandle = null
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            if (line != null) onUpdatePending(line)
            return
        }
        val shape = pendingShape
        pendingDrag = false
        dragOrigin = null
        dragHandle = null
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (shape != null) onUpdatePending(shape)
    }

    fun sync(source: DrawingDocument, revision: Int) {
        document = source
        if (width <= 0 || height <= 0) return
        val recreated = ensureCanvasBitmaps()
        if (recreated || renderedRevision != revision) {
            renderAll()
            renderedRevision = revision
        }
    }

    fun cancelEyedropperGesture() {
        loupeVisible = false
        dropperPointer = MotionEvent.INVALID_POINTER_ID
        stylusActive = false
        Log.d(TAG, "eyedropper cancel")
        invalidate()
        onEyedropperCancel()
    }

    fun cancelOutstandingFill() {
        val busy = fillInFlight || fillTracking
        fillGeneration++
        fillTracking = false
        fillMoved = false
        fillInFlight = false
        if (activeDrawTool == Tool.FILL || busy) {
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
        }
        if (busy) {
            onFillBusy(false)
            Log.d(TAG, "fill cancelled")
        }
    }

    override fun onDetachedFromWindow() {
        cancelOutstandingFill()
        fillExecutor.shutdownNow()
        exportExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    /**
     * Commits a pending shape or line, then copies ink for a background export.
     * Null when the canvas is not ready or a copy cannot be allocated.
     */
    fun exportSnapshot(): ExportSource? {
        if (exportInFlight || fillInFlight) return null
        commitPendingOnly()
        val source = ink ?: return null
        val doc = document ?: return null
        if (doc.width <= 0 || doc.height <= 0) return null
        val copy = try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (oom: OutOfMemoryError) {
            Log.d(TAG, "export failed oom")
            null
        } ?: return null
        exportInFlight = true
        return ExportSource(copy, doc.width, doc.height, doc.backgroundColor)
    }

    fun startExport(
        format: ExportFormat,
        quality: Int,
        transparent: Boolean,
        share: Boolean,
        onBusy: (Boolean) -> Unit,
    ) {
        val source = exportSnapshot()
        if (source == null) {
            Log.d(TAG, "export failed no bitmap")
            Toast.makeText(context.applicationContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
            onBusy(false)
            return
        }
        val appContext = context.applicationContext
        val started = System.nanoTime()
        val keepAlpha = transparent && format == ExportFormat.PNG
        try {
            exportExecutor.execute {
                var rendered: Bitmap? = null
                try {
                    rendered = ExportRenderer.render(
                        source.ink,
                        source.width,
                        source.height,
                        source.background,
                        keepAlpha,
                    )
                    val name = ExportNaming.fileName(LocalDateTime.now(), format)
                    val uri = if (share) {
                        ExportWriter.writeShareFile(appContext, rendered, format, quality, name)
                    } else {
                        ExportWriter.saveToMediaStore(appContext, rendered, format, quality, name)
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    Log.d(TAG, "export done format=${format.name} ms=$elapsedMs")
                    mainHandler.post { deliverExport(onBusy, share, uri, format) }
                } catch (thrown: Throwable) {
                    Log.d(TAG, "export failed ${thrown.javaClass.simpleName} ${thrown.message}")
                    mainHandler.post {
                        exportInFlight = false
                        if (isAttachedToWindow) onBusy(false)
                        Toast.makeText(appContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (!source.ink.isRecycled) source.ink.recycle()
                    val image = rendered
                    if (image != null && !image.isRecycled) image.recycle()
                }
            }
        } catch (thrown: Throwable) {
            if (!source.ink.isRecycled) source.ink.recycle()
            exportInFlight = false
            Log.d(TAG, "export failed ${thrown.javaClass.simpleName} ${thrown.message}")
            Toast.makeText(appContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
            onBusy(false)
        }
    }

    private fun deliverExport(onBusy: (Boolean) -> Unit, share: Boolean, uri: Uri, format: ExportFormat) {
        exportInFlight = false
        val appContext = context.applicationContext
        if (isAttachedToWindow) onBusy(false)
        if (!share) {
            Toast.makeText(appContext, R.string.export_saved, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(appContext.contentResolver, "export", uri)
        }
        val chooser = Intent.createChooser(send, appContext.getString(R.string.export_share))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launch = if (isAttachedToWindow) context else appContext
        if (launch !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            launch.startActivity(chooser)
        } catch (thrown: Exception) {
            Log.d(TAG, "export failed share ${thrown.message}")
            Toast.makeText(appContext, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fillInFlight || exportInFlight) return true
        logEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (navigating) {
            onNavigateTouch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && shouldStartNavigate(event)) {
            startNavigate(event)
            return true
        }
        if (eyedropperActive) {
            onEyedropperTouch(event)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_MOVE -> onPointerMove(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finishPointer(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID &&
                    event.getPointerId(event.actionIndex) == activePointerId
                ) {
                    finishPointer(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (fillTracking) {
                    endFillTrack()
                } else if (pendingDrag) {
                    cancelPendingDrag()
                } else if (drawing) {
                    discardActive()
                }
            }
        }
        return true
    }

    private fun onPointerDown(event: MotionEvent) {
        val mode = InputRouting.modeForPointer(
            event.getToolType(0),
            pointerCount = 1,
            stylusDown = false,
        )
        if (mode != GestureMode.DRAW) return
        if (pendingDrag) cancelPendingDrag()
        if (drawing) discardActive()
        val routed = InputRouting.toolForEvent(tool, event.getToolType(0), event.buttonState)
        if (routed == Tool.FILL) {
            beginFillTrack(event)
            return
        }
        if (routed == null) return
        if (pendingShape != null && (routed == Tool.ERASER || tool != Tool.SHAPE)) {
            Log.d(TAG, "pending commit routed=$routed")
            commitPendingOnly()
            return
        }
        if (pendingLine != null && (routed == Tool.ERASER || tool != Tool.LINE)) {
            Log.d(TAG, "pending line commit routed=$routed")
            commitPendingOnly()
            return
        }
        if (routed == Tool.LINE && pendingLine != null) {
            val x = canvasX(event, 0)
            val y = canvasY(event, 0)
            val line = pendingLine ?: return
            val handle = LineTransform.hitLineHandle(line, x, y, canvasUnits(handleTouchPx()))
            if (handle != null) {
                beginLineDrag(handle, x, y, event, 0)
                return
            }
            val slop = max(line.widthPx / 2f, canvasUnits(dp(LineTransform.BODY_SLOP_DP)))
            if (LineTransform.nearLine(line, x, y, slop)) {
                beginLineDrag(null, x, y, event, 0)
                return
            }
            Log.d(TAG, "pending line commit tap")
            commitPendingOnly()
            return
        }
        if (routed == Tool.SHAPE && pendingShape != null) {
            val x = canvasX(event, 0)
            val y = canvasY(event, 0)
            val shape = pendingShape ?: return
            val handle = ShapeTransform.hitHandle(
                shape,
                x,
                y,
                canvasUnits(handleTouchPx()),
                canvasUnits(rotateOffsetPx()),
            )
            if (handle != null) {
                beginPendingDrag(handle, x, y, event, 0)
                return
            }
            if (ShapeTransform.insideBox(shape, x, y, 0f)) {
                beginPendingDrag(null, x, y, event, 0)
                return
            }
            Log.d(TAG, "pending commit tap")
            commitPendingOnly()
            return
        }
        when (routed) {
            Tool.LINE -> beginLine(event, 0)
            Tool.SHAPE -> beginShape(event, 0)
            else -> beginStroke(event, 0, routed)
        }
    }

    private fun onPointerMove(event: MotionEvent) {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        if (ignoredPointer(event, index)) return
        if (fillTracking) {
            val x = event.getX(index)
            val y = event.getY(index)
            if (hypot(x - fillScreenX, y - fillScreenY) > touchSlop) {
                fillMoved = true
            }
            return
        }
        val x = canvasX(event, index)
        val y = canvasY(event, index)
        when {
            pendingDrag -> updatePendingDrag(x, y)
            !drawing -> Unit
            activeDrawTool == Tool.LINE -> moveLine(x, y)
            activeDrawTool == Tool.SHAPE -> moveShape(x, y)
            else -> addSamples(event, index)
        }
    }

    private fun finishPointer(event: MotionEvent) {
        when {
            fillTracking -> finishFillTap(event)
            pendingDrag -> finishPendingDrag(event)
            !drawing -> Unit
            activeDrawTool == Tool.LINE -> commitLine(event)
            activeDrawTool == Tool.SHAPE -> finishShape(event)
            else -> commitStroke(event)
        }
    }

    private fun beginFillTrack(event: MotionEvent) {
        val toolType = event.getToolType(0)
        activePointerId = event.getPointerId(0)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        fillTracking = true
        fillMoved = false
        fillScreenX = event.getX(0)
        fillScreenY = event.getY(0)
        viewTransform.toCanvas(fillScreenX, fillScreenY, mapped)
        fillStartX = mapped[0]
        fillStartY = mapped[1]
    }

    private fun finishFillTap(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        val moved = fillMoved
        val startX = fillStartX
        val startY = fillStartY
        val screenX = fillScreenX
        val screenY = fillScreenY
        val ignorePalm = index >= 0 && ignoredPointer(event, index)
        endFillTrack()
        if (index < 0 || ignorePalm) return
        val x = event.getX(index)
        val y = event.getY(index)
        if (moved || hypot(x - screenX, y - screenY) > touchSlop) {
            Log.d(TAG, "fill drag ignored")
            return
        }
        if (pendingShape != null || pendingLine != null) {
            commitPendingOnly()
        }
        startFill(startX, startY)
    }

    private fun endFillTrack() {
        fillTracking = false
        fillMoved = false
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun startFill(x: Float, y: Float) {
        val bitmap = ink ?: return
        val widthPx = bitmap.width
        val heightPx = bitmap.height
        if (widthPx <= 0 || heightPx <= 0) return
        if (x < 0f || y < 0f || x >= widthPx || y >= heightPx) {
            Log.d(TAG, "fill outside")
            return
        }
        val seedX = x.roundToInt().coerceIn(0, widthPx - 1)
        val seedY = y.roundToInt().coerceIn(0, heightPx - 1)
        val pixels = IntArray(widthPx * heightPx)
        bitmap.getPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
        val background = document?.backgroundColor ?: Color.WHITE
        val tolerance = fillTolerance.coerceIn(0, 255)
        val gapPx = (fillGapDp * resources.displayMetrics.density).roundToInt().coerceIn(0, 32)
        val color = opaque(fillColor)
        val generation = ++fillGeneration
        fillInFlight = true
        onFillBusy(true)
        Log.d(TAG, "fill start seed=$seedX,$seedY tolerance=$tolerance gapPx=$gapPx color=${ColorMath.toHex(color)}")
        val started = System.nanoTime()
        try {
            fillExecutor.execute {
                var tooComplex = false
                val runs = try {
                    FloodFill.floodFill(
                        pixels = pixels,
                        w = widthPx,
                        h = heightPx,
                        seedX = seedX,
                        seedY = seedY,
                        background = background,
                        fillColor = color,
                        tolerance = tolerance,
                        gapPx = gapPx,
                        expandEdge = true,
                        report = {
                            tooComplex = true
                            Log.d(TAG, it)
                        },
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "fill failed ${e.message}")
                    null
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                val runCount = if (runs == null) 0 else runs.size / 3
                mainHandler.post {
                    if (generation != fillGeneration || !isAttachedToWindow) {
                        Log.d(TAG, "fill dropped runs=$runCount ms=$elapsedMs")
                        if (generation == fillGeneration && fillInFlight) {
                            fillInFlight = false
                            onFillBusy(false)
                        }
                        return@post
                    }
                    fillInFlight = false
                    onFillBusy(false)
                    if (runs == null) {
                        if (tooComplex) {
                            Toast.makeText(context, R.string.fill_too_complex, Toast.LENGTH_SHORT).show()
                        }
                        Log.d(TAG, "fill none ms=$elapsedMs tolerance=$tolerance gapPx=$gapPx")
                        return@post
                    }
                    val fill = DrawObject.Fill(
                        seedX = seedX,
                        seedY = seedY,
                        color = color,
                        alpha = 1f,
                        tolerance = tolerance,
                        gapPx = gapPx,
                        width = widthPx,
                        height = heightPx,
                        runs = runs,
                    )
                    stampFill(fill)
                    onCommit(fill)
                    Log.d(
                        TAG,
                        "fill commit runs=$runCount ms=$elapsedMs tolerance=$tolerance gapPx=$gapPx",
                    )
                }
            }
        } catch (e: Exception) {
            fillInFlight = false
            onFillBusy(false)
            Log.d(TAG, "fill rejected ${e.message}")
        }
    }

    private fun beginStroke(event: MotionEvent, index: Int, routed: Tool) {
        activePointerId = event.getPointerId(index)
        val toolType = event.getToolType(index)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        activeDrawTool = routed
        val settings = clampBrushSettings(if (routed == Tool.ERASER) eraserSettings else brushSettings)
        strokeBaseWidthPx = settings.sizeDp * resources.displayMetrics.density
        strokeAlpha = if (routed == Tool.ERASER) 1f else settings.opacity
        drawing = true
        strokePoints.clear()
        clearScratch()
        addCurrent(event, index)
        Log.d(TAG, "begin routed=$routed input=$inputType sizePx=$strokeBaseWidthPx alpha=$strokeAlpha")
    }

    private fun beginLine(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        val toolType = event.getToolType(index)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS
        activeDrawTool = Tool.LINE
        val settings = clampBrushSettings(brushSettings)
        strokeBaseWidthPx = settings.sizeDp * resources.displayMetrics.density
        strokeAlpha = settings.opacity
        drawing = true
        lineStartX = canvasX(event, index)
        lineStartY = canvasY(event, index)
        lineEndX = lineStartX
        lineEndY = lineStartY
        lastRawX = lineStartX
        lastRawY = lineStartY
        lineMagnet = false
        labelBuilder.setLength(0)
        clearScratch()
        Log.d(
            TAG,
            "line begin start=$lineStartX,$lineStartY snap=$lineSnapEnabled sizePx=$strokeBaseWidthPx alpha=$strokeAlpha",
        )
    }

    private fun moveLine(rawX: Float, rawY: Float) {
        val previousX = lineEndX
        val previousY = lineEndY
        lastRawX = rawX
        lastRawY = rawY
        lineMagnet = LineSnap.snapInto(
            lineStartX,
            lineStartY,
            rawX,
            rawY,
            LineSnap.SNAP_STEP_DEG,
            LineSnap.SNAP_TOLERANCE_DEG,
            lineSnapEnabled,
            snapOut,
        )
        lineEndX = snapOut[0]
        lineEndY = snapOut[1]
        val rawAngle = LineSnap.angleDeg(lineStartX, lineStartY, rawX, rawY)
        val snappedAngle = LineSnap.angleDeg(lineStartX, lineStartY, lineEndX, lineEndY)
        labelBuilder.setLength(0)
        if (showSnapLabel && lineMagnet) {
            val length = hypot(lineEndX - lineStartX, lineEndY - lineStartY)
            labelBuilder.append(snappedAngle.roundToInt())
            labelBuilder.append(" deg  ")
            labelBuilder.append(length.roundToInt())
            labelBuilder.append(" px")
        }
        scratchCanvas?.let { canvas ->
            clearScratch()
            if (lineStartX != lineEndX || lineStartY != lineEndY) {
                drawLineOn(canvas, lineStartX, lineStartY, lineEndX, lineEndY, strokeColor, strokeBaseWidthPx)
            }
        }
        invalidateSegment(lineStartX, lineStartY, previousX, previousY, strokeBaseWidthPx)
        invalidateSegment(lineStartX, lineStartY, lineEndX, lineEndY, strokeBaseWidthPx)
        Log.d(
            TAG,
            "line start=$lineStartX,$lineStartY end=$lineEndX,$lineEndY rawAngle=$rawAngle snappedAngle=$snappedAngle snap=$lineSnapEnabled",
        )
    }

    private fun commitLine(event: MotionEvent) {
        if (!drawing) return
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0 && !ignoredPointer(event, index)) {
            moveLine(canvasX(event, index), canvasY(event, index))
        }
        val x1 = lineStartX
        val y1 = lineStartY
        val x2 = lineEndX
        val y2 = lineEndY
        val width = strokeBaseWidthPx
        val alpha = strokeAlpha
        val type = inputType
        val length = hypot(x2 - x1, y2 - y1)
        val minimum = MIN_LINE_DP * resources.displayMetrics.density
        endGesture()
        labelBuilder.setLength(0)
        clearScratch()
        if (length < minimum) {
            Log.d(TAG, "line discarded length=$length min=$minimum")
            invalidateSegment(x1, y1, x2, y2, width)
            return
        }
        val line = DrawObject.Line(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            color = strokeColor,
            alpha = alpha,
            widthPx = width,
            inputType = type,
        )
        pendingLine = line
        paintPendingLine()
        onStagePending(line)
        invalidate()
        Log.d(
            TAG,
            "pending line start start=$x1,$y1 end=$x2,$y2 length=$length snap=$lineSnapEnabled color=${ColorMath.toHex(line.color)}",
        )
    }

    private fun commitStroke(event: MotionEvent) {
        if (!drawing) return
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0 && !ignoredPointer(event, index)) {
            addSamples(event, index)
        }
        val points = ArrayList<StrokePoint>(strokePoints.size)
        points.addAll(strokePoints)
        val toolUsed = activeDrawTool
        val width = strokeBaseWidthPx
        val alpha = strokeAlpha
        val type = inputType
        endGesture()
        if (points.isEmpty()) {
            clearScratch()
            invalidate()
            return
        }
        if (toolUsed == Tool.ERASER) {
            // Live erasing already punched the ink. Rebuild so the stored stroke matches replay,
            // including the end cap that is only known at pointer up.
            renderAll()
            stampErase(points, width, type)
        } else {
            stampBrush(points, width, alpha, type, strokeColor)
        }
        clearScratch()
        val obj: DrawObject = if (toolUsed == Tool.ERASER) {
            DrawObject.EraseStroke(points, width, type)
        } else {
            DrawObject.Stroke(
                points = points,
                color = strokeColor,
                baseWidthPx = width,
                inputType = type,
                alpha = alpha,
            )
        }
        if (toolUsed != Tool.ERASER) {
            Log.d(TAG, "commit stroke color=${ColorMath.toHex(strokeColor)} alpha=$alpha")
        }
        onCommit(obj)
        ink?.prepareToDraw()
        invalidate()
    }

    private fun discardActive() {
        when (activeDrawTool) {
            Tool.LINE -> discardLine()
            Tool.SHAPE -> discardShape()
            else -> discardStroke()
        }
    }

    private fun discardLine() {
        val x1 = lineStartX
        val y1 = lineStartY
        val x2 = lineEndX
        val y2 = lineEndY
        val width = strokeBaseWidthPx
        endGesture()
        labelBuilder.setLength(0)
        clearScratch()
        invalidateSegment(x1, y1, x2, y2, width)
        Log.d(TAG, "line cancel")
    }

    private fun discardStroke() {
        endGesture()
        strokePoints.clear()
        clearScratch()
        renderAll()
    }

    private fun endGesture() {
        drawing = false
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        strokePoints.clear()
    }

    private fun ignoredPointer(event: MotionEvent, index: Int): Boolean {
        return stylusActive && event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
    }

    private fun addSamples(event: MotionEvent, pointerIndex: Int) {
        val historySize = event.historySize
        for (h in 0 until historySize) {
            addPoint(
                canvasHistoricalX(event, pointerIndex, h),
                canvasHistoricalY(event, pointerIndex, h),
                event.getHistoricalPressure(pointerIndex, h),
                event.getHistoricalEventTime(h),
            )
        }
        addCurrent(event, pointerIndex)
    }

    private fun addCurrent(event: MotionEvent, pointerIndex: Int) {
        addPoint(
            canvasX(event, pointerIndex),
            canvasY(event, pointerIndex),
            event.getPressure(pointerIndex),
            event.eventTime,
        )
    }

    private fun addPoint(x: Float, y: Float, pressure: Float, timeMs: Long) {
        val last = strokePoints.lastOrNull()
        if (last != null && last.x == x && last.y == y) return
        val point = StrokePoint(x, y, pressure.coerceIn(0f, 1f), timeMs)
        val from = strokePoints.lastOrNull()
        val previous = if (strokePoints.size >= 2) strokePoints[strokePoints.size - 2] else null
        strokePoints.add(point)
        if (from == null) return
        StrokeMath.fillQuadSegment(previous, from, point, strokeBaseWidthPx, inputType, segment)
        if (activeDrawTool == Tool.ERASER) {
            val canvas = inkCanvas ?: return
            geometryXfer = clearMode
            try {
                drawSegment(canvas, Color.BLACK)
            } finally {
                geometryXfer = null
            }
        } else {
            val canvas = scratchCanvas ?: return
            drawSegment(canvas, strokeColor)
        }
    }

    private fun stampBrush(points: List<StrokePoint>, width: Float, alpha: Float, type: InputType, color: Int) {
        val canvas = scratchCanvas ?: return
        val target = inkCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawStrokeContents(canvas, points, color, width, type)
        scratch?.prepareToDraw()
        blitPaint.xfermode = null
        blitPaint.alpha = alphaByte(alpha)
        target.drawBitmap(scratch!!, 0f, 0f, blitPaint)
        clearScratch()
    }

    private fun stampLine(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        width: Float,
        alpha: Float,
    ) {
        if (x1 == x2 && y1 == y2) return
        val canvas = scratchCanvas ?: return
        val target = inkCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawLineOn(canvas, x1, y1, x2, y2, color, width)
        scratch?.prepareToDraw()
        blitPaint.xfermode = null
        blitPaint.alpha = alphaByte(alpha)
        target.drawBitmap(scratch!!, 0f, 0f, blitPaint)
        clearScratch()
    }

    private fun drawLineOn(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        width: Float,
    ) {
        path.rewind()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        strokePaint.xfermode = geometryXfer
        strokePaint.alpha = 255
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = color
        strokePaint.strokeWidth = width
        canvas.drawPath(path, strokePaint)
    }

    private fun invalidateSegment(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        val extra = if (showSnapLabel) labelPaint.textSize * 8f else 0f
        invalidateHull(x1, y1, x2, y2, x2, y2, width + extra)
    }

    private fun refreshLiveColor() {
        if (!drawing || activeDrawTool == Tool.ERASER) return
        when (activeDrawTool) {
            Tool.LINE -> moveLine(lastRawX, lastRawY)
            Tool.SHAPE -> moveShape(lastRawX, lastRawY)
            else -> repaintLiveBrush()
        }
    }

    private fun repaintLiveBrush() {
        val canvas = scratchCanvas ?: return
        clearScratch()
        if (strokePoints.isNotEmpty()) {
            drawStrokeContents(canvas, strokePoints, strokeColor, strokeBaseWidthPx, inputType)
        }
        invalidate()
    }

    private fun refreshShapePreview(reason: String) {
        if (!drawing || activeDrawTool != Tool.SHAPE) return
        Log.d(
            TAG,
            "shape update $reason kind=$shapeKind constrain=$shapeConstrain filled=$shapeFilled",
        )
        moveShape(lastRawX, lastRawY)
    }

    private fun beginShape(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        val toolType = event.getToolType(index)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS
        activeDrawTool = Tool.SHAPE
        val settings = clampBrushSettings(brushSettings)
        strokeBaseWidthPx = settings.sizeDp * resources.displayMetrics.density
        strokeAlpha = settings.opacity
        drawing = true
        shapeStartX = canvasX(event, index)
        shapeStartY = canvasY(event, index)
        shapeEndX = shapeStartX
        shapeEndY = shapeStartY
        lastRawX = shapeStartX
        lastRawY = shapeStartY
        clearScratch()
        Log.d(
            TAG,
            "shape begin kind=$shapeKind start=$shapeStartX,$shapeStartY constrain=$shapeConstrain filled=$shapeFilled sizePx=$strokeBaseWidthPx alpha=$strokeAlpha",
        )
    }

    private fun moveShape(rawX: Float, rawY: Float) {
        ShapeGeometry.boundsInto(
            shapeKind,
            shapeStartX,
            shapeStartY,
            shapeEndX,
            shapeEndY,
            shapeFilled,
            strokeBaseWidthPx,
            minArrowHeadPx(),
            prevShapeBounds,
        )
        lastRawX = rawX
        lastRawY = rawY
        ShapeGeometry.constrainInto(
            shapeKind,
            shapeStartX,
            shapeStartY,
            rawX,
            rawY,
            shapeConstrain,
            shapeEndOut,
        )
        shapeEndX = shapeEndOut[0]
        shapeEndY = shapeEndOut[1]
        scratchCanvas?.let { canvas ->
            clearScratch()
            if (shapeStartX != shapeEndX || shapeStartY != shapeEndY) {
                drawShapeOn(
                    canvas,
                    shapeKind,
                    shapeStartX,
                    shapeStartY,
                    shapeEndX,
                    shapeEndY,
                    shapeFilled,
                    colorForShape(strokeColor, fillColor, shapeKind, shapeFilled),
                    strokeBaseWidthPx,
                )
            }
        }
        ShapeGeometry.boundsInto(
            shapeKind,
            shapeStartX,
            shapeStartY,
            shapeEndX,
            shapeEndY,
            shapeFilled,
            strokeBaseWidthPx,
            minArrowHeadPx(),
            shapeBounds,
        )
        invalidateBounds(prevShapeBounds)
        invalidateBounds(shapeBounds)
    }

    private fun finishShape(event: MotionEvent) {
        if (!drawing) return
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0 && !ignoredPointer(event, index)) {
            moveShape(canvasX(event, index), canvasY(event, index))
        }
        val kind = shapeKind
        val x1 = shapeStartX
        val y1 = shapeStartY
        val x2 = shapeEndX
        val y2 = shapeEndY
        val filled = shapeFilled
        val constrain = shapeConstrain
        val width = strokeBaseWidthPx
        val alpha = strokeAlpha
        val type = inputType
        val span = hypot(x2 - x1, y2 - y1)
        val minimum = MIN_SHAPE_DP * resources.displayMetrics.density
        endGesture()
        if (span < minimum) {
            clearScratch()
            Log.d(TAG, "shape discarded kind=$kind span=$span min=$minimum constrain=$constrain filled=$filled")
            ShapeGeometry.boundsInto(kind, x1, y1, x2, y2, filled, width, minArrowHeadPx(), shapeBounds)
            invalidateBounds(shapeBounds)
            return
        }
        val shape = DrawObject.Shape(
            kind = kind,
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            filled = filled,
            color = colorForShape(strokeColor, fillColor, kind, filled),
            alpha = alpha,
            widthPx = width,
            inputType = type,
        )
        pendingShape = shape
        paintPendingShape()
        onStagePending(shape)
        invalidate()
        Log.d(
            TAG,
            "pending start kind=$kind start=$x1,$y1 end=$x2,$y2 constrain=$constrain filled=$filled span=$span color=${ColorMath.toHex(shape.color)}",
        )
    }

    private fun discardShape() {
        val kind = shapeKind
        val x1 = shapeStartX
        val y1 = shapeStartY
        val x2 = shapeEndX
        val y2 = shapeEndY
        val filled = shapeFilled
        val width = strokeBaseWidthPx
        endGesture()
        clearScratch()
        ShapeGeometry.boundsInto(kind, x1, y1, x2, y2, filled, width, minArrowHeadPx(), shapeBounds)
        invalidateBounds(shapeBounds)
        Log.d(TAG, "shape cancel kind=$kind constrain=$shapeConstrain filled=$shapeFilled")
    }

    private fun stampFill(fill: DrawObject.Fill) {
        val canvas = inkCanvas ?: return
        val bitmap = ink ?: return
        fillPaint.xfermode = null
        fillPaint.color = fill.color
        fillPaint.alpha = alphaByte(fill.alpha)
        val runs = fill.runs
        var index = 0
        while (index + 2 < runs.size) {
            val y = runs[index]
            var x = runs[index + 1]
            val length = runs[index + 2]
            index += 3
            if (y < 0 || y >= bitmap.height || length <= 0) continue
            var end = x + length
            if (x < 0) x = 0
            if (end > bitmap.width) end = bitmap.width
            if (x >= end) continue
            canvas.drawRect(x.toFloat(), y.toFloat(), end.toFloat(), (y + 1).toFloat(), fillPaint)
        }
    }

    private fun stampShape(obj: DrawObject.Shape) {
        if (obj.x1 == obj.x2 && obj.y1 == obj.y2) return
        val canvas = scratchCanvas ?: return
        val target = inkCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawShapeRotated(canvas, obj)
        scratch?.prepareToDraw()
        blitPaint.xfermode = null
        blitPaint.alpha = alphaByte(obj.alpha)
        target.drawBitmap(scratch!!, 0f, 0f, blitPaint)
        clearScratch()
    }

    private fun drawShapeRotated(canvas: Canvas, shape: DrawObject.Shape) {
        if (shape.rotationDeg == 0f) {
            drawShapeOn(
                canvas,
                shape.kind,
                shape.x1,
                shape.y1,
                shape.x2,
                shape.y2,
                shape.filled,
                shape.color,
                shape.widthPx,
            )
            return
        }
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, shapeBox)
        val cx = (shapeBox[0] + shapeBox[2]) * 0.5f
        val cy = (shapeBox[1] + shapeBox[3]) * 0.5f
        canvas.save()
        canvas.rotate(shape.rotationDeg, cx, cy)
        drawShapeOn(
            canvas,
            shape.kind,
            shape.x1,
            shape.y1,
            shape.x2,
            shape.y2,
            shape.filled,
            shape.color,
            shape.widthPx,
        )
        canvas.restore()
    }

    private fun paintPendingShape() {
        val shape = pendingShape ?: return
        val canvas = scratchCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawShapeRotated(canvas, shape)
    }

    private fun paintPendingLine() {
        val line = pendingLine ?: return
        val canvas = scratchCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawLineOn(canvas, line.x1, line.y1, line.x2, line.y2, line.color, line.widthPx)
    }

    private fun beginLineDrag(handle: LineHandle?, x: Float, y: Float, event: MotionEvent, index: Int) {
        val line = pendingLine ?: return
        activePointerId = event.getPointerId(index)
        val toolType = event.getToolType(index)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        pendingDrag = true
        dragOrigin = null
        dragHandle = null
        lineHandle = handle
        lineDragOrigin = line
        endpointSnap = lineSnapEnabled
        dragStartX = x
        dragStartY = y
        Log.d(TAG, "pending line pointer handle=${handle ?: "move"}")
    }

    private fun updateLineDrag(x: Float, y: Float) {
        val origin = lineDragOrigin ?: return
        val previous = pendingLine
        val handle = lineHandle
        val next = if (handle == null) {
            LineTransform.movedLine(origin, x - dragStartX, y - dragStartY)
        } else {
            LineTransform.draggedEndpoint(origin, handle, x, y, endpointSnap, minLinePx(), lineOut)
            origin.copy(x1 = lineOut[0], y1 = lineOut[1], x2 = lineOut[2], y2 = lineOut[3])
        }
        pendingLine = next
        paintPendingLine()
        if (previous != null) invalidateLine(previous)
        invalidateLine(next)
    }

    private fun beginPendingDrag(handle: Handle?, x: Float, y: Float, event: MotionEvent, index: Int) {
        val shape = pendingShape ?: return
        activePointerId = event.getPointerId(index)
        val toolType = event.getToolType(index)
        inputType = inputTypeOf(toolType)
        stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        pendingDrag = true
        lineDragOrigin = null
        lineHandle = null
        dragHandle = handle
        dragOrigin = shape
        dragStartX = x
        dragStartY = y
        Log.d(TAG, "pending pointer handle=${handle ?: "move"}")
    }

    private fun updatePendingDrag(x: Float, y: Float) {
        if (lineDragOrigin != null) {
            updateLineDrag(x, y)
            return
        }
        val origin = dragOrigin ?: return
        val previous = pendingShape
        val handle = dragHandle
        val next = when (handle) {
            null -> ShapeTransform.moved(origin, x - dragStartX, y - dragStartY)
            Handle.ROTATE -> ShapeTransform.rotated(origin, x, y, lineSnapEnabled)
            else -> ShapeTransform.resized(origin, handle, x, y, shapeConstrain, minSidePx())
        }
        pendingShape = if (next.filled == shapeFilled) next else next.copy(filled = shapeFilled)
        paintPendingShape()
        if (previous != null) invalidateShape(previous)
        pendingShape?.let { invalidateShape(it) }
    }

    private fun finishPendingDrag(event: MotionEvent) {
        if (!pendingDrag) return
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0 && !ignoredPointer(event, index)) {
            updatePendingDrag(canvasX(event, index), canvasY(event, index))
        }
        if (lineDragOrigin != null) {
            val line = pendingLine
            pendingDrag = false
            lineDragOrigin = null
            lineHandle = null
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            if (line != null) {
                paintPendingLine()
                onUpdatePending(line)
                Log.d(TAG, "pending line edit start=${line.x1},${line.y1} end=${line.x2},${line.y2}")
            }
            return
        }
        val shape = pendingShape
        pendingDrag = false
        dragOrigin = null
        dragHandle = null
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (shape != null) {
            paintPendingShape()
            onUpdatePending(shape)
            Log.d(TAG, "pending edit kind=${shape.kind} rotation=${shape.rotationDeg}")
        }
    }

    private fun cancelPendingDrag() {
        pendingDrag = false
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (lineDragOrigin != null) {
            pendingLine = lineDragOrigin
            lineDragOrigin = null
            lineHandle = null
            paintPendingLine()
            invalidate()
            Log.d(TAG, "pending line cancel drag")
            return
        }
        pendingShape = dragOrigin ?: pendingShape
        dragOrigin = null
        dragHandle = null
        paintPendingShape()
        invalidate()
        Log.d(TAG, "pending cancel drag")
    }

    private fun commitPendingOnly() {
        flushPendingEdit()
        val shape = pendingShape
        if (shape != null) {
            pendingShape = null
            clearScratch()
            stampShape(shape)
            onCommitPending()
            ink?.prepareToDraw()
            invalidate()
            Log.d(TAG, "pending commit kind=${shape.kind} rotation=${shape.rotationDeg}")
            return
        }
        val line = pendingLine ?: return
        pendingLine = null
        clearScratch()
        stampLine(line.x1, line.y1, line.x2, line.y2, line.color, line.widthPx, line.alpha)
        onCommitPending()
        ink?.prepareToDraw()
        invalidate()
        Log.d(TAG, "pending line commit start=${line.x1},${line.y1} end=${line.x2},${line.y2}")
    }

    private fun drawHandles(canvas: Canvas, shape: DrawObject.Shape) {
        ShapeGeometry.normalizedBoxInto(shape.x1, shape.y1, shape.x2, shape.y2, shapeBox)
        val cx = (shapeBox[0] + shapeBox[2]) * 0.5f
        val cy = (shapeBox[1] + shapeBox[3]) * 0.5f
        cornerScratch[0] = shapeBox[0]
        cornerScratch[1] = shapeBox[1]
        cornerScratch[2] = shapeBox[2]
        cornerScratch[3] = shapeBox[1]
        cornerScratch[4] = shapeBox[2]
        cornerScratch[5] = shapeBox[3]
        cornerScratch[6] = shapeBox[0]
        cornerScratch[7] = shapeBox[3]
        if (shape.rotationDeg != 0f) {
            cornerMatrix.setRotate(shape.rotationDeg, cx, cy)
            cornerMatrix.mapPoints(cornerScratch)
        }
        mapPointsToScreen(cornerScratch, 4)
        handleLinePaint.strokeWidth = dp(1f)
        canvas.drawLine(cornerScratch[0], cornerScratch[1], cornerScratch[2], cornerScratch[3], handleLinePaint)
        canvas.drawLine(cornerScratch[2], cornerScratch[3], cornerScratch[4], cornerScratch[5], handleLinePaint)
        canvas.drawLine(cornerScratch[4], cornerScratch[5], cornerScratch[6], cornerScratch[7], handleLinePaint)
        canvas.drawLine(cornerScratch[6], cornerScratch[7], cornerScratch[0], cornerScratch[1], handleLinePaint)
        ShapeTransform.handlePositions(shape, canvasUnits(rotateOffsetPx()), handleOut)
        mapPointsToScreen(handleOut, Handle.entries.size)
        val top = Handle.TOP.ordinal
        val rotate = Handle.ROTATE.ordinal
        handleLinePaint.strokeWidth = dp(1.5f)
        canvas.drawLine(
            handleOut[top * 2],
            handleOut[top * 2 + 1],
            handleOut[rotate * 2],
            handleOut[rotate * 2 + 1],
            handleLinePaint,
        )
        val radius = dp(6f)
        for (i in 0 until Handle.entries.size) {
            canvas.drawCircle(handleOut[i * 2], handleOut[i * 2 + 1], radius, handleFillPaint)
            canvas.drawCircle(handleOut[i * 2], handleOut[i * 2 + 1], radius, handleLinePaint)
        }
    }

    private fun invalidateShape(shape: DrawObject.Shape) {
        ShapeTransform.rotatedBoundsInto(
            shape,
            shape.widthPx,
            canvasUnits(rotateOffsetPx()),
            minArrowHeadPx(),
            canvasUnits(dp(8f)),
            shapeBounds,
        )
        invalidateBounds(shapeBounds)
    }

    private fun drawLineHandles(canvas: Canvas, line: DrawObject.Line) {
        val radius = dp(6f)
        handleLinePaint.strokeWidth = dp(1.5f)
        viewTransform.toScreen(line.x1, line.y1, mapped)
        canvas.drawCircle(mapped[0], mapped[1], radius, handleFillPaint)
        canvas.drawCircle(mapped[0], mapped[1], radius, handleLinePaint)
        viewTransform.toScreen(line.x2, line.y2, mapped)
        canvas.drawCircle(mapped[0], mapped[1], radius, handleFillPaint)
        canvas.drawCircle(mapped[0], mapped[1], radius, handleLinePaint)
    }

    private fun invalidateLine(line: DrawObject.Line) {
        invalidateHull(
            line.x1,
            line.y1,
            line.x2,
            line.y2,
            line.x2,
            line.y2,
            max(line.widthPx, canvasUnits(dp(16f))),
        )
    }

    private fun minLinePx(): Float = dp(MIN_LINE_DP)

    private fun rotateOffsetPx(): Float = dp(ShapeTransform.ROTATE_OFFSET_DP)

    private fun handleTouchPx(): Float = dp(ShapeTransform.HANDLE_TOUCH_DP)

    private fun minSidePx(): Float = dp(ShapeTransform.MIN_SIDE_DP)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun drawShapeOn(
        canvas: Canvas,
        kind: ShapeKind,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        filled: Boolean,
        color: Int,
        width: Float,
    ) {
        strokePaint.xfermode = geometryXfer
        strokePaint.alpha = 255
        strokePaint.color = color
        strokePaint.strokeWidth = width
        when (kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
                ShapeGeometry.normalizedBoxInto(x1, y1, x2, y2, shapeBox)
                shapeRect.set(shapeBox[0], shapeBox[1], shapeBox[2], shapeBox[3])
                strokePaint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
                strokePaint.strokeJoin = if (kind == ShapeKind.ELLIPSE) Paint.Join.ROUND else Paint.Join.MITER
                strokePaint.strokeMiter = ShapeGeometry.MITER_LIMIT
                if (kind == ShapeKind.ELLIPSE) {
                    canvas.drawOval(shapeRect, strokePaint)
                } else {
                    canvas.drawRect(shapeRect, strokePaint)
                }
            }
            ShapeKind.TRIANGLE -> {
                ShapeGeometry.trianglePointsInto(x1, y1, x2, y2, shapeTri)
                path.rewind()
                path.moveTo(shapeTri[0], shapeTri[1])
                path.lineTo(shapeTri[2], shapeTri[3])
                path.lineTo(shapeTri[4], shapeTri[5])
                path.close()
                strokePaint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
                strokePaint.strokeJoin = Paint.Join.MITER
                strokePaint.strokeMiter = ShapeGeometry.MITER_LIMIT
                canvas.drawPath(path, strokePaint)
            }
            ShapeKind.ARROW -> {
                if (x1 != x2 || y1 != y2) {
                    path.rewind()
                    path.moveTo(x1, y1)
                    path.lineTo(x2, y2)
                    strokePaint.style = Paint.Style.STROKE
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(path, strokePaint)
                }
                val shaft = hypot(x2 - x1, y2 - y1)
                val headLength = ShapeGeometry.arrowHeadLength(width, shaft, minArrowHeadPx())
                if (headLength <= 0f) return
                ShapeGeometry.arrowHeadInto(x1, y1, x2, y2, headLength, shapeHead)
                path.rewind()
                path.moveTo(shapeHead[2], shapeHead[3])
                path.lineTo(shapeHead[0], shapeHead[1])
                path.lineTo(shapeHead[4], shapeHead[5])
                if (filled) {
                    path.close()
                    strokePaint.style = Paint.Style.FILL
                } else {
                    strokePaint.style = Paint.Style.STROKE
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    private fun minArrowHeadPx(): Float {
        return ShapeGeometry.MIN_ARROW_HEAD_DP * resources.displayMetrics.density
    }

    private fun invalidateBounds(bounds: FloatArray) {
        invalidateCanvasRect(bounds[0], bounds[1], bounds[2], bounds[3], screenPad = 2f)
    }

    private fun stampErase(points: List<StrokePoint>, width: Float, type: InputType) {
        val canvas = inkCanvas ?: return
        geometryXfer = clearMode
        try {
            drawStrokeContents(canvas, points, Color.BLACK, width, type)
        } finally {
            geometryXfer = null
        }
    }

    private fun drawStrokeContents(
        canvas: Canvas,
        points: List<StrokePoint>,
        color: Int,
        width: Float,
        type: InputType,
    ) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            drawDot(canvas, points[0], color, width, type)
            return
        }
        for (i in 1 until points.size) {
            val previous = if (i >= 2) points[i - 2] else null
            StrokeMath.fillQuadSegment(previous, points[i - 1], points[i], width, type, segment)
            drawSegment(canvas, color)
        }
        drawTailOn(canvas, points[points.lastIndex - 1], points.last(), color, width, type)
    }

    private fun drawDot(canvas: Canvas, point: StrokePoint, color: Int, width: Float, type: InputType) {
        val diameter = StrokeMath.widthFor(point, width, type)
        strokePaint.xfermode = geometryXfer
        strokePaint.alpha = 255
        strokePaint.color = color
        strokePaint.style = Paint.Style.FILL
        canvas.drawCircle(point.x, point.y, diameter * 0.5f, strokePaint)
        strokePaint.style = Paint.Style.STROKE
        if (drawing) {
            invalidateHull(point.x, point.y, point.x, point.y, point.x, point.y, diameter)
        }
    }

    private fun drawSegment(canvas: Canvas, color: Int) {
        path.rewind()
        path.moveTo(segment.startX, segment.startY)
        path.quadTo(segment.controlX, segment.controlY, segment.endX, segment.endY)
        strokePaint.xfermode = geometryXfer
        strokePaint.alpha = 255
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = color
        strokePaint.strokeWidth = segment.width
        canvas.drawPath(path, strokePaint)
        if (drawing) {
            invalidateHull(
                segment.startX,
                segment.startY,
                segment.controlX,
                segment.controlY,
                segment.endX,
                segment.endY,
                segment.width,
            )
        }
    }

    private fun drawTailOn(
        canvas: Canvas,
        from: StrokePoint,
        to: StrokePoint,
        color: Int,
        width: Float,
        type: InputType,
    ) {
        val startX = (from.x + to.x) * 0.5f
        val startY = (from.y + to.y) * 0.5f
        val startPressure = (from.pressure + to.pressure) * 0.5f
        val strokeWidth = StrokeMath.interpolatedWidth(startPressure, to.pressure, width, type)
        path.rewind()
        path.moveTo(startX, startY)
        path.lineTo(to.x, to.y)
        strokePaint.xfermode = geometryXfer
        strokePaint.alpha = 255
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidth
        canvas.drawPath(path, strokePaint)
        if (drawing) {
            invalidateHull(startX, startY, to.x, to.y, to.x, to.y, strokeWidth)
        }
    }

    private fun clearScratch() {
        scratchCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    private fun alphaByte(alpha: Float): Int {
        return (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun invalidateHull(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        width: Float,
    ) {
        val pad = width * 0.5f
        val left = min(x0, min(x1, x2)) - pad
        val top = min(y0, min(y1, y2)) - pad
        val right = max(x0, max(x1, x2)) + pad
        val bottom = max(y0, max(y1, y2)) + pad
        val labelPad = if (showSnapLabel) labelPaint.textSize * 8f else 0f
        invalidateCanvasRect(left, top, right, bottom, screenPad = 2f + labelPad)
    }

    private fun onEyedropperTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = event.getToolType(0)
                stylusActive = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                dropperPointer = event.getPointerId(0)
                showLoupe(event.getX(0), event.getY(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(dropperPointer)
                if (index >= 0) showLoupe(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                    event.getPointerId(event.actionIndex) != dropperPointer
                ) {
                    return
                }
                val index = event.findPointerIndex(dropperPointer)
                if (index < 0) return
                val color = sampleVisible(event.getX(index), event.getY(index))
                loupeVisible = false
                dropperPointer = MotionEvent.INVALID_POINTER_ID
                stylusActive = false
                invalidate()
                Log.d(TAG, "eyedropper pick color=${ColorMath.toHex(color)} target=$eyedropperTarget")
                onEyedropperPick(color)
            }
            MotionEvent.ACTION_CANCEL -> {
                loupeVisible = false
                dropperPointer = MotionEvent.INVALID_POINTER_ID
                stylusActive = false
                invalidate()
                Log.d(TAG, "eyedropper cancel")
                onEyedropperCancel()
            }
            else -> Unit
        }
    }

    private fun showLoupe(x: Float, y: Float) {
        loupeX = x
        loupeY = y
        loupeColor = sampleVisible(x, y)
        loupeVisible = true
        invalidate()
    }

    private fun sampleVisible(x: Float, y: Float): Int {
        val background = document?.backgroundColor ?: Color.WHITE
        val bitmap = ink
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return opaque(background)
        EyedropperMath.toBitmapCoords(
            x,
            y,
            viewTransform.scale,
            viewTransform.tx,
            viewTransform.ty,
            bitmap.width,
            bitmap.height,
            eyedropperOut,
            clamp = true,
        )
        val bx = eyedropperOut[0].roundToInt().coerceIn(0, bitmap.width - 1)
        val by = eyedropperOut[1].roundToInt().coerceIn(0, bitmap.height - 1)
        return ColorMath.compositeOver(bitmap.getPixel(bx, by), background)
    }

    private fun drawLoupe(canvas: Canvas) {
        val diameter = dp(96f)
        val radius = diameter * 0.5f
        val gap = dp(20f)
        var centerX = loupeX
        var centerY = loupeY - diameter - gap
        if (centerY < radius) centerY = loupeY + diameter + gap
        val maxX = (width - radius).coerceAtLeast(radius)
        val maxY = (height - radius).coerceAtLeast(radius)
        centerX = centerX.coerceIn(radius, maxX)
        centerY = centerY.coerceIn(radius, maxY)
        viewTransform.toCanvas(loupeX, loupeY, mapped)
        val srcSpan = diameter / 8f
        val srcLeft = mapped[0] - srcSpan * 0.5f
        val srcTop = mapped[1] - srcSpan * 0.5f
        loupeSrc.set(
            srcLeft.toInt(),
            srcTop.toInt(),
            (srcLeft + srcSpan).toInt().coerceAtLeast(srcLeft.toInt() + 1),
            (srcTop + srcSpan).toInt().coerceAtLeast(srcTop.toInt() + 1),
        )
        loupeDst.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val checkpoint = canvas.save()
        loupePath.rewind()
        loupePath.addCircle(centerX, centerY, radius, Path.Direction.CW)
        canvas.clipPath(loupePath)
        canvas.drawColor(document?.backgroundColor ?: Color.WHITE)
        ink?.let { bitmap -> canvas.drawBitmap(bitmap, loupeSrc, loupeDst, null) }
        canvas.restoreToCount(checkpoint)
        loupeStrokePaint.strokeWidth = dp(2f)
        loupeStrokePaint.color = Color.BLACK
        canvas.drawCircle(centerX, centerY, radius, loupeStrokePaint)
        val arm = dp(8f)
        canvas.drawLine(centerX - arm, centerY, centerX + arm, centerY, loupeStrokePaint)
        canvas.drawLine(centerX, centerY - arm, centerX, centerY + arm, loupeStrokePaint)
        writeHex(loupeColor)
        val textWidth = loupeTextPaint.measureText(hexChars, 0, 7)
        val chipHeight = dp(28f)
        val chipWidth = textWidth + dp(36f)
        var chipTop = centerY + radius + dp(6f)
        if (chipTop + chipHeight > height) chipTop = centerY - radius - dp(6f) - chipHeight
        var chipLeft = centerX - chipWidth * 0.5f
        if (chipLeft < 0f) chipLeft = 0f
        if (chipLeft + chipWidth > width) chipLeft = (width - chipWidth).coerceAtLeast(0f)
        loupeChip.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight)
        loupeFillPaint.color = Color.WHITE
        canvas.drawRoundRect(loupeChip, dp(6f), dp(6f), loupeFillPaint)
        loupeStrokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(loupeChip, dp(6f), dp(6f), loupeStrokePaint)
        val swatch = dp(16f)
        val swatchLeft = chipLeft + dp(6f)
        val swatchTop = chipTop + (chipHeight - swatch) * 0.5f
        loupeChip.set(swatchLeft, swatchTop, swatchLeft + swatch, swatchTop + swatch)
        loupeFillPaint.color = loupeColor
        canvas.drawRect(loupeChip, loupeFillPaint)
        loupeTextPaint.color = Color.BLACK
        val textY = chipTop + chipHeight * 0.5f - (loupeTextPaint.ascent() + loupeTextPaint.descent()) * 0.5f
        canvas.drawText(hexChars, 0, 7, chipLeft + dp(28f), textY, loupeTextPaint)
    }

    private fun writeHex(argb: Int) {
        val rgb = argb and 0x00FFFFFF
        for (i in 0 until 6) {
            val nibble = (rgb shr ((5 - i) * 4)) and 0xF
            hexChars[i + 1] = HEX_DIGITS[nibble]
        }
    }

    private fun logEvent(event: MotionEvent) {
        val index = event.actionIndex.coerceAtMost((event.pointerCount - 1).coerceAtLeast(0))
        val toolType = event.getToolType(index)
        val kind = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "unknown"
        }
        val routed = InputRouting.toolForEvent(tool, toolType, event.buttonState)
        val stylusButtons = InputRouting.BUTTON_STYLUS_PRIMARY or InputRouting.BUTTON_STYLUS_SECONDARY
        if (event.buttonState and stylusButtons != 0) {
            Log.d(TAG, "stylus button held buttonState=${event.buttonState} toolType=$kind")
        }
        Log.d(
            TAG,
            "selected=$tool routed=${routed ?: "none"} tool=$kind buttonState=${event.buttonState} pressure=${event.getPressure(index)} action=${event.actionMasked}",
        )
    }

    private fun shouldStartNavigate(event: MotionEvent): Boolean {
        val mode = InputRouting.modeForPointer(
            event.getToolType(event.actionIndex),
            event.pointerCount,
            stylusDown = stylusActive,
        )
        return mode == GestureMode.NAVIGATE
    }

    private fun startNavigate(event: MotionEvent) {
        if (eyedropperActive) {
            loupeVisible = false
            dropperPointer = MotionEvent.INVALID_POINTER_ID
            Log.d(TAG, "eyedropper cancel gesture")
            invalidate()
        }
        if (fillTracking) {
            endFillTrack()
            Log.d(TAG, "fill tap cancelled")
        } else if (pendingDrag) {
            cancelPendingDrag()
        } else if (drawing) {
            discardActive()
        }
        navigating = true
        navFrozen = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        rememberFocus(event, exclude = -1)
    }

    private fun onNavigateTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (navFrozen || event.pointerCount < 2) return
                val prevX = navFocusX
                val prevY = navFocusY
                val prevSpan = navSpan
                rememberFocus(event, exclude = -1)
                val factor = if (prevSpan > 1f && navSpan > 1f) navSpan / prevSpan else 1f
                viewTransform.pinch(prevX, prevY, navFocusX, navFocusY, factor)
                constrainView()
                publishView()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                navFrozen = false
                rememberFocus(event, exclude = -1)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = event.pointerCount - 1
                if (remaining < 2) {
                    navFrozen = true
                } else {
                    navFrozen = false
                    rememberFocus(event, exclude = event.actionIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                navigating = false
                navFrozen = false
                stylusActive = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            else -> Unit
        }
    }

    private fun rememberFocus(event: MotionEvent, exclude: Int) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        var first = -1
        var second = -1
        for (i in 0 until event.pointerCount) {
            if (i == exclude) continue
            if (first < 0) {
                first = i
            } else if (second < 0) {
                second = i
            }
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return
        navFocusX = sumX / count
        navFocusY = sumY / count
        navSpan = if (second >= 0) {
            hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
        } else {
            0f
        }
    }

    private fun stepZoom(factor: Float) {
        if (width <= 0 || height <= 0) return
        viewTransform.zoomAround(width / 2f, height / 2f, factor)
        constrainView()
        publishView()
        invalidate()
    }

    private fun constrainView() {
        viewTransform.clamp(
            canvasWidth(),
            canvasHeight(),
            width.toFloat(),
            height.toFloat(),
            dp(ViewTransform.MIN_VISIBLE_DP),
        )
        syncMatrix()
    }

    private fun ensureCanvasBitmaps(): Boolean {
        val doc = document ?: return false
        if (doc.width <= 0 || doc.height <= 0) {
            if (!CanvasLimits.withinCap(width, height)) return false
            doc.width = width
            doc.height = height
        } else if (!CanvasLimits.withinCap(doc.width, doc.height)) {
            return false
        }
        val existing = ink
        if (existing != null && !existing.isRecycled &&
            existing.width == doc.width && existing.height == doc.height && scratch != null
        ) {
            doc.bitmap = existing
            return false
        }
        cancelOutstandingFill()
        dropLiveGesture()
        existing?.recycle()
        scratch?.recycle()
        val created = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val scratchBitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        ink = created
        inkCanvas = Canvas(created)
        scratch = scratchBitmap
        scratchCanvas = Canvas(scratchBitmap)
        doc.bitmap = created
        return true
    }

    private fun dropLiveGesture() {
        if (drawing) {
            drawing = false
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            strokePoints.clear()
        }
        if (pendingDrag) {
            pendingDrag = false
            if (lineDragOrigin != null) {
                pendingLine = lineDragOrigin
                lineDragOrigin = null
                lineHandle = null
            } else {
                pendingShape = dragOrigin ?: pendingShape
                dragOrigin = null
                dragHandle = null
            }
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun applyPendingFit() {
        if (fitNonce == appliedFitNonce || width <= 0 || height <= 0) return
        if ((document?.width ?: 0) <= 0) return
        appliedFitNonce = fitNonce
        viewTransform.fit(
            canvasWidth(),
            canvasHeight(),
            width.toFloat(),
            height.toFloat(),
            dp(ViewTransform.FIT_MARGIN_DP),
        )
        constrainView()
        publishView()
        invalidate()
    }

    private fun applyViewRequest(publish: Boolean) {
        if (width <= 0 || height <= 0) return
        viewTransform.scale = viewScaleRequest.coerceIn(ViewTransform.MIN_SCALE, ViewTransform.MAX_SCALE)
        val hadCenter = !viewCenterXRequest.isNaN() && !viewCenterYRequest.isNaN()
        if (hadCenter) {
            viewTransform.centerOn(
                viewCenterXRequest,
                viewCenterYRequest,
                width.toFloat(),
                height.toFloat(),
            )
            constrainView()
        } else {
            viewTransform.tx = 0f
            viewTransform.ty = 0f
            syncMatrix()
        }
        invalidate()
        if (publish && hadCenter) publishView()
    }

    private fun publishView() {
        if (width <= 0 || height <= 0) return
        val scale = viewTransform.scale
        val centerX = viewTransform.centerX(width.toFloat())
        val centerY = viewTransform.centerY(height.toFloat())
        viewScaleRequest = scale
        viewCenterXRequest = centerX
        viewCenterYRequest = centerY
        Log.d(TAG, "view scale=$scale tx=${viewTransform.tx} ty=${viewTransform.ty}")
        onViewChanged(scale, centerX, centerY)
    }

    private fun syncMatrix() {
        viewMatrix.setScale(viewTransform.scale, viewTransform.scale)
        viewMatrix.postTranslate(viewTransform.tx, viewTransform.ty)
    }

    private fun identityView(): Boolean {
        return viewTransform.scale == 1f && viewTransform.tx == 0f && viewTransform.ty == 0f
    }

    private fun bitmapPaint(): Paint? {
        if (identityView()) return null
        return if (viewTransform.scale >= ViewTransform.CRISP_SCALE) crispBitmapPaint else smoothBitmapPaint
    }

    private fun canvasWidth(): Float {
        val doc = document
        if (doc != null && doc.width > 0) return doc.width.toFloat()
        val bitmap = ink
        if (bitmap != null && bitmap.width > 0) return bitmap.width.toFloat()
        return width.toFloat().coerceAtLeast(1f)
    }

    private fun canvasHeight(): Float {
        val doc = document
        if (doc != null && doc.height > 0) return doc.height.toFloat()
        val bitmap = ink
        if (bitmap != null && bitmap.height > 0) return bitmap.height.toFloat()
        return height.toFloat().coerceAtLeast(1f)
    }

    private fun canvasX(event: MotionEvent, index: Int): Float {
        viewTransform.toCanvas(event.getX(index), event.getY(index), mapped)
        return mapped[0]
    }

    private fun canvasY(event: MotionEvent, index: Int): Float {
        viewTransform.toCanvas(event.getX(index), event.getY(index), mapped)
        return mapped[1]
    }

    private fun canvasHistoricalX(event: MotionEvent, index: Int, history: Int): Float {
        viewTransform.toCanvas(
            event.getHistoricalX(index, history),
            event.getHistoricalY(index, history),
            mapped,
        )
        return mapped[0]
    }

    private fun canvasHistoricalY(event: MotionEvent, index: Int, history: Int): Float {
        viewTransform.toCanvas(
            event.getHistoricalX(index, history),
            event.getHistoricalY(index, history),
            mapped,
        )
        return mapped[1]
    }

    private fun canvasUnits(screenPx: Float): Float {
        val current = viewTransform.scale
        return if (current == 0f) screenPx else screenPx / current
    }

    private fun mapPointsToScreen(points: FloatArray, count: Int) {
        for (i in 0 until count) {
            viewTransform.toScreen(points[i * 2], points[i * 2 + 1], mapped)
            points[i * 2] = mapped[0]
            points[i * 2 + 1] = mapped[1]
        }
    }

    private fun sameCenter(a: Float, b: Float): Boolean {
        if (a.isNaN() && b.isNaN()) return true
        return a == b
    }

    private fun invalidateCanvasRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenPad: Float,
    ) {
        val current = viewTransform.scale
        val screenLeft = left * current + viewTransform.tx - screenPad
        val screenTop = top * current + viewTransform.ty - screenPad
        val screenRight = right * current + viewTransform.tx + screenPad
        val screenBottom = bottom * current + viewTransform.ty + screenPad
        @Suppress("DEPRECATION")
        invalidate(
            screenLeft.toInt(),
            screenTop.toInt(),
            screenRight.toInt() + 1,
            screenBottom.toInt() + 1,
        )
    }

    private fun inputTypeOf(toolType: Int): InputType {
        return when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputType.STYLUS
            MotionEvent.TOOL_TYPE_FINGER -> InputType.FINGER
            MotionEvent.TOOL_TYPE_MOUSE -> InputType.MOUSE
            else -> InputType.OTHER
        }
    }

    private companion object {
        const val TAG = "DrawingView"
        const val MIN_LINE_DP = 4f
        const val MIN_SHAPE_DP = 4f
        val HEX_DIGITS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
        )
    }
}
