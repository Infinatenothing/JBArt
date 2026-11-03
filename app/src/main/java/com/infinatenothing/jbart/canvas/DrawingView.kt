package com.infinatenothing.jbart.canvas

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Xfermode
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * White background composited under a transparent ink bitmap.
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
            if (current.filled == value) return
            pendingShape = current.copy(filled = value)
            dragOrigin = dragOrigin?.let { origin ->
                if (origin.filled == value) origin else origin.copy(filled = value)
            }
            if (!drawing) {
                paintPendingShape()
                invalidate()
            }
        }

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

    init {
        setWillNotDraw(false)
        isClickable = true
        check(MotionEvent.TOOL_TYPE_FINGER == InputRouting.TOOL_TYPE_FINGER)
        check(MotionEvent.TOOL_TYPE_STYLUS == InputRouting.TOOL_TYPE_STYLUS)
        check(MotionEvent.TOOL_TYPE_MOUSE == InputRouting.TOOL_TYPE_MOUSE)
        check(MotionEvent.TOOL_TYPE_ERASER == InputRouting.TOOL_TYPE_ERASER)
        check(MotionEvent.BUTTON_STYLUS_PRIMARY == InputRouting.BUTTON_STYLUS_PRIMARY)
        check(MotionEvent.BUTTON_STYLUS_SECONDARY == InputRouting.BUTTON_STYLUS_SECONDARY)
        labelPaint.textSize = 12f * resources.displayMetrics.density
        handleLinePaint.strokeWidth = dp(1.5f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val existing = ink
        if (existing != null && existing.width == w && existing.height == h && scratch != null) return
        existing?.recycle()
        scratch?.recycle()
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ink = created
        inkCanvas = Canvas(created)
        scratch = scratchBitmap
        scratchCanvas = Canvas(scratchBitmap)
        // Canvas coordinates are view pixels. A smaller view clips; a larger one leaves
        // empty space. Strokes are not scaled.
        document?.let { doc ->
            doc.width = w
            doc.height = h
            doc.bitmap = created
        }
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
        renderAll()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(document?.backgroundColor ?: Color.WHITE)
        ink?.let { bitmap ->
            // CPU draws into this bitmap after the GPU has cached it. Without
            // prepareToDraw the screen keeps the first blank upload.
            bitmap.prepareToDraw()
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
        val pending = pendingShape
        val line = pendingLine
        if (pending != null || line != null || (drawing && activeDrawTool != Tool.ERASER)) {
            scratch?.let { bitmap ->
                bitmap.prepareToDraw()
                blitPaint.xfermode = null
                val alpha = if (drawing) strokeAlpha else pending?.alpha ?: line?.alpha ?: strokeAlpha
                blitPaint.alpha = alphaByte(alpha)
                canvas.drawBitmap(bitmap, 0f, 0f, blitPaint)
            }
            if (!drawing) {
                if (pending != null) drawHandles(canvas, pending)
                else if (line != null) drawLineHandles(canvas, line)
            }
            if (drawing && activeDrawTool == Tool.LINE && labelBuilder.isNotEmpty()) {
                val offset = labelPaint.textSize
                canvas.drawText(
                    labelBuilder,
                    0,
                    labelBuilder.length,
                    lineEndX + offset,
                    lineEndY - offset,
                    labelPaint,
                )
            }
        }
    }

    fun requestRedraw() = invalidate()

    fun renderAll() {
        val canvas = inkCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (obj in document?.objects.orEmpty()) {
            when (obj) {
                is DrawObject.Stroke -> stampBrush(obj.points, obj.baseWidthPx, obj.alpha, obj.inputType)
                is DrawObject.EraseStroke -> stampErase(obj.points, obj.baseWidthPx, obj.inputType)
                is DrawObject.Line -> stampLine(obj.x1, obj.y1, obj.x2, obj.y2, obj.color, obj.widthPx, obj.alpha)
                is DrawObject.Shape -> stampShape(obj)
                is DrawObject.Fill -> Unit
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
        source.width = width
        source.height = height
        ink?.let { source.bitmap = it }
        if (renderedRevision != revision) {
            renderAll()
            renderedRevision = revision
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        logEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
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
                if (pendingDrag) cancelPendingDrag() else discardActive()
            }
        }
        return true
    }

    private fun onPointerDown(event: MotionEvent) {
        if (pendingDrag) cancelPendingDrag()
        if (drawing) discardActive()
        val routed = InputRouting.toolForEvent(tool, event.getToolType(0), event.buttonState)
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
            val x = event.getX(0)
            val y = event.getY(0)
            val line = pendingLine ?: return
            val handle = LineTransform.hitLineHandle(line, x, y, handleTouchPx())
            if (handle != null) {
                beginLineDrag(handle, x, y, event, 0)
                return
            }
            val slop = max(line.widthPx / 2f, dp(LineTransform.BODY_SLOP_DP))
            if (LineTransform.nearLine(line, x, y, slop)) {
                beginLineDrag(null, x, y, event, 0)
                return
            }
            Log.d(TAG, "pending line commit tap")
            commitPendingOnly()
            return
        }
        if (routed == Tool.SHAPE && pendingShape != null) {
            val x = event.getX(0)
            val y = event.getY(0)
            val shape = pendingShape ?: return
            val handle = ShapeTransform.hitHandle(shape, x, y, handleTouchPx(), rotateOffsetPx())
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
        val x = event.getX(index)
        val y = event.getY(index)
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
            pendingDrag -> finishPendingDrag(event)
            !drawing -> Unit
            activeDrawTool == Tool.LINE -> commitLine(event)
            activeDrawTool == Tool.SHAPE -> finishShape(event)
            else -> commitStroke(event)
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
        lineStartX = event.getX(index)
        lineStartY = event.getY(index)
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
                drawLineOn(canvas, lineStartX, lineStartY, lineEndX, lineEndY, Color.BLACK, strokeBaseWidthPx)
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
            moveLine(event.getX(index), event.getY(index))
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
            color = Color.BLACK,
            alpha = alpha,
            widthPx = width,
            inputType = type,
        )
        pendingLine = line
        paintPendingLine()
        onStagePending(line)
        invalidate()
        Log.d(TAG, "pending line start start=$x1,$y1 end=$x2,$y2 length=$length snap=$lineSnapEnabled")
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
            stampBrush(points, width, alpha, type)
        }
        clearScratch()
        val obj: DrawObject = if (toolUsed == Tool.ERASER) {
            DrawObject.EraseStroke(points, width, type)
        } else {
            DrawObject.Stroke(
                points = points,
                color = Color.BLACK,
                baseWidthPx = width,
                inputType = type,
                alpha = alpha,
            )
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
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                event.getHistoricalPressure(pointerIndex, h),
                event.getHistoricalEventTime(h),
            )
        }
        addCurrent(event, pointerIndex)
    }

    private fun addCurrent(event: MotionEvent, pointerIndex: Int) {
        addPoint(
            event.getX(pointerIndex),
            event.getY(pointerIndex),
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
            drawSegment(canvas, Color.BLACK)
        }
    }

    private fun stampBrush(points: List<StrokePoint>, width: Float, alpha: Float, type: InputType) {
        val canvas = scratchCanvas ?: return
        val target = inkCanvas ?: return
        clearScratch()
        geometryXfer = null
        drawStrokeContents(canvas, points, Color.BLACK, width, type)
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
        shapeStartX = event.getX(index)
        shapeStartY = event.getY(index)
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
                    Color.BLACK,
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
            moveShape(event.getX(index), event.getY(index))
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
            color = Color.BLACK,
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
            "pending start kind=$kind start=$x1,$y1 end=$x2,$y2 constrain=$constrain filled=$filled span=$span",
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
            updatePendingDrag(event.getX(index), event.getY(index))
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
        canvas.save()
        if (shape.rotationDeg != 0f) canvas.rotate(shape.rotationDeg, cx, cy)
        handleLinePaint.strokeWidth = dp(1f)
        shapeRect.set(shapeBox[0], shapeBox[1], shapeBox[2], shapeBox[3])
        canvas.drawRect(shapeRect, handleLinePaint)
        canvas.restore()
        ShapeTransform.handlePositions(shape, rotateOffsetPx(), handleOut)
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
            rotateOffsetPx(),
            minArrowHeadPx(),
            dp(8f),
            shapeBounds,
        )
        invalidateBounds(shapeBounds)
    }

    private fun drawLineHandles(canvas: Canvas, line: DrawObject.Line) {
        val radius = dp(6f)
        handleLinePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(line.x1, line.y1, radius, handleFillPaint)
        canvas.drawCircle(line.x1, line.y1, radius, handleLinePaint)
        canvas.drawCircle(line.x2, line.y2, radius, handleFillPaint)
        canvas.drawCircle(line.x2, line.y2, radius, handleLinePaint)
    }

    private fun invalidateLine(line: DrawObject.Line) {
        invalidateHull(
            line.x1,
            line.y1,
            line.x2,
            line.y2,
            line.x2,
            line.y2,
            max(line.widthPx, dp(16f)),
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
        @Suppress("DEPRECATION")
        invalidate(
            bounds[0].toInt(),
            bounds[1].toInt(),
            bounds[2].toInt() + 1,
            bounds[3].toInt() + 1,
        )
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
        val pad = width * 0.5f + 2f
        val left = min(x0, min(x1, x2)) - pad
        val top = min(y0, min(y1, y2)) - pad
        val right = max(x0, max(x1, x2)) + pad
        val bottom = max(y0, max(y1, y2)) + pad
        @Suppress("DEPRECATION")
        invalidate(left.toInt(), top.toInt(), right.toInt() + 1, bottom.toInt() + 1)
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
    }
}
