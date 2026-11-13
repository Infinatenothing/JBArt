package com.infinatenothing.jbart.export

enum class ExportFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    BMP("image/bmp", "bmp"),
    GIF("image/gif", "gif"),
    PDF("application/pdf", "pdf"),
    SVG("image/svg+xml", "svg"),
    ;

    val supportsTransparent: Boolean get() = this == PNG || this == GIF || this == SVG
    val isVector: Boolean get() = this == PDF || this == SVG
}
