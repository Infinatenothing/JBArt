package com.infinatenothing.jbart.session

import java.io.File
import java.io.FileOutputStream

interface SessionStore {
    fun load(): SessionLoad
    fun save(snapshot: SessionSnapshot)
    fun delete()
}

class FileSessionStore(private val filesDir: File) : SessionStore {
    override fun load(): SessionLoad = locked {
        val file = file()
        if (!file.exists()) return@locked SessionLoad.Missing
        val decoded = try {
            SessionCodec.decode(file.readBytes())
        } catch (e: Exception) {
            SessionLoad.Failed(e.message ?: "corrupt")
        }
        if (decoded is SessionLoad.Failed) file.delete()
        decoded
    }

    override fun save(snapshot: SessionSnapshot) = locked {
        val dir = File(filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) error("session dir")
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(SessionCodec.encode(snapshot))
        val dest = File(dir, FILE)
        if (dest.exists() && !dest.delete()) error("session replace")
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    override fun delete() {
        locked { file().delete() }
    }

    private fun file(): File = File(File(filesDir, DIR), FILE)

    private fun <T> locked(block: () -> T): T {
        val dir = File(filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return FileOutputStream(File(dir, "$FILE.lock")).use { out ->
            out.channel.lock().use { block() }
        }
    }

    private companion object {
        const val DIR = "session"
        const val FILE = "current.jbs"
    }
}
