package com.infinatenothing.jbart.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface ProjectRead {
    data class Ok(val snapshot: SessionSnapshot, val thumb: ByteArray?) : ProjectRead
    data object Newer : ProjectRead
    data object Corrupt : ProjectRead
}

/**
 * A `.jbart` project is a ZIP: `manifest.json` (container version, canvas size, name),
 * `session.jbs` ([SessionCodec] bytes, the only drawing data) and an optional `thumb.png`
 * preview. A newer container or codec version reads as [ProjectRead.Newer].
 */
object ProjectArchive {
    const val CONTAINER_VERSION = 1
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MIME = "application/x-jbart"
    const val EXTENSION = "jbart"
    private const val MANIFEST = "manifest.json"
    private const val SESSION = "session.jbs"
    private const val THUMB = "thumb.png"
    private val containerField = Regex("\"container\"\\s*:\\s*(\\d+)")

    fun pack(snapshot: SessionSnapshot, name: String, thumbPng: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val manifest = "{\"container\":$CONTAINER_VERSION,\"width\":${snapshot.width}," +
                "\"height\":${snapshot.height},\"name\":${jsonString(name)}}"
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry(SESSION))
            zip.write(SessionCodec.encode(snapshot))
            if (thumbPng != null) {
                zip.putNextEntry(ZipEntry(THUMB))
                zip.write(thumbPng)
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun unpack(bytes: ByteArray): ProjectRead {
        if (bytes.size > MAX_BYTES) return ProjectRead.Corrupt
        var manifest: String? = null
        var session: ByteArray? = null
        var thumb: ByteArray? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST -> manifest = String(readCapped(zip), Charsets.UTF_8)
                        SESSION -> session = readCapped(zip)
                        THUMB -> thumb = readCapped(zip)
                    }
                }
            }
        } catch (_: Exception) {
            return ProjectRead.Corrupt
        }
        val container = manifest?.let { containerField.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return ProjectRead.Corrupt
        if (container > CONTAINER_VERSION) return ProjectRead.Newer
        if (container < 1) return ProjectRead.Corrupt
        val inner = session ?: return ProjectRead.Corrupt
        return when (val decoded = SessionCodec.decode(inner)) {
            is SessionLoad.Ok -> ProjectRead.Ok(decoded.snapshot, thumb)
            is SessionLoad.Failed ->
                if (decoded.reason.startsWith("unknown version")) ProjectRead.Newer else ProjectRead.Corrupt
            SessionLoad.Missing -> ProjectRead.Corrupt
        }
    }

    /** Inflated entries are capped too, so a small ZIP cannot expand past [MAX_BYTES]. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) error("entry too large")
        }
        return out.toByteArray()
    }

    private fun jsonString(value: String): String {
        val text = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' || c == '\\' -> text.append('\\').append(c)
                c < ' ' -> text.append(String.format("\\u%04x", c.code))
                else -> text.append(c)
            }
        }
        return text.append('"').toString()
    }
}
