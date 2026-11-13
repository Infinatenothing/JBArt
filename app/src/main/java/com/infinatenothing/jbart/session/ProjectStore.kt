package com.infinatenothing.jbart.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reads and writes project bytes by uri string, so the view model stays testable. */
interface ProjectFiles {
    /** Reads at most [ProjectArchive.MAX_BYTES] + 1 bytes. Throws when the uri cannot be read. */
    fun read(uri: String): ByteArray

    /** Replaces the file. When [fresh] (a new Save As target) a failed write deletes it. */
    fun write(uri: String, bytes: ByteArray, fresh: Boolean)

    fun displayName(uri: String): String?
}

class ContentProjectFiles(context: Context) : ProjectFiles {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    override fun read(uri: String): ByteArray {
        val input = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("open $uri")
        return input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= ProjectArchive.MAX_BYTES) {
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    override fun write(uri: String, bytes: ByteArray, fresh: Boolean) {
        val target = Uri.parse(uri)
        try {
            // "wt" truncates; some providers only accept "w".
            val out = try {
                resolver.openOutputStream(target, "wt")
            } catch (_: Exception) {
                resolver.openOutputStream(target, "w")
            } ?: throw IOException("open $uri")
            out.use { it.write(bytes) }
        } catch (thrown: Throwable) {
            if (fresh) {
                try {
                    DocumentsContract.deleteDocument(resolver, target)
                } catch (_: Exception) {
                }
            }
            throw thrown
        }
    }

    override fun displayName(uri: String): String? {
        return try {
            resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Keeps Save and Recents working after a restart. A VIEW grant is often not persistable. */
    fun keep(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }
}
