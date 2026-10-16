package com.infinatenothing.jbart.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import kotlin.math.max
import kotlin.math.min

class DrawingView(context: Context) : View(context) {
    val document = DrawingDocument(backgroundColor = Color.WHITE)
    var history = HistoryManager()
    var tool = Tool.BRUSH

    private var backing: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val path = Path()
    private val segment = SegmentBuffer()
    private val strokePoints = ArrayList<StrokePoint>(64)

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var inputType = InputType.OTHER
    private var stylusActive = false
    private var drawing = false

    private val baseWidthPx: Float
        get() = 4f * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val existing = backing
        if (existing != null && existing.width == w && existing.height == h) return
        existing?.recycle()
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        backing = created
        bitmapCanvas = Canvas(created)
        document.width = w
        document.height = h
        document.bitmap = created
        if (drawing) {
            drawing = false
            stylusActive = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            strokePoints.clear()
        }
        renderAll()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(document.backgroundColor)
        backing?.let { bitmap ->
            // CPU draws into this bitmap after the GPU has cached it. Without
            // prepareToDraw the screen keeps the first blank upload.
            bitmap.prepareToDraw()
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    fun requestRedraw() = invalidate()

    fun renderAll() {
        val canvas = bitmapCanvas ?: return
        canvas.drawColor(document.backgroundColor)
        for (obj in document.objects) {
            if (obj is DrawObject.Stroke) replay(obj, canvas)
        }
        invalidate()
    }

    fun undo() {
        val entry = history.undo() ?: return
        if (document.objects.lastOrNull() == entry) {
            document.objects.removeAt(document.objects.lastIndex)
        }
        renderAll()
    }

    fun redo() {
        val entry = history.redo() ?: return
        document.objects.add(entry)
        renderAll()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        logEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (tool != Tool.BRUSH) {
            if (drawing) discardStroke()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) return true
                beginStroke(event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                if (ignoredPointer(event, index)) return true
                addSamples(event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    commitStroke(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drawing && event.getPointerId(event.actionIndex) == activePointerId) {
                    commitStroke(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> discardStroke()
        }
        return true
    }

    private fun beginStroke(event: MotionEvent, index: Int) {
        activePointerId = event.getPointerId(index)
        inputType = inputTypeOf(event.getToolType(index))
        stylusActive = inputType == InputType.STYLUS
        drawing = true
        strokePoints.clear()
        addCurrent(event, index)
    }

    private fun commitStroke(event: MotionEvent) {
        if (!drawing) return
        val index = event.findPointerIndex(activePointerId)
        if (index >= 0 && !ignoredPointer(event, index)) {
            addSamples(event, index)
        }
        val color = Color.BLACK
        val width = baseWidthPx
        val type = inputType
        val canvas = bitmapCanvas
        if (canvas != null && strokePoints.size == 1) {
            drawDot(canvas, strokePoints[0], color, width, type)
        } else {
            drawTail(color, width, type)
        }
        val points = ArrayList<StrokePoint>(strokePoints.size)
        points.addAll(strokePoints)
        endGesture()
        if (points.isEmpty()) return
        val stroke = DrawObject.Stroke(
            points = points,
            color = color,
            baseWidthPx = width,
            inputType = type,
        )
        document.objects.add(stroke)
        history.push(stroke)
        backing?.prepareToDraw()
        invalidate()
    }

    private fun discardStroke() {
        endGesture()
        strokePoints.clear()
        renderAll()
    }

    private fun endGesture() {
        drawing = false
        stylusActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun ignoredPointer(event: MotionEvent, index: Int): Boolean {
        val pointerType = event.getToolType(index)
        if (pointerType == MotionEvent.TOOL_TYPE_ERASER) return true
        if (stylusActive && pointerType == MotionEvent.TOOL_TYPE_FINGER) return true
        return false
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
        val canvas = bitmapCanvas ?: return
        if (from == null) return
        StrokeMath.fillQuadSegment(previous, from, point, baseWidthPx, inputType, segment)
        drawSegment(canvas, Color.BLACK)
    }

    private fun replay(stroke: DrawObject.Stroke, canvas: Canvas) {
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            drawDot(canvas, points[0], stroke.color, stroke.baseWidthPx, stroke.inputType)
            return
        }
        for (i in 1 until points.size) {
            val previous = if (i >= 2) points[i - 2] else null
            StrokeMath.fillQuadSegment(
                previous,
                points[i - 1],
                points[i],
                stroke.baseWidthPx,
                stroke.inputType,
                segment,
            )
            drawSegment(canvas, stroke.color)
        }
        if (points.size >= 2) {
            drawTailOn(
                canvas,
                points[points.lastIndex - 1],
                points.last(),
                stroke.color,
                stroke.baseWidthPx,
                stroke.inputType,
            )
        }
    }

    private fun drawDot(
        canvas: Canvas,
        point: StrokePoint,
        color: Int,
        width: Float,
        type: InputType,
    ) {
        val diameter = StrokeMath.widthFor(point, width, type)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(point.x, point.y, diameter * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        if (drawing) {
            invalidateHull(point.x, point.y, point.x, point.y, point.x, point.y, diameter)
        }
    }

    private fun drawSegment(canvas: Canvas, color: Int) {
        path.rewind()
        path.moveTo(segment.startX, segment.startY)
        path.quadTo(segment.controlX, segment.controlY, segment.endX, segment.endY)
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = segment.width
        canvas.drawPath(path, paint)
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

    private fun drawTail(color: Int, width: Float, type: InputType) {
        if (strokePoints.size < 2) return
        val canvas = bitmapCanvas ?: return
        drawTailOn(
            canvas,
            strokePoints[strokePoints.lastIndex - 1],
            strokePoints.last(),
            color,
            width,
            type,
        )
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
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = strokeWidth
        canvas.drawPath(path, paint)
        if (drawing) {
            invalidateHull(startX, startY, to.x, to.y, to.x, to.y, strokeWidth)
        }
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
        val index = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        val kind = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "unknown"
        }
        Log.d(TAG, "tool=$kind pressure=${event.getPressure(index)} action=${event.actionMasked}")
    }

    private fun inputTypeOf(toolType: Int): InputType {
        return when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> InputType.STYLUS
            MotionEvent.TOOL_TYPE_FINGER -> InputType.FINGER
            MotionEvent.TOOL_TYPE_MOUSE -> InputType.MOUSE
            else -> InputType.OTHER
        }
    }

    private companion object {
        const val TAG = "DrawingView"
    }
}
