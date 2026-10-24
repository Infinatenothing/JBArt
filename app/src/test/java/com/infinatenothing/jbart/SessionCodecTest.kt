package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.StrokePoint
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.session.SessionCodec
import com.infinatenothing.jbart.session.SessionLoad
import com.infinatenothing.jbart.session.SessionSnapshot
import org.junit.Assert.assertEquals
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
            objects = listOf(stroke(1f), erase(2f), stroke(3f)),
            undoCount = 2,
            redo = listOf(erase(9f)),
            tool = Tool.ERASER,
            brush = BrushSettings(sizeDp = 12f, opacity = 0.4f),
            eraser = BrushSettings(sizeDp = 20f, opacity = 1f),
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(snapshot))
        assertEquals(SessionLoad.Ok(snapshot), decoded)
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

    private fun erase(x: Float) = DrawObject.EraseStroke(
        points = listOf(StrokePoint(x, 2f, 1f, 30L)),
        baseWidthPx = 16f,
        inputType = InputType.FINGER,
    )
}
