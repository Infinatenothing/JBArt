package com.infinatenothing.jbart

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.infinatenothing.jbart.export.PdfWriter
import com.infinatenothing.jbart.export.VectorScene
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfWriterTest {
    @Test
    fun onePageAtCanvasSizeWithBackgroundAndInk() {
        val red = 0xFFFF0000.toInt()
        val objects = listOf(
            DrawObject.Fill(0, 0, red, 1f, 0, 0, 120, 80, intArrayOf(0, 0, 10)),
            DrawObject.Shape(ShapeKind.ELLIPSE, 20f, 20f, 60f, 60f, true, red, 1f, 4f, InputType.FINGER, 15f),
            DrawObject.Line(0f, 70f, 100f, 70f, red, 0.5f, 3f, InputType.FINGER),
        )
        val flattened = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pdf_writer_test.pdf")
        try {
            file.outputStream().use {
                val ops = VectorScene.build(objects, 120, 80, 12f)
                PdfWriter.write(ops, 120, 80, 0xFF00FF00.toInt(), flattened, it)
            }
            assertEquals("%PDF", file.readBytes().copyOf(4).toString(Charsets.US_ASCII))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        assertEquals(120, page.width)
                        assertEquals(80, page.height)
                        val render = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                        page.render(render, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertEquals(0xFF00FF00.toInt(), render.getPixel(110, 10))
                        assertEquals(red, render.getPixel(40, 40))
                        assertEquals(red, render.getPixel(5, 0))
                        render.recycle()
                    }
                }
            }
        } finally {
            flattened.recycle()
            file.delete()
        }
    }
}
