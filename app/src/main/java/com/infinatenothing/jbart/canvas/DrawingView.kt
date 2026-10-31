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
import android.graphics.Xfermode
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.InputType
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
 * so a stroke does not darken where it crosses itself. Erases punch that ink with CLEAR.
 */
class DrawingView(context: Context) : View(context) {
    var document: DrawingDocument? = null
    var onCommit: (DrawObject) -> Unit = {}
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
    private val showSnapLabel =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val labelBuilder = StringBuilder(32)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
    }

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
        if (drawing && activeDrawTool != Tool.ERASER) {
            scratch?.let { bitmap ->
                bitmap.prepareToDraw()
                blitPaint.xfermode = null
                blitPaint.alpha = alphaByte(strokeAlpha)
                canvas.drawBitmap(bitmap, 0f, 0f, blitPaint)
            }
            if (activeDrawTool == Tool.LINE && labelBuilder.isNotEmpty()) {
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
                is DrawObject.Shape, is DrawObject.Fill -> Unit
            }
        }
        clearScratch()
        ink?.prepareToDraw()
        invalidate()
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
            MotionEvent.ACTION_DOWN -> {
                if (drawing) discardActive()
                val routed = InputRouting.toolForEvent(tool, event.getToolType(0), event.buttonState)
                if (routed == null) return true
                if (routed == Tool.LINE) beginLine(event, 0) else beginStroke(event, 0, routed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                if (ignoredPointer(event, index)) return true
                if (activeDrawTool == Tool.LINE) {
                    moveLine(event.getX(index), event.getY(index))
                } else {
                    addSamples(event, index)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finishGesture(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drawing && event.getPointerId(event.actionIndex) == activePointerId) {
                    finishGesture(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> discardActive()
        }
        return true
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

    private fun finishGesture(event: MotionEvent) {
        if (activeDrawTool == Tool.LINE) commitLine(event) else commitStroke(event)
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
        stampLine(x1, y1, x2, y2, Color.BLACK, width, alpha)
        onCommit(
            DrawObject.Line(
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                color = Color.BLACK,
                alpha = alpha,
                widthPx = width,
                inputType = type,
            ),
        )
        ink?.prepareToDraw()
        invalidate()
        Log.d(TAG, "line commit start=$x1,$y1 end=$x2,$y2 length=$length snap=$lineSnapEnabled")
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
        if (activeDrawTool == Tool.LINE) discardLine() else discardStroke()
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
    }
}
