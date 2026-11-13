package com.infinatenothing.jbart

import com.infinatenothing.jbart.export.ExportFormat
import com.infinatenothing.jbart.export.ExportNaming
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ExportNamingTest {
    @Test
    fun fileNameUsesLocalStampAndExtension() {
        val now = LocalDateTime.of(2026, 11, 8, 17, 59, 1)
        assertEquals("JBArt_20261108_175901.png", ExportNaming.fileName(now, ExportFormat.PNG))
        assertEquals("JBArt_20261108_175901.jpg", ExportNaming.fileName(now, ExportFormat.JPEG))
        assertEquals("JBArt_20261108_175901.bmp", ExportNaming.fileName(now, ExportFormat.BMP))
        assertEquals("JBArt_20261108_175901.gif", ExportNaming.fileName(now, ExportFormat.GIF))
        assertEquals("JBArt_20261108_175901.pdf", ExportNaming.fileName(now, ExportFormat.PDF))
        assertEquals("JBArt_20261108_175901.svg", ExportNaming.fileName(now, ExportFormat.SVG))
    }
}
