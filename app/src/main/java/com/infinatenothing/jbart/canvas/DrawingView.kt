package com.infinatenothing.jbart.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.infinatenothing.jbart.model.DrawingDocument
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.Tool

class DrawingView(context: Context) : View(context) {
    val document = DrawingDocument(backgroundColor = Color.WHITE)
    var history = HistoryManager()
    var tool = Tool.BRUSH

    private var backing: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val existing = backing
        if (existing != null && existing.width == w && existing.height == h) return
        existing?.recycle()
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        created.eraseColor(Color.WHITE)
        backing = created
        document.width = w
        document.height = h
        document.bitmap = created
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        backing?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    fun requestRedraw() = invalidate()

    fun undo() {
        history.undo()
        requestRedraw()
    }

    fun redo() {
        history.redo()
        requestRedraw()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.getToolType(0)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "unknown"
        }
        Log.d(TAG, "tool=$kind pressure=${event.pressure} action=${event.actionMasked}")
        return true
    }

    private companion object {
        const val TAG = "DrawingView"
    }
}
