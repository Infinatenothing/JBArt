package com.infinatenothing.jbart.export

import java.io.OutputStream

/**
 * 24-bit uncompressed BMP. Rows are bottom-up BGR, padded to 4 bytes.
 * [pixels] are straight ARGB and must already be opaque; alpha is not written.
 */
object BmpEncoder {
    fun encode(pixels: IntArray, width: Int, height: Int, out: OutputStream) {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
        val stride = (width * 3 + 3) and 3.inv()
        val imageSize = stride * height
        val fileSize = HEADER_BYTES + imageSize
        val header = ByteArray(HEADER_BYTES)
        header[0] = 'B'.code.toByte()
        header[1] = 'M'.code.toByte()
        writeInt(header, 2, fileSize)
        writeInt(header, 10, HEADER_BYTES)
        writeInt(header, 14, INFO_BYTES)
        writeInt(header, 18, width)
        writeInt(header, 22, height)
        writeShort(header, 26, 1)
        writeShort(header, 28, 24)
        writeInt(header, 34, imageSize)
        out.write(header)
        val row = ByteArray(stride)
        for (y in height - 1 downTo 0) {
            var offset = 0
            val start = y * width
            for (x in 0 until width) {
                val pixel = pixels[start + x]
                row[offset++] = (pixel and 0xFF).toByte()
                row[offset++] = ((pixel ushr 8) and 0xFF).toByte()
                row[offset++] = ((pixel ushr 16) and 0xFF).toByte()
            }
            while (offset < stride) row[offset++] = 0
            out.write(row)
        }
    }

    private fun writeInt(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = ((value ushr 8) and 0xFF).toByte()
        into[at + 2] = ((value ushr 16) and 0xFF).toByte()
        into[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeShort(into: ByteArray, at: Int, value: Int) {
        into[at] = (value and 0xFF).toByte()
        into[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private const val HEADER_BYTES = 54
    private const val INFO_BYTES = 40
}
