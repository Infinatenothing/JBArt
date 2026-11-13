package com.infinatenothing.jbart.export

import com.infinatenothing.jbart.canvas.ShapeGeometry
import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/**
 * SVG 1.1 serializer for [VectorScene] ops. Coordinates are canvas pixels.
 * Images are embedded as PNG data URIs from [pngBytes]. No Android types.
 * There is no user text in the file, so nothing needs XML escaping.
 */
object SvgWriter {
    fun encode(
        ops: List<VectorOp>,
        width: Int,
        height: Int,
        background: Int,
        transparent: Boolean,
        pngBytes: (VectorOp) -> ByteArray,
        out: OutputStream,
    ) {
        require(width > 0 && height > 0)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\" " +
                "width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">\n",
        )
        writer.write("<title>JBArt</title>\n")
        if (!transparent) {
            writer.write("<rect width=\"$width\" height=\"$height\" fill=\"${hex(background)}\"/>\n")
        }
        for (op in ops) {
            when (op) {
                is VectorOp.Flattened -> image(writer, 0, 0, width, height, pngBytes(op))
                is VectorOp.FillImage -> image(writer, op.left, op.top, op.width, op.height, pngBytes(op))
                is VectorOp.Group -> group(writer, op)
            }
        }
        writer.write("</svg>\n")
        writer.flush()
    }

    private fun image(writer: Appendable, x: Int, y: Int, width: Int, height: Int, png: ByteArray) {
        writer.append("<image x=\"$x\" y=\"$y\" width=\"$width\" height=\"$height\" xlink:href=\"data:image/png;base64,")
        writer.append(Base64.getEncoder().encodeToString(png))
        writer.append("\"/>\n")
    }

    private fun group(writer: Appendable, op: VectorOp.Group) {
        val color = hex(op.rgb)
        writer.append("<g fill=\"none\" stroke=\"$color\" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
        if (op.opacity < 1f) writer.append(" opacity=\"${num(op.opacity, 4)}\"")
        if (op.rotationDeg != 0f) {
            writer.append(" transform=\"rotate(${num(op.rotationDeg)} ${num(op.pivotX)} ${num(op.pivotY)})\"")
        }
        writer.append(">\n")
        for (mark in op.marks) {
            when (val geometry = mark.geometry) {
                is Geometry.Polyline -> {
                    writer.append("<path d=\"M")
                    for (i in geometry.xy.indices step 2) {
                        if (i > 0) writer.append(" L")
                        writer.append(' ').append(num(geometry.xy[i])).append(' ').append(num(geometry.xy[i + 1]))
                    }
                    if (geometry.closed) writer.append(" Z")
                    writer.append('"')
                }
                is Geometry.Quad -> writer.append(
                    "<path d=\"M ${num(geometry.startX)} ${num(geometry.startY)} " +
                        "Q ${num(geometry.controlX)} ${num(geometry.controlY)} ${num(geometry.endX)} ${num(geometry.endY)}\"",
                )
                is Geometry.Oval -> writer.append(
                    "<ellipse cx=\"${num((geometry.left + geometry.right) * 0.5f)}\" " +
                        "cy=\"${num((geometry.top + geometry.bottom) * 0.5f)}\" " +
                        "rx=\"${num((geometry.right - geometry.left) * 0.5f)}\" " +
                        "ry=\"${num((geometry.bottom - geometry.top) * 0.5f)}\"",
                )
            }
            if (mark.filled) {
                writer.append(" fill=\"$color\" stroke=\"none\"")
            } else {
                writer.append(" stroke-width=\"${num(mark.width)}\"")
                if (!mark.roundJoin) {
                    writer.append(" stroke-linejoin=\"miter\" stroke-miterlimit=\"${num(ShapeGeometry.MITER_LIMIT)}\"")
                }
            }
            writer.append("/>\n")
        }
        writer.append("</g>\n")
    }

    /** #RRGGBB from the low 24 bits. */
    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    /** Rounded to [places] decimals with trailing zeros dropped: 2.50 -> "2.5", 3.00 -> "3". */
    fun num(value: Float, places: Int = 2): String {
        return BigDecimal(value.toDouble()).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
