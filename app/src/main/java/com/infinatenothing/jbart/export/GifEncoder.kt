package com.infinatenothing.jbart.export

import java.io.OutputStream

/**
 * Single-frame GIF89a: global color table, optional transparent index, LZW image data.
 * [indices] are palette indices, one byte per pixel, row-major.
 */
object GifEncoder {
    fun encode(
        indices: ByteArray,
        width: Int,
        height: Int,
        palette: IntArray,
        transparentIndex: Int?,
        out: OutputStream,
    ) {
        require(width in 1..0xFFFF && height in 1..0xFFFF)
        require(indices.size == width * height)
        require(palette.size in 1..256)
        require(transparentIndex == null || transparentIndex in palette.indices)
        var tableBits = 1
        while (1 shl tableBits < palette.size) tableBits++
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        out.write(0x80 or (7 shl 4) or (tableBits - 1))
        out.write(0)
        out.write(0)
        val table = ByteArray(3 shl tableBits)
        for (index in palette.indices) {
            val color = palette[index]
            table[index * 3] = (color ushr 16).toByte()
            table[index * 3 + 1] = (color ushr 8).toByte()
            table[index * 3 + 2] = color.toByte()
        }
        out.write(table)
        if (transparentIndex != null) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 1, 0, 0, transparentIndex.toByte(), 0))
        }
        out.write(0x2C)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        out.write(0)
        val minCodeSize = maxOf(2, tableBits)
        out.write(minCodeSize)
        compress(indices, minCodeSize, BlockWriter(out))
        out.write(0x3B)
        out.flush()
    }

    private fun compress(indices: ByteArray, minCodeSize: Int, writer: BlockWriter) {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val keys = IntArray(HASH_SIZE) { -1 }
        val codes = IntArray(HASH_SIZE)
        var next = end + 1
        var codeSize = minCodeSize + 1
        writer.code(clear, codeSize)
        var prefix = indices[0].toInt() and 0xFF
        for (at in 1 until indices.size) {
            val pixel = indices[at].toInt() and 0xFF
            val key = (prefix shl 8) or pixel
            var slot = key % HASH_SIZE
            while (keys[slot] != -1 && keys[slot] != key) {
                slot = if (slot + 1 == HASH_SIZE) 0 else slot + 1
            }
            if (keys[slot] == key) {
                prefix = codes[slot]
                continue
            }
            writer.code(prefix, codeSize)
            if (next < MAX_CODES) {
                keys[slot] = key
                codes[slot] = next
                // The decoder adds this entry one code later, so widen as soon as it is assigned.
                if (next == 1 shl codeSize) codeSize++
                next++
            } else {
                writer.code(clear, codeSize)
                keys.fill(-1)
                next = end + 1
                codeSize = minCodeSize + 1
            }
            prefix = pixel
        }
        writer.code(prefix, codeSize)
        writer.code(end, codeSize)
        writer.finish()
    }

    /** Packs codes LSB first into 255-byte sub-blocks, then the zero-length terminator. */
    private class BlockWriter(private val out: OutputStream) {
        private val block = ByteArray(256)
        private var size = 0
        private var bits = 0
        private var bitCount = 0

        fun code(value: Int, width: Int) {
            bits = bits or (value shl bitCount)
            bitCount += width
            while (bitCount >= 8) {
                byte(bits and 0xFF)
                bits = bits ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) byte(bits and 0xFF)
            flush()
            out.write(0)
        }

        private fun byte(value: Int) {
            block[++size] = value.toByte()
            if (size == 255) flush()
        }

        private fun flush() {
            if (size == 0) return
            block[0] = size.toByte()
            out.write(block, 0, size + 1)
            size = 0
        }
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private const val MAX_CODES = 4096
    private const val HASH_SIZE = 8191
}
