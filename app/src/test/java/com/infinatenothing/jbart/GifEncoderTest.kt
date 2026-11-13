package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.GifEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class GifEncoderTest {
    @Test
    fun headerTableAndTrailer() {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val indices = byteArrayOf(0, 1, 2, 1)
        val bytes = encode(indices, 2, 2, palette, null)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(2, readShort(bytes, 6))
        assertEquals(2, readShort(bytes, 8))
        assertEquals(0xF1, bytes[10].toInt() and 0xFF)
        assertEquals(0xFF, bytes[13].toInt() and 0xFF)
        assertEquals(0xFF, bytes[17].toInt() and 0xFF)
        assertEquals(0x2C, bytes[13 + 12].toInt() and 0xFF)
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        val decoded = decode(bytes)
        assertEquals(4, decoded.tableSize)
        assertNull(decoded.transparentIndex)
        assertArrayEquals(indices, decoded.indices)
    }

    @Test
    fun transparentIndexWritesGraphicControlExtension() {
        val bytes = encode(byteArrayOf(0, 1), 2, 1, intArrayOf(0xFF000000.toInt(), 0), 1)
        assertEquals(0x21, bytes[13 + 6].toInt() and 0xFF)
        assertEquals(0xF9, bytes[13 + 7].toInt() and 0xFF)
        assertEquals(1, decode(bytes).transparentIndex)
    }

    @Test
    fun singlePixelRoundTrips() {
        val bytes = encode(byteArrayOf(0), 1, 1, intArrayOf(0xFF123456.toInt()), null)
        assertArrayEquals(byteArrayOf(0), decode(bytes).indices)
    }

    @Test
    fun noisyFullPaletteRoundTripsAcrossDictionaryResets() {
        val random = Random(7)
        val indices = ByteArray(300 * 200) { random.nextInt(256).toByte() }
        val bytes = encode(indices, 300, 200, IntArray(256) { 0xFF000000.toInt() or it }, null)
        assertArrayEquals(indices, decode(bytes).indices)
    }

    @Test
    fun twoColorRunsRoundTrip() {
        val random = Random(3)
        val indices = ByteArray(257 * 129) { if (random.nextInt(10) < 8) 0 else 1 }
        val bytes = encode(indices, 257, 129, intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), null)
        assertArrayEquals(indices, decode(bytes).indices)
    }

    private fun encode(indices: ByteArray, width: Int, height: Int, palette: IntArray, clear: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(indices, width, height, palette, clear, out)
        return out.toByteArray()
    }

    private class Decoded(val tableSize: Int, val transparentIndex: Int?, val indices: ByteArray)

    /** Minimal single-frame GIF reader used only to check the encoder. */
    private fun decode(bytes: ByteArray): Decoded {
        val tableSize = 2 shl (bytes[10].toInt() and 7)
        var at = 13 + tableSize * 3
        var transparent: Int? = null
        while ((bytes[at].toInt() and 0xFF) == 0x21) {
            if ((bytes[at + 1].toInt() and 0xFF) == 0xF9 && (bytes[at + 3].toInt() and 1) == 1) {
                transparent = bytes[at + 6].toInt() and 0xFF
            }
            at += 2
            while (bytes[at].toInt() != 0) at += (bytes[at].toInt() and 0xFF) + 1
            at++
        }
        assertEquals(0x2C, bytes[at].toInt() and 0xFF)
        val width = readShort(bytes, at + 5)
        val height = readShort(bytes, at + 7)
        at += 10
        val minCodeSize = bytes[at++].toInt()
        val data = ByteArrayOutputStream()
        while (bytes[at].toInt() != 0) {
            val length = bytes[at].toInt() and 0xFF
            data.write(bytes, at + 1, length)
            at += length + 1
        }
        assertEquals(0x3B, bytes[at + 1].toInt() and 0xFF)
        return Decoded(tableSize, transparent, lzw(data.toByteArray(), minCodeSize, width * height))
    }

    private fun lzw(data: ByteArray, minCodeSize: Int, pixels: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val table = ArrayList<IntArray>()
        fun reset() {
            table.clear()
            for (code in 0 until clear) table.add(intArrayOf(code))
            table.add(IntArray(0))
            table.add(IntArray(0))
        }
        reset()
        val out = ByteArrayOutputStream()
        var codeSize = minCodeSize + 1
        var bitPos = 0
        var previous: IntArray? = null
        while (true) {
            var code = 0
            for (bit in 0 until codeSize) {
                val byte = data[(bitPos + bit) / 8].toInt() and 0xFF
                code = code or (((byte ushr ((bitPos + bit) % 8)) and 1) shl bit)
            }
            bitPos += codeSize
            if (code == clear) {
                reset()
                codeSize = minCodeSize + 1
                previous = null
                continue
            }
            if (code == end) break
            val entry = when {
                code < table.size -> table[code]
                previous != null && code == table.size -> previous + previous[0]
                else -> error("bad code $code")
            }
            for (value in entry) out.write(value)
            if (previous != null && table.size < 4096) {
                table.add(previous + entry[0])
                if (table.size == 1 shl codeSize && codeSize < 12) codeSize++
            }
            previous = entry
        }
        val result = out.toByteArray()
        assertEquals(pixels, result.size)
        return result
    }

    private fun readShort(bytes: ByteArray, at: Int): Int {
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }
}
