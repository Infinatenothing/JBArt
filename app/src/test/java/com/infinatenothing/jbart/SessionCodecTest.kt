package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.session.SessionCodec
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SessionCodecTest {
    @Test
    fun roundTripMixedStrokesAndSettings() {
        val snapshot = SessionSnapshot(
            width = 800,
            height = 600,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(stroke(1f), erase(2f), line(), stroke(3f)),
            undoCount = 3,
            redo = listOf(erase(9f)),
            tool = Tool.LINE,
            brush = BrushSettings(sizeDp = 12f, opacity = 0.4f),
            eraser = BrushSettings(sizeDp = 20f, opacity = 1f),
            lineSnap = false,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot))
        assertEquals(SessionLoad.Ok(snapshot), decoded)
    }

    @Test
    fun roundTripShapesOfEveryKindAndShapeSettings() {
        val snapshot = SessionSnapshot(
            width = 800,
            height = 600,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(
                stroke(1f),
                erase(2f),
                line(),
                shape(ShapeKind.RECTANGLE, filled = false, x = 3f),
                shape(ShapeKind.ELLIPSE, filled = true, x = 4f),
                shape(ShapeKind.TRIANGLE, filled = false, x = 5f),
                shape(ShapeKind.ARROW, filled = true, x = 6f),
            ),
            undoCount = 4,
            redo = listOf(shape(ShapeKind.ARROW, filled = false, x = 7f), erase(9f)),
            tool = Tool.SHAPE,
            brush = BrushSettings(sizeDp = 12f, opacity = 0.4f),
            eraser = BrushSettings(sizeDp = 20f, opacity = 1f),
            lineSnap = false,
            shapeKind = ShapeKind.TRIANGLE,
            shapeConstrain = true,
            shapeFilled = true,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot))
        assertEquals(SessionLoad.Ok(snapshot), decoded)
    }

    @Test
    fun version2PayloadLessShapeIsDroppedAndUndoCountStaysValid() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(2)
                data.writeInt(10)
                data.writeInt(20)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("LINE")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                data.writeBoolean(true)
                data.writeInt(3)
                data.writeByte(1)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(4f)
                data.writeFloat(0.8f)
                data.writeUTF("FINGER")
                data.writeInt(1)
                data.writeFloat(1f)
                data.writeFloat(2f)
                data.writeFloat(0.5f)
                data.writeLong(3L)
                data.writeByte(4)
                data.writeByte(3)
                data.writeFloat(8f)
                data.writeFloat(9f)
                data.writeFloat(10f)
                data.writeFloat(11f)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(1f)
                data.writeFloat(5f)
                data.writeUTF("STYLUS")
                data.writeInt(3)
                data.writeInt(1)
                data.writeByte(4)
            }
        }.toByteArray()
        val decoded = SessionCodec.decode(bytes)
        assertTrue(decoded is SessionLoad.Ok)
        val snapshot = (decoded as SessionLoad.Ok).snapshot
        assertEquals(2, snapshot.objects.size)
        val keptStroke = snapshot.objects[0] as DrawObject.Stroke
        assertEquals(1f, keptStroke.points.single().x, 0f)
        val keptLine = snapshot.objects[1] as DrawObject.Line
        assertEquals(8f, keptLine.x1, 0f)
        assertEquals(11f, keptLine.y2, 0f)
        assertEquals(2, snapshot.undoCount)
        assertTrue(snapshot.redo.isEmpty())
        assertEquals(ShapeKind.RECTANGLE, snapshot.shapeKind)
        assertFalse(snapshot.shapeConstrain)
        assertFalse(snapshot.shapeFilled)
        assertTrue(snapshot.lineSnap)
    }

    @Test
    fun version1SessionWithoutLinePayloadStillLoads() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(1)
                data.writeInt(10)
                data.writeInt(20)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("BRUSH")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                data.writeInt(2)
                data.writeByte(1)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(4f)
                data.writeFloat(0.8f)
                data.writeUTF("FINGER")
                data.writeInt(1)
                data.writeFloat(1f)
                data.writeFloat(2f)
                data.writeFloat(0.5f)
                data.writeLong(3L)
                data.writeByte(3)
                data.writeInt(1)
                data.writeInt(0)
            }
        }.toByteArray()
        val decoded = SessionCodec.decode(bytes)
        assertTrue(decoded is SessionLoad.Ok)
        val snapshot = (decoded as SessionLoad.Ok).snapshot
        val stroke = snapshot.objects[0] as DrawObject.Stroke
        assertEquals(1f, stroke.points.single().x, 0f)
        assertEquals(0.8f, stroke.alpha, 0f)
        val legacy = snapshot.objects[1] as DrawObject.Line
        assertEquals(0f, legacy.x1, 0f)
        assertEquals(0f, legacy.x2, 0f)
        assertTrue(snapshot.lineSnap)
        assertEquals(1, snapshot.undoCount)
    }

    @Test
    fun truncatedInputIsAnError() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(SessionCodec.MAGIC)
                it.writeInt(SessionCodec.VERSION)
                it.writeInt(10)
            }
        }.toByteArray()
        assertTrue(SessionCodec.decode(bytes) is SessionLoad.Failed)
    }

    @Test
    fun unknownVersionIsRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(SessionCodec.MAGIC)
                it.writeInt(99)
            }
        }.toByteArray()
        val decoded = SessionCodec.decode(bytes)
        assertTrue(decoded is SessionLoad.Failed)
        assertEquals("unknown version 99", (decoded as SessionLoad.Failed).reason)
    }

    private fun stroke(x: Float) = DrawObject.Stroke(
        points = listOf(StrokePoint(x, 1f, 0.5f, 20L)),
        color = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        inputType = InputType.STYLUS,
        alpha = 0.8f,
    )

    private fun line() = DrawObject.Line(
        x1 = 1f,
        y1 = 2f,
        x2 = 30f,
        y2 = 40f,
        color = 0xFF000000.toInt(),
        alpha = 0.3f,
        widthPx = 6f,
        inputType = InputType.STYLUS,
    )

    private fun shape(kind: ShapeKind, filled: Boolean, x: Float) = DrawObject.Shape(
        kind = kind,
        x1 = x,
        y1 = 1f,
        x2 = x + 10f,
        y2 = 12f,
        filled = filled,
        color = 0xFF000000.toInt(),
        alpha = if (filled) 0.3f else 1f,
        widthPx = 5f,
        inputType = InputType.STYLUS,
    )

    private fun erase(x: Float) = DrawObject.EraseStroke(
        points = listOf(StrokePoint(x, 2f, 1f, 30L)),
        baseWidthPx = 16f,
        inputType = InputType.FINGER,
    )
}
