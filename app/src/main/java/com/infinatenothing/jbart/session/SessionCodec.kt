package com.infinatenothing.jbart.session

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.CanvasLimits
import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.HistoryLimits
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.opaque
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

data class SessionSnapshot(
    val width: Int,
    val height: Int,
    val backgroundColor: Int,
    val objects: List<DrawObject>,
    val undoCount: Int,
    val redo: List<DrawObject>,
    val tool: Tool,
    val brush: BrushSettings,
    val eraser: BrushSettings,
    val lineSnap: Boolean = true,
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val shapeConstrain: Boolean = false,
    val shapeFilled: Boolean = false,
    val pending: DrawObject? = null,
    val colors: ColorSettings = ColorSettings(),
    val fillTolerance: Int = 32,
    val fillGapDp: Int = 2,
    val fixedSize: Boolean = true,
)

sealed interface SessionLoad {
    data object Missing : SessionLoad
    data class Ok(val snapshot: SessionSnapshot) : SessionLoad
    data class Failed(val reason: String) : SessionLoad
}

/**
 * Binary session.
 * Version 1: magic, version, size, background, tool, brush, eraser, objects, undoCount, redo.
 * Version 2 adds the line-snap flag after the eraser and a full [DrawObject.Line] payload.
 * Version 3 adds shape kind, constrain, and filled after line-snap, and a full [DrawObject.Shape] payload.
 * Version 4 adds [DrawObject.Shape.rotationDeg] after the version 3 shape payload. Line objects keep
 * the version 3 payload. After the redo list, version 4 writes a pending flag and, when set, one
 * object ([DrawObject.Shape] or [DrawObject.Line]). That object is not part of [SessionSnapshot.objects]
 * or the undo count. A pending tag other than shape or line drops only the pending object.
 * A version 1 or 2 shape tag has no payload and is dropped. [SessionSnapshot.undoCount] is then
 * clamped to the remaining object count. Version 1, 2, and 3 files still load, with no pending object
 * and shape rotation of 0.
 * Version 5 adds the stroke color, fill color, recent count (one byte), and recent colors
 * after the filled flag and before the object list. Object payloads are unchanged.
 * A recent count above [ColorSettings.MAX_RECENT] fails the header. Versions 1 to 4 still
 * load, with black stroke and fill colors and an empty recent list. Object colors already
 * stored in those files are left as they were.
 * Version 6 writes fill tolerance and the close-gaps setting (dp) after the color block,
 * and a full [DrawObject.Fill] payload: seed, color, alpha, tolerance, gap px, bitmap size,
 * run count, then runs. Versions 1 to 5 still load. Their fill tag has no payload and is
 * dropped, and [SessionSnapshot.undoCount] is clamped to the objects that remain.
 * Tolerance defaults to 32 and the gap setting to 2. A run count above
 * [HistoryLimits.MAX_FILL_RUNS] fails the file. Runs out of order drop that fill only.
 * Version 7 writes a size-mode byte after the fill settings (0 legacy, 1 fixed) and a
 * [DrawObject.Clear] tag with no payload. Versions 1 to 6 load as fixed at their saved
 * width and height. A width or height that is not positive, longer than
 * [CanvasLimits.MAX_EDGE] on the long side, or over [CanvasLimits.MAX_PIXELS] fails the file.
 * Strokes keep canvas pixels. The file is an autosave, not a user document.
 */
object SessionCodec {
    const val MAGIC = 0x4A425331
    const val VERSION = 7
    private const val VERSION_LINE_PAYLOAD = 2
    private const val VERSION_SHAPE_PAYLOAD = 3
    private const val VERSION_SHAPE_ROTATION = 4
    private const val VERSION_COLORS = 5
    private const val VERSION_FILL = 6
    private const val VERSION_CANVAS = 7

    var log: (String) -> Unit = {}

    private const val STROKE = 1
    private const val ERASE = 2
    private const val LINE = 3
    private const val SHAPE = 4
    private const val FILL = 5
    private const val CLEAR = 6
    private const val POINT_BYTES = 20

