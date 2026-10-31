package com.infinatenothing.jbart.session

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
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
 * Version 1 files still load. Strokes keep canvas pixels. The file is an autosave, not a user document.
 */
object SessionCodec {
    const val MAGIC = 0x4A425331
    const val VERSION = 2
    private const val VERSION_LINE_PAYLOAD = 2

    private const val STROKE = 1
    private const val ERASE = 2
    private const val LINE = 3
    private const val SHAPE = 4
    private const val FILL = 5
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
            writeObjects(data, snapshot.objects)
            data.writeInt(snapshot.undoCount)
            writeObjects(data, snapshot.redo)
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
        if (version != 1 && version != VERSION) return SessionLoad.Failed("unknown version $version")
        val width = data.readInt()
        val height = data.readInt()
        val background = data.readInt()
        val tool = enumValue<Tool>(data.readUTF()) ?: return SessionLoad.Failed("corrupt")
        val brush = readBrush(data)
        val eraser = readBrush(data)
        val lineSnap = if (version >= VERSION_LINE_PAYLOAD) data.readBoolean() else true
        val objects = readObjects(data, version)
        val undoCount = data.readInt()
        if (undoCount !in 0..objects.size) return SessionLoad.Failed("corrupt")
        val redo = readObjects(data, version)
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
            ),
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
        return List(count) { readObject(data, version) }
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
            is DrawObject.Shape -> data.writeByte(SHAPE)
            is DrawObject.Fill -> data.writeByte(FILL)
        }
    }

    private fun readObject(data: DataInputStream, version: Int): DrawObject {
        return when (data.readUnsignedByte()) {
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
            SHAPE -> DrawObject.Shape()
            FILL -> DrawObject.Fill()
            else -> throw EOFException()
        }
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
