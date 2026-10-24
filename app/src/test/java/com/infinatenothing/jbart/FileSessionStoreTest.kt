package com.infinatenothing.jbart

import com.infinatenothing.jbart.session.FileSessionStore
import com.infinatenothing.jbart.session.SessionLoad
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileSessionStoreTest {
    @Test
    fun corruptFileIsDeleted() {
        val dir = Files.createTempDirectory("jbart-session").toFile()
        val store = FileSessionStore(dir)
        val file = File(dir, "session/current.jbs")
        file.parentFile?.mkdirs()
        file.writeText("garbage")
        val loaded = store.load()
        assertTrue(loaded is SessionLoad.Failed)
        assertTrue(!file.exists())
    }
}