    fun encode(snapshot: SessionSnapshot): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(snapshot.width)
            data.writeInt(snapshot.height)
            data.writeInt(snapshot.backgroundColor)
            data.writeUTF(snapshot.tool.name)
            writeBrush(data, snapshot.brush)
            writeBrush(data, snapshot.eraser)
            data.writeBoolean(snapshot.lineSnap)
            data.writeUTF(snapshot.shapeKind.name)
            data.writeBoolean(snapshot.shapeConstrain)
            data.writeBoolean(snapshot.shapeFilled)
            writeColors(data, snapshot.colors)
            data.writeInt(snapshot.fillTolerance.coerceIn(0, 255))
            data.writeInt(snapshot.fillGapDp.coerceIn(0, 10))
            data.writeByte(if (snapshot.fixedSize) CanvasLimits.MODE_FIXED else CanvasLimits.MODE_LEGACY)
            writeObjects(data, snapshot.objects)
            data.writeInt(snapshot.undoCount)
            writeObjects(data, snapshot.redo)
            val pending = snapshot.pending
            data.writeBoolean(pending != null)
            if (pending != null) writeObject(data, pending)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SessionLoad {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data -> read(data) }
        } catch (_: Exception) {
            SessionLoad.Failed("corrupt")
        }
    }

    private fun read(data: DataInputStream): SessionLoad {
        if (data.readInt() != MAGIC) return SessionLoad.Failed("corrupt")
        val version = data.readInt()
        if (version !in 1..VERSION) return SessionLoad.Failed("unknown version $version")
        val width = data.readInt()
        val height = data.readInt()
        if (!CanvasLimits.withinCap(width, height)) return SessionLoad.Failed("canvas size")
        val background = data.readInt()
        val tool = enumValue<Tool>(data.readUTF()) ?: return SessionLoad.Failed("corrupt")
        val brush = readBrush(data)
        val eraser = readBrush(data)
        val lineSnap = if (version >= VERSION_LINE_PAYLOAD) data.readBoolean() else true
        val shapeKind: ShapeKind
        val shapeConstrain: Boolean
        val shapeFilled: Boolean
        if (version >= VERSION_SHAPE_PAYLOAD) {
            shapeKind = enumValue<ShapeKind>(data.readUTF()) ?: return SessionLoad.Failed("corrupt")
            shapeConstrain = data.readBoolean()
            shapeFilled = data.readBoolean()
        } else {
            shapeKind = ShapeKind.RECTANGLE
            shapeConstrain = false
            shapeFilled = false
        }
        val colors = if (version >= VERSION_COLORS) readColors(data) else ColorSettings()
        val fillTolerance: Int
        val fillGapDp: Int
        if (version >= VERSION_FILL) {
            fillTolerance = data.readInt().coerceIn(0, 255)
            fillGapDp = data.readInt().coerceIn(0, 10)
        } else {
            fillTolerance = 32
            fillGapDp = 2
        }
        val fixedSize = if (version >= VERSION_CANVAS) {
            when (data.readUnsignedByte()) {
                CanvasLimits.MODE_LEGACY -> false
                CanvasLimits.MODE_FIXED -> true
                else -> return SessionLoad.Failed("corrupt")
            }
        } else {
            true
        }
        val objects = readObjects(data, version)
        val undoCountRaw = data.readInt()
        val undoCount = if (version >= VERSION_FILL) {
            undoCountRaw
        } else {
            undoCountRaw.coerceIn(0, objects.size)
        }
        if (undoCount !in 0..objects.size) return SessionLoad.Failed("corrupt")
        val redo = readObjects(data, version)
        val pending = if (version >= VERSION_SHAPE_ROTATION) readPending(data, version) else null
        return SessionLoad.Ok(
            SessionSnapshot(
                width = width,
                height = height,
                backgroundColor = background,
                objects = objects,
                undoCount = undoCount,
                redo = redo,
                tool = tool,
                brush = brush,
                eraser = eraser,
                lineSnap = lineSnap,
                shapeKind = shapeKind,
                shapeConstrain = shapeConstrain,
                shapeFilled = shapeFilled,
                pending = pending,
                colors = colors,
                fillTolerance = fillTolerance,
                fillGapDp = fillGapDp,
                fixedSize = fixedSize,
            ),
        )
    }

    private fun writeColors(data: DataOutputStream, colors: ColorSettings) {
        data.writeInt(opaque(colors.stroke))
        data.writeInt(opaque(colors.fill))
        val recent = colors.recent.take(ColorSettings.MAX_RECENT).map(::opaque)
        data.writeByte(recent.size)
        for (color in recent) data.writeInt(color)
    }

    private fun readColors(data: DataInputStream): ColorSettings {
        val stroke = opaque(data.readInt())
        val fill = opaque(data.readInt())
        val count = data.readUnsignedByte()
        if (count > ColorSettings.MAX_RECENT) throw EOFException()
        return ColorSettings(
            stroke = stroke,
            fill = fill,
            recent = List(count) { opaque(data.readInt()) },
        )
    }

    private fun writeBrush(data: DataOutputStream, settings: BrushSettings) {
        data.writeFloat(settings.sizeDp)
        data.writeFloat(settings.opacity)
    }

    private fun readBrush(data: DataInputStream): BrushSettings {
        return BrushSettings(sizeDp = data.readFloat(), opacity = data.readFloat())
    }

    private fun writeObjects(data: DataOutputStream, objects: List<DrawObject>) {
        data.writeInt(objects.size)
        for (obj in objects) writeObject(data, obj)
    }

    private fun readObjects(data: DataInputStream, version: Int): List<DrawObject> {
        val count = data.readInt()
        if (count < 0 || count > data.available()) throw EOFException()
        val objects = ArrayList<DrawObject>(count)
        repeat(count) {
            val obj = readObject(data, version)
            if (obj != null) objects.add(obj)
        }
        return objects
    }

    private fun writeObject(data: DataOutputStream, obj: DrawObject) {
        when (obj) {
            is DrawObject.Stroke -> {
                data.writeByte(STROKE)
                data.writeInt(obj.color)
                data.writeFloat(obj.baseWidthPx)
                data.writeFloat(obj.alpha)
                data.writeUTF(obj.inputType.name)
                writePoints(data, obj.points)
            }
            is DrawObject.EraseStroke -> {
                data.writeByte(ERASE)
                data.writeFloat(obj.baseWidthPx)
                data.writeUTF(obj.inputType.name)
                writePoints(data, obj.points)
            }
            is DrawObject.Line -> {
                data.writeByte(LINE)
                data.writeFloat(obj.x1)
                data.writeFloat(obj.y1)
                data.writeFloat(obj.x2)
                data.writeFloat(obj.y2)
                data.writeInt(obj.color)
                data.writeFloat(obj.alpha)
                data.writeFloat(obj.widthPx)
                data.writeUTF(obj.inputType.name)
            }
            is DrawObject.Shape -> {
                data.writeByte(SHAPE)
                data.writeUTF(obj.kind.name)
                data.writeFloat(obj.x1)
                data.writeFloat(obj.y1)
                data.writeFloat(obj.x2)
                data.writeFloat(obj.y2)
                data.writeBoolean(obj.filled)
                data.writeInt(obj.color)
                data.writeFloat(obj.alpha)
                data.writeFloat(obj.widthPx)
                data.writeUTF(obj.inputType.name)
                data.writeFloat(obj.rotationDeg)
            }
            is DrawObject.Fill -> {
                data.writeByte(FILL)
                data.writeInt(obj.seedX)
                data.writeInt(obj.seedY)
                data.writeInt(obj.color)
                data.writeFloat(obj.alpha)
                data.writeInt(obj.tolerance)
                data.writeInt(obj.gapPx)
                data.writeInt(obj.width)
                data.writeInt(obj.height)
                val count = obj.runs.size / 3
                data.writeInt(count)
                for (i in 0 until count * 3) data.writeInt(obj.runs[i])
            }
            is DrawObject.Clear -> data.writeByte(CLEAR)
        }
    }

    private fun readPending(data: DataInputStream, version: Int): DrawObject? {
        if (!data.readBoolean()) return null
        val tag = data.readUnsignedByte()
        if (tag != SHAPE && tag != LINE) {
            log("pending dropped tag=$tag")
            return null
        }
        return readTagged(tag, data, version)
    }

    private fun readObject(data: DataInputStream, version: Int): DrawObject? {
        return readTagged(data.readUnsignedByte(), data, version)
    }

    private fun readTagged(tag: Int, data: DataInputStream, version: Int): DrawObject? {
        return when (tag) {
            STROKE -> {
                val color = data.readInt()
                val width = data.readFloat()
                val alpha = data.readFloat()
                val input = enumValue<InputType>(data.readUTF()) ?: throw EOFException()
                DrawObject.Stroke(
                    points = readPoints(data),
                    color = color,
                    baseWidthPx = width,
                    inputType = input,
                    alpha = alpha,
                )
            }
            ERASE -> {
                val width = data.readFloat()
                val input = enumValue<InputType>(data.readUTF()) ?: throw EOFException()
                DrawObject.EraseStroke(
                    points = readPoints(data),
                    baseWidthPx = width,
                    inputType = input,
                )
            }
            LINE -> if (version >= VERSION_LINE_PAYLOAD) {
                DrawObject.Line(
                    x1 = data.readFloat(),
                    y1 = data.readFloat(),
                    x2 = data.readFloat(),
                    y2 = data.readFloat(),
                    color = data.readInt(),
                    alpha = data.readFloat(),
                    widthPx = data.readFloat(),
                    inputType = enumValue<InputType>(data.readUTF()) ?: throw EOFException(),
                )
            } else {
                DrawObject.Line(
                    x1 = 0f,
                    y1 = 0f,
                    x2 = 0f,
                    y2 = 0f,
                    color = 0xFF000000.toInt(),
                    alpha = 1f,
                    widthPx = 1f,
                    inputType = InputType.OTHER,
                )
            }
            SHAPE -> if (version >= VERSION_SHAPE_PAYLOAD) {
                DrawObject.Shape(
                    kind = enumValue<ShapeKind>(data.readUTF()) ?: throw EOFException(),
                    x1 = data.readFloat(),
                    y1 = data.readFloat(),
                    x2 = data.readFloat(),
                    y2 = data.readFloat(),
                    filled = data.readBoolean(),
                    color = data.readInt(),
                    alpha = data.readFloat(),
                    widthPx = data.readFloat(),
                    inputType = enumValue<InputType>(data.readUTF()) ?: throw EOFException(),
                    rotationDeg = if (version >= VERSION_SHAPE_ROTATION) data.readFloat() else 0f,
                )
            } else {
                null
            }
            FILL -> if (version >= VERSION_FILL) readFill(data) else null
            CLEAR -> if (version >= VERSION_CANVAS) DrawObject.Clear else throw EOFException()
            else -> throw EOFException()
        }
    }

    private fun readFill(data: DataInputStream): DrawObject.Fill? {
        val seedX = data.readInt()
        val seedY = data.readInt()
        val color = data.readInt()
        val alpha = data.readFloat()
        val tolerance = data.readInt()
        val gapPx = data.readInt()
        val width = data.readInt()
        val height = data.readInt()
        val count = data.readInt()
        if (count < 0 || count > HistoryLimits.MAX_FILL_RUNS) throw EOFException()
        val runs = IntArray(count * 3)
        for (i in runs.indices) runs[i] = data.readInt()
        if (!runsInOrder(runs)) {
            log("fill dropped runs=$count")
            return null
        }
        return DrawObject.Fill(
            seedX = seedX,
            seedY = seedY,
            color = color,
            alpha = alpha,
            tolerance = tolerance,
            gapPx = gapPx,
            width = width,
            height = height,
            runs = runs,
        )
    }

    private fun runsInOrder(runs: IntArray): Boolean {
        var previousY = Int.MIN_VALUE
        var previousEnd = 0
        var index = 0
        while (index < runs.size) {
            val y = runs[index]
            val x = runs[index + 1]
            val length = runs[index + 2]
            if (length <= 0 || y < previousY) return false
            if (y == previousY && x < previousEnd) return false
            previousY = y
            previousEnd = x + length
            index += 3
        }
        return true
    }

    private fun writePoints(data: DataOutputStream, points: List<StrokePoint>) {
        data.writeInt(points.size)
        for (point in points) {
            data.writeFloat(point.x)
            data.writeFloat(point.y)
            data.writeFloat(point.pressure)
            data.writeLong(point.timeMs)
        }
    }

    private fun readPoints(data: DataInputStream): List<StrokePoint> {
        val count = data.readInt()
        if (count < 0 || count > data.available() / POINT_BYTES) throw EOFException()
        return List(count) {
            StrokePoint(
                x = data.readFloat(),
                y = data.readFloat(),
                pressure = data.readFloat(),
                timeMs = data.readLong(),
            )
        }
    }
}

private inline fun <reified T : Enum<T>> enumValue(name: String): T? {
    return enumValues<T>().firstOrNull { it.name == name }
}
