package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.BmpEncoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class BmpEncoderTest {
    @Test
    fun oneRedPixelIsBottomUpBgrWithPad() {
        val out = ByteArrayOutputStream()
        BmpEncoder.encode(intArrayOf(0xFF112233.toInt()), 1, 1, out)
        val bytes = out.toByteArray()
        assertEquals(58, bytes.size)
        assertEquals('B'.code.toByte(), bytes[0])
        assertEquals('M'.code.toByte(), bytes[1])
        assertEquals(58, readInt(bytes, 2))
        assertEquals(54, readInt(bytes, 10))
        assertEquals(40, readInt(bytes, 14))
        assertEquals(1, readInt(bytes, 18))
        assertEquals(1, readInt(bytes, 22))
        assertEquals(1, readShort(bytes, 26))
        assertEquals(24, readShort(bytes, 28))
        assertEquals(0x33.toByte(), bytes[54])
        assertEquals(0x22.toByte(), bytes[55])
        assertEquals(0x11.toByte(), bytes[56])
        assertEquals(0.toByte(), bytes[57])
    }

    @Test
    fun rowsAreBottomUpAndWidthTwoIsPadded() {
        val top = 0xFF00FF00.toInt()
        val bottomLeft = 0xFF0000FF.toInt()
        val bottomRight = 0xFFFFFFFF.toInt()
        val out = ByteArrayOutputStream()
        BmpEncoder.encode(intArrayOf(top, top, bottomLeft, bottomRight), 2, 2, out)
        val bytes = out.toByteArray()
        val stride = 8
        assertEquals(54 + stride * 2, bytes.size)
        assertEquals(0xFF.toByte(), bytes[54])
        assertEquals(0x00.toByte(), bytes[55])
        assertEquals(0x00.toByte(), bytes[56])
        assertEquals(0xFF.toByte(), bytes[57])
        assertEquals(0xFF.toByte(), bytes[58])
        assertEquals(0xFF.toByte(), bytes[59])
        assertEquals(0.toByte(), bytes[60])
        assertEquals(0.toByte(), bytes[61])
        assertEquals(0x00.toByte(), bytes[54 + stride])
        assertEquals(0xFF.toByte(), bytes[55 + stride])
        assertEquals(0x00.toByte(), bytes[56 + stride])
    }

    private fun readInt(bytes: ByteArray, at: Int): Int {
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShort(bytes: ByteArray, at: Int): Int {
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }
}
