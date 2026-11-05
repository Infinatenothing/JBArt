package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.ColorSettings
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
import org.junit.Assert.assertNull
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
        assertNull(snapshot.pending)
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
        assertNull(snapshot.pending)
        assertEquals(ColorSettings(), snapshot.colors)
        assertEquals(0xFF000000.toInt(), stroke.color)
    }

    @Test
    fun version4RoundTripWithoutPendingAndWithRotatedPendingShape() {
        val plain = SessionSnapshot(
            width = 100,
            height = 80,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(line(), shape(ShapeKind.ELLIPSE, filled = false, x = 2f)),
            undoCount = 2,
            redo = listOf(line()),
            tool = Tool.SHAPE,
            brush = BrushSettings(sizeDp = 8f, opacity = 1f),
            eraser = BrushSettings(sizeDp = 16f, opacity = 1f),
            lineSnap = true,
        )
        val plainDecoded = SessionCodec.decode(SessionCodec.encode(plain))
        assertEquals(SessionLoad.Ok(plain), plainDecoded)
        assertNull((plainDecoded as SessionLoad.Ok).snapshot.pending)

        val pending = shape(ShapeKind.RECTANGLE, filled = true, x = 4f).copy(rotationDeg = 30f)
        val withPending = plain.copy(
            objects = listOf(line(), shape(ShapeKind.TRIANGLE, filled = false, x = 1f).copy(rotationDeg = 12f)),
            undoCount = 1,
            redo = emptyList(),
            pending = pending,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(withPending))
        assertEquals(SessionLoad.Ok(withPending), decoded)
        val snapshot = (decoded as SessionLoad.Ok).snapshot
        assertEquals(pending, snapshot.pending)
        assertFalse(snapshot.objects.contains(pending))
        assertEquals(1, snapshot.undoCount)
        val stored = snapshot.objects[1] as DrawObject.Shape
        assertEquals(12f, stored.rotationDeg, 0f)
        assertTrue(snapshot.objects[0] is DrawObject.Line)
    }

    @Test
    fun version4RoundTripKeepsAPendingLineOutOfTheDocument() {
        val pending = line().copy(x2 = 44f, y2 = 8f)
        val snapshot = SessionSnapshot(
            width = 90,
            height = 70,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(line(), shape(ShapeKind.RECTANGLE, filled = false, x = 3f)),
            undoCount = 2,
            redo = listOf(stroke(2f)),
            tool = Tool.LINE,
            brush = BrushSettings(),
            eraser = BrushSettings(sizeDp = 16f),
            lineSnap = false,
            pending = pending,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot))
        assertEquals(SessionLoad.Ok(snapshot), decoded)
        val loaded = (decoded as SessionLoad.Ok).snapshot
        assertEquals(pending, loaded.pending)
        assertFalse(loaded.objects.contains(pending))
        assertEquals(2, loaded.undoCount)
        assertEquals(1, loaded.redo.size)
    }

    @Test
    fun badPendingTagDropsOnlyThePendingObject() {
        val snapshot = SessionSnapshot(
            width = 40,
            height = 40,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(line(), shape(ShapeKind.RECTANGLE, filled = false, x = 3f)),
            undoCount = 2,
            redo = emptyList(),
            tool = Tool.BRUSH,
            brush = BrushSettings(),
            eraser = BrushSettings(sizeDp = 16f),
        )
        val encoded = SessionCodec.encode(snapshot)
        assertEquals(0, encoded.last().toInt())
        val dirty = encoded.copyOf(encoded.size + 1)
        dirty[dirty.lastIndex - 1] = 1
        dirty[dirty.lastIndex] = 9
        val decoded = SessionCodec.decode(dirty)
        assertTrue(decoded is SessionLoad.Ok)
        val loaded = (decoded as SessionLoad.Ok).snapshot
        assertNull(loaded.pending)
        assertEquals(snapshot.objects, loaded.objects)
        assertEquals(snapshot.undoCount, loaded.undoCount)
        assertEquals(snapshot.redo, loaded.redo)
    }

    @Test
    fun version3ShapeLoadsWithZeroRotationAndNoPending() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(3)
                data.writeInt(10)
                data.writeInt(20)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("SHAPE")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                data.writeBoolean(true)
                data.writeUTF("ARROW")
                data.writeBoolean(true)
                data.writeBoolean(false)
                data.writeInt(2)
                data.writeByte(3)
                data.writeFloat(1f)
                data.writeFloat(2f)
                data.writeFloat(8f)
                data.writeFloat(9f)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(0.5f)
                data.writeFloat(3f)
                data.writeUTF("FINGER")
                data.writeByte(4)
                data.writeUTF("RECTANGLE")
                data.writeFloat(5f)
                data.writeFloat(6f)
                data.writeFloat(15f)
                data.writeFloat(16f)
                data.writeBoolean(true)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(1f)
                data.writeFloat(4f)
                data.writeUTF("STYLUS")
                data.writeInt(2)
                data.writeInt(0)
            }
        }.toByteArray()
        val decoded = SessionCodec.decode(bytes)
        assertTrue(decoded is SessionLoad.Ok)
        val snapshot = (decoded as SessionLoad.Ok).snapshot
        assertNull(snapshot.pending)
        assertEquals(2, snapshot.undoCount)
        val line = snapshot.objects[0] as DrawObject.Line
        assertEquals(1f, line.x1, 0f)
        assertEquals(9f, line.y2, 0f)
        val shape = snapshot.objects[1] as DrawObject.Shape
        assertEquals(ShapeKind.RECTANGLE, shape.kind)
        assertEquals(0f, shape.rotationDeg, 0f)
        assertEquals(5f, shape.x1, 0f)
        assertTrue(shape.filled)
        assertEquals(ShapeKind.ARROW, snapshot.shapeKind)
        assertTrue(snapshot.shapeConstrain)
        assertFalse(snapshot.shapeFilled)
        assertEquals(ColorSettings(), snapshot.colors)
        assertEquals(0xFF000000.toInt(), shape.color)
    }

    @Test
    fun version5RoundTripKeepsColorsRecentAndColoredPending() {
        val colors = ColorSettings(
            stroke = ColorSettings.RED,
            fill = ColorSettings.BLUE,
            recent = listOf(ColorSettings.GREEN, ColorSettings.ORANGE, 0x80FF00FF.toInt()),
        )
        val pending = shape(ShapeKind.ELLIPSE, filled = true, x = 8f).copy(color = ColorSettings.BLUE)
        val snapshot = SessionSnapshot(
            width = 120,
            height = 90,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(
                stroke(1f).copy(color = ColorSettings.RED),
                line().copy(color = ColorSettings.RED),
                shape(ShapeKind.RECTANGLE, filled = false, x = 2f).copy(color = ColorSettings.RED),
                shape(ShapeKind.TRIANGLE, filled = true, x = 3f).copy(color = ColorSettings.BLUE),
            ),
            undoCount = 4,
            redo = listOf(stroke(4f).copy(color = ColorSettings.GREEN)),
            tool = Tool.BRUSH,
            brush = BrushSettings(),
            eraser = BrushSettings(sizeDp = 16f),
            shapeFilled = true,
            pending = pending,
            colors = colors,
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot))
        val loaded = (decoded as SessionLoad.Ok).snapshot
        assertEquals(ColorSettings.RED, loaded.colors.stroke)
        assertEquals(ColorSettings.BLUE, loaded.colors.fill)
        assertEquals(
            listOf(ColorSettings.GREEN, ColorSettings.ORANGE, 0xFFFF00FF.toInt()),
            loaded.colors.recent,
        )
        assertEquals(ColorSettings.RED, (loaded.objects[0] as DrawObject.Stroke).color)
        assertEquals(ColorSettings.RED, (loaded.objects[1] as DrawObject.Line).color)
        assertEquals(ColorSettings.RED, (loaded.objects[2] as DrawObject.Shape).color)
        assertEquals(false, (loaded.objects[2] as DrawObject.Shape).filled)
        assertEquals(ColorSettings.BLUE, (loaded.objects[3] as DrawObject.Shape).color)
        assertEquals(pending.copy(color = ColorSettings.BLUE), loaded.pending)
    }

    @Test
    fun olderVersionsLoadWithBlackColorsAndEmptyRecent() {
        for (version in listOf(1, 2, 4)) {
            val bytes = header(version)
            val decoded = SessionCodec.decode(bytes)
            assertTrue("version $version", decoded is SessionLoad.Ok)
            assertEquals(ColorSettings(), (decoded as SessionLoad.Ok).snapshot.colors)
        }
        val version3 = SessionCodec.decode(version3Bytes())
        assertTrue(version3 is SessionLoad.Ok)
        assertEquals(ColorSettings(), (version3 as SessionLoad.Ok).snapshot.colors)
    }

    @Test
    fun version6RoundTripKeepsFillsToleranceAndGap() {
        val fill = DrawObject.Fill(
            seedX = 3,
            seedY = 4,
            color = ColorSettings.RED,
            alpha = 1f,
            tolerance = 32,
            gapPx = 5,
            width = 12,
            height = 9,
            runs = intArrayOf(1, 0, 4, 1, 6, 2, 2, 0, 3),
        )
        val snapshot = SessionSnapshot(
            width = 12,
            height = 9,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(stroke(1f), fill, shape(ShapeKind.ELLIPSE, filled = false, x = 2f)),
            undoCount = 2,
            redo = listOf(fill.copy(seedX = 1)),
            tool = Tool.FILL,
            brush = BrushSettings(),
            eraser = BrushSettings(),
            pending = line(),
            colors = ColorSettings(stroke = ColorSettings.RED, fill = ColorSettings.BLUE),
            fillTolerance = 40,
            fillGapDp = 2,
        )
        val loaded = (SessionCodec.decode(SessionCodec.encode(snapshot)) as SessionLoad.Ok).snapshot
        assertEquals(snapshot, loaded)
        val restored = loaded.objects[1] as DrawObject.Fill
        assertTrue(restored.runs.contentEquals(fill.runs))
        assertEquals(5, restored.gapPx)
        assertEquals(40, loaded.fillTolerance)
        assertEquals(2, loaded.fillGapDp)
    }

    @Test
    fun olderVersionsDropPlaceholderFillsAndKeepUndoConsistent() {
        for (version in 1..5) {
            val bytes = legacyWithFill(version)
            val decoded = SessionCodec.decode(bytes)
            assertTrue("version $version", decoded is SessionLoad.Ok)
            val snapshot = (decoded as SessionLoad.Ok).snapshot
            assertTrue(snapshot.objects.none { it is DrawObject.Fill })
            assertTrue(snapshot.undoCount <= snapshot.objects.size)
            assertEquals(32, snapshot.fillTolerance)
            assertEquals(2, snapshot.fillGapDp)
        }
    }

    @Test
    fun fillRunCountAboveTheCapFailsAndOutOfOrderDropsThatFill() {
        val tooMany = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                writeV6Header(data)
                data.writeInt(1)
                data.writeByte(5)
                data.writeInt(0)
                data.writeInt(0)
                data.writeInt(ColorSettings.RED)
                data.writeFloat(1f)
                data.writeInt(32)
                data.writeInt(0)
                data.writeInt(4)
                data.writeInt(4)
                data.writeInt(150_001)
                data.writeInt(0)
                data.writeInt(0)
            }
        }.toByteArray()
        assertTrue(SessionCodec.decode(tooMany) is SessionLoad.Failed)

        val disordered = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                writeV6Header(data)
                data.writeInt(1)
                data.writeByte(5)
                data.writeInt(1)
                data.writeInt(2)
                data.writeInt(ColorSettings.RED)
                data.writeFloat(1f)
                data.writeInt(32)
                data.writeInt(4)
                data.writeInt(8)
                data.writeInt(8)
                data.writeInt(2)
                data.writeInt(2)
                data.writeInt(0)
                data.writeInt(1)
                data.writeInt(1)
                data.writeInt(0)
                data.writeInt(1)
                data.writeInt(0)
                data.writeInt(0)
                data.writeBoolean(false)
            }
        }.toByteArray()
        val loaded = SessionCodec.decode(disordered) as SessionLoad.Ok
        assertTrue(loaded.snapshot.objects.isEmpty())
        assertEquals(0, loaded.snapshot.undoCount)
    }

    @Test
    fun recentCountAboveTheCapFailsTheHeader() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(5)
                data.writeInt(10)
                data.writeInt(10)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("BRUSH")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                data.writeBoolean(true)
                data.writeUTF("RECTANGLE")
                data.writeBoolean(false)
                data.writeBoolean(false)
                data.writeInt(ColorSettings.RED)
                data.writeInt(ColorSettings.BLUE)
                data.writeByte(ColorSettings.MAX_RECENT + 1)
            }
        }.toByteArray()
        assertTrue(SessionCodec.decode(bytes) is SessionLoad.Failed)
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

    private fun legacyWithFill(version: Int): ByteArray {
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(version)
                data.writeInt(10)
                data.writeInt(10)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("BRUSH")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                if (version >= 2) data.writeBoolean(true)
                if (version >= 3) {
                    data.writeUTF("RECTANGLE")
                    data.writeBoolean(false)
                    data.writeBoolean(false)
                }
                if (version >= 5) {
                    data.writeInt(0xFF000000.toInt())
                    data.writeInt(0xFF000000.toInt())
                    data.writeByte(0)
                }
                data.writeInt(2)
                data.writeByte(1)
                data.writeInt(0xFF000000.toInt())
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeUTF("FINGER")
                data.writeInt(1)
                data.writeFloat(1f)
                data.writeFloat(1f)
                data.writeFloat(1f)
                data.writeLong(1L)
                data.writeByte(5)
                data.writeInt(2)
                data.writeInt(0)
                if (version >= 4) data.writeBoolean(false)
            }
        }.toByteArray()
    }

    private fun writeV6Header(data: DataOutputStream) {
        data.writeInt(SessionCodec.MAGIC)
        data.writeInt(6)
        data.writeInt(8)
        data.writeInt(8)
        data.writeInt(0xFFFFFFFF.toInt())
        data.writeUTF("FILL")
        data.writeFloat(4f)
        data.writeFloat(1f)
        data.writeFloat(16f)
        data.writeFloat(1f)
        data.writeBoolean(true)
        data.writeUTF("RECTANGLE")
        data.writeBoolean(false)
        data.writeBoolean(false)
        data.writeInt(ColorSettings.BLACK)
        data.writeInt(ColorSettings.RED)
        data.writeByte(0)
        data.writeInt(32)
        data.writeInt(2)
    }

    private fun header(version: Int): ByteArray {
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(version)
                data.writeInt(10)
                data.writeInt(10)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("BRUSH")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                if (version >= 2) data.writeBoolean(true)
                if (version >= 3) {
                    data.writeUTF("RECTANGLE")
                    data.writeBoolean(false)
                    data.writeBoolean(false)
                }
                data.writeInt(0)
                data.writeInt(0)
                data.writeInt(0)
                if (version >= 4) data.writeBoolean(false)
            }
        }.toByteArray()
    }

    private fun version3Bytes(): ByteArray {
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(SessionCodec.MAGIC)
                data.writeInt(3)
                data.writeInt(10)
                data.writeInt(20)
                data.writeInt(0xFFFFFFFF.toInt())
                data.writeUTF("SHAPE")
                data.writeFloat(4f)
                data.writeFloat(1f)
                data.writeFloat(16f)
                data.writeFloat(1f)
                data.writeBoolean(true)
                data.writeUTF("ARROW")
                data.writeBoolean(true)
                data.writeBoolean(false)
                data.writeInt(0)
                data.writeInt(0)
                data.writeInt(0)
            }
        }.toByteArray()
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
