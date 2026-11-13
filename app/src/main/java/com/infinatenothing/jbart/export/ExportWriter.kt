package com.infinatenothing.jbart.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

object ExportWriter {
    fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        displayName: String,
    ): android.net.Uri = saveToMediaStore(context, format, displayName) { writeBitmap(bitmap, format, quality, it) }

    /** Images go to Pictures/JBArt. PDF goes to Documents/JBArt: MediaStore only allows image types under Pictures. */
    fun saveToMediaStore(
        context: Context,
        format: ExportFormat,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): android.net.Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePending(context, format, displayName, write)
        } else {
            saveLegacy(context, format, displayName, write)
        }
    }

    fun writeShareFile(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat,
        quality: Int,
        displayName: String,
    ): android.net.Uri = writeShareFile(context, displayName) { writeBitmap(bitmap, format, quality, it) }

    fun writeShareFile(context: Context, displayName: String, write: (OutputStream) -> Unit): android.net.Uri {
        val dir = File(context.cacheDir, "exports")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdir")
        dir.listFiles()?.forEach { child ->
            if (!child.delete()) throw IOException("clear cache")
        }
        val file = File(dir, displayName)
        try {
            FileOutputStream(file).use(write)
        } catch (thrown: Throwable) {
            file.delete()
            throw thrown
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun isImage(format: ExportFormat): Boolean = format.mimeType.startsWith("image/")

    private fun directory(format: ExportFormat): String {
        return if (isImage(format)) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOCUMENTS
    }

    private fun collection(format: ExportFormat): android.net.Uri {
        return if (isImage(format)) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
    }

    private fun savePending(
        context: Context,
        format: ExportFormat,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): android.net.Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directory(format)}/JBArt")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(format), values)
            ?: throw IOException("insert")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("stream")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("publish")
            return uri
        } catch (thrown: Throwable) {
            resolver.delete(uri, null, null)
            throw thrown
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        format: ExportFormat,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): android.net.Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(directory(format)), "JBArt")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdir")
        val file = File(dir, displayName)
        var uri: android.net.Uri? = null
        try {
            FileOutputStream(file).use(write)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
            }
            uri = context.contentResolver.insert(collection(format), values)
                ?: throw IOException("insert")
            return uri
        } catch (thrown: Throwable) {
            uri?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
            throw thrown
        }
    }

    private fun writeBitmap(bitmap: Bitmap, format: ExportFormat, quality: Int, out: OutputStream) {
        when (format) {
            ExportFormat.PNG -> {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("png")
            }
            ExportFormat.JPEG -> {
                val level = quality.coerceIn(50, 100)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, level, out)) throw IOException("jpeg")
            }
            ExportFormat.BMP -> {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                BmpEncoder.encode(pixels, bitmap.width, bitmap.height, out)
            }
            ExportFormat.GIF -> {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val palette = GifPalette.build(pixels)
                val indices = GifPalette.map(pixels, bitmap.width, bitmap.height, palette)
                GifEncoder.encode(indices, bitmap.width, bitmap.height, palette.colors, palette.transparentIndex, out)
            }
            ExportFormat.PDF, ExportFormat.SVG -> throw IllegalArgumentException("vector format")
        }
    }
}
