package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.DrawObject
import com.infinatenothing.jbart.model.InputType
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.session.ProjectArchive
import com.infinatenothing.jbart.session.ProjectRead
import com.infinatenothing.jbart.session.SessionCodec
import com.infinatenothing.jbart.session.SessionSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {
    @Test
    fun roundTripKeepsSnapshotPendingAndThumb() {
        val snapshot = snapshot()
        val thumb = byteArrayOf(1, 2, 3)
        val read = ProjectArchive.unpack(ProjectArchive.pack(snapshot, "a \"quoted\"\\name\n.jbart", thumb))
        assertTrue(read is ProjectRead.Ok)
        read as ProjectRead.Ok
        assertEquals(snapshot, read.snapshot)
        assertArrayEquals(thumb, read.thumb)
    }

    @Test
    fun manifestCarriesContainerVersionSizeAndName() {
        val bytes = ProjectArchive.pack(snapshot(), "Cat.jbart", null)
        val manifest = entries(bytes).getValue("manifest.json").toString(Charsets.UTF_8)
        assertEquals("{\"container\":1,\"width\":40,\"height\":30,\"name\":\"Cat.jbart\"}", manifest)
        assertTrue("thumb.png" !in entries(bytes))
    }

    @Test
    fun olderInnerCodecVersionLoads() {
        val read = ProjectArchive.unpack(zip(manifest(1), version1Session()))
        assertTrue(read is ProjectRead.Ok)
        assertEquals(10, (read as ProjectRead.Ok).snapshot.width)
    }

    @Test
    fun newerContainerOrCodecIsNewer() {
        val inner = SessionCodec.encode(snapshot())
        assertEquals(ProjectRead.Newer, ProjectArchive.unpack(zip(manifest(2), inner)))
        val newerCodec = inner.copyOf().also { it[7] = (SessionCodec.VERSION + 1).toByte() }
        assertEquals(ProjectRead.Newer, ProjectArchive.unpack(zip(manifest(1), newerCodec)))
    }

    @Test
    fun brokenFilesAreCorrupt() {
        val good = ProjectArchive.pack(snapshot(), "x", null)
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack("not a zip".toByteArray()))
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack(good.copyOf(good.size / 2)))
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack(zip(manifest(1), null)))
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack(zip(null, SessionCodec.encode(snapshot()))))
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack(zip(manifest(1), byteArrayOf(1, 2, 3))))
        assertEquals(ProjectRead.Corrupt, ProjectArchive.unpack(ByteArray(ProjectArchive.MAX_BYTES + 1)))
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun manifest(container: Int) = "{\"container\": $container}".toByteArray()

    private fun zip(manifest: ByteArray?, session: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest)
            }
            if (session != null) {
                zip.putNextEntry(ZipEntry("session.jbs"))
                zip.write(session)
            }
        }
        return out.toByteArray()
    }

    private fun version1Session(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(SessionCodec.MAGIC)
            data.writeInt(1)
            data.writeInt(10)
            data.writeInt(20)
            data.writeInt(0xFFFFFFFF.toInt())
            data.writeUTF("BRUSH")
            repeat(4) { data.writeFloat(1f) }
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
        }
        return out.toByteArray()
    }

    private fun snapshot(): SessionSnapshot {
        val line = DrawObject.Line(
            x1 = 1f, y1 = 2f, x2 = 3f, y2 = 4f,
            color = 0xFF112233.toInt(), widthPx = 5f, alpha = 1f, inputType = InputType.FINGER,
        )
        val shape = DrawObject.Shape(
            kind = ShapeKind.ELLIPSE, x1 = 1f, y1 = 1f, x2 = 9f, y2 = 9f,
            color = 0xFF000000.toInt(), widthPx = 2f, alpha = 1f, filled = false, rotationDeg = 30f, inputType = InputType.FINGER,
        )
        return SessionSnapshot(
            width = 40,
            height = 30,
            backgroundColor = 0xFFFFFFFF.toInt(),
            objects = listOf(line, DrawObject.Clear),
            undoCount = 2,
            redo = listOf(line),
            tool = Tool.SHAPE,
            brush = BrushSettings(),
            eraser = BrushSettings(),
            pending = shape,
        )
    }
}
