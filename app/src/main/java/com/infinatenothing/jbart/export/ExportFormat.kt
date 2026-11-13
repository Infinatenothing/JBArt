package com.infinatenothing.jbart.export

enum class ExportFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    BMP("image/bmp", "bmp"),
    GIF("image/gif", "gif"),
}
