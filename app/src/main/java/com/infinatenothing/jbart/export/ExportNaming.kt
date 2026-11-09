package com.infinatenothing.jbart.export

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ExportNaming {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun fileName(now: LocalDateTime, format: ExportFormat): String {
        return "JBArt_${now.format(stamp)}.${format.extension}"
    }
}
