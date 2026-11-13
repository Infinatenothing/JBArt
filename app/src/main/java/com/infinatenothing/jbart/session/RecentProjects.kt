package com.infinatenothing.jbart.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

data class RecentProject(val uri: String, val name: String, val openedAt: Long, val thumb: File?)

/** Most recent first, capped at [max]. Thumbnails are cached PNGs named by a hash of the uri. */
class RecentProjects(filesDir: File, private val max: Int = MAX) {
    private val dir = File(filesDir, "recents")
    private val index = File(dir, "index.bin")

    @Synchronized
    fun list(): List<RecentProject> {
        if (!index.exists()) return emptyList()
        return try {
            DataInputStream(ByteArrayInputStream(index.readBytes())).use { data ->
                if (data.readInt() != VERSION) return emptyList()
                List(data.readInt().coerceIn(0, max)) {
                    val uri = data.readUTF()
                    RecentProject(uri, data.readUTF(), data.readLong(), thumbFile(uri).takeIf { it.isFile })
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(uri: String, name: String, thumbPng: ByteArray?, now: Long): List<RecentProject> {
        dir.mkdirs()
        if (thumbPng != null) thumbFile(uri).writeBytes(thumbPng)
        val kept = list().filter { it.uri != uri }
        val next = listOf(RecentProject(uri, name, now, thumbFile(uri).takeIf { it.isFile })) + kept
        next.drop(max).forEach { thumbFile(it.uri).delete() }
        return write(next.take(max))
    }

    @Synchronized
    fun remove(uri: String): List<RecentProject> {
        thumbFile(uri).delete()
        return write(list().filter { it.uri != uri })
    }

    private fun write(entries: List<RecentProject>): List<RecentProject> {
        dir.mkdirs()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(VERSION)
            data.writeInt(entries.size)
            entries.forEach {
                data.writeUTF(it.uri)
                data.writeUTF(it.name)
                data.writeLong(it.openedAt)
            }
        }
        writeAtomic(index, bytes.toByteArray())
        return entries
    }

    private fun thumbFile(uri: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }

    companion object {
        const val MAX = 12
        private const val VERSION = 1
    }
}

/** The project file Save writes to. Lives next to the autosave so it survives process death. */
data class ProjectLinkState(val uri: String?, val name: String?, val dirty: Boolean)

class ProjectLink(filesDir: File) {
    private val file = File(File(filesDir, "session"), "project.link")

    @Synchronized
    fun read(): ProjectLinkState {
        if (!file.exists()) return ProjectLinkState(null, null, false)
        return try {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { data ->
                val hasUri = data.readBoolean()
                val uri = data.readUTF()
                val name = data.readUTF()
                ProjectLinkState(uri.takeIf { hasUri }, name.takeIf { hasUri }, data.readBoolean())
            }
        } catch (_: Exception) {
            ProjectLinkState(null, null, false)
        }
    }

    @Synchronized
    fun write(state: ProjectLinkState) {
        file.parentFile?.mkdirs()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeBoolean(state.uri != null)
            data.writeUTF(state.uri.orEmpty())
            data.writeUTF(state.name.orEmpty())
            data.writeBoolean(state.dirty)
        }
        writeAtomic(file, bytes.toByteArray())
    }
}

private fun writeAtomic(dest: File, bytes: ByteArray) {
    val tmp = File(dest.parentFile, "${dest.name}.tmp")
    tmp.writeBytes(bytes)
    if (dest.exists() && !dest.delete()) error("replace ${dest.name}")
    if (!tmp.renameTo(dest)) {
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
    }
}
