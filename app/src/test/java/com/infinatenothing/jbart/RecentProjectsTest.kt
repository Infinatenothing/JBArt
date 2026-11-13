package com.infinatenothing.jbart

import com.infinatenothing.jbart.session.ProjectLink
import com.infinatenothing.jbart.session.ProjectLinkState
import com.infinatenothing.jbart.session.RecentProjects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentProjectsTest {
    private val dir: File = Files.createTempDirectory("jbart-recents").toFile()

    @Test
    fun newestFirstReopenMovesToTopAndCapDropsOldest() {
        val recents = RecentProjects(dir, max = 3)
        recents.add("u1", "one", byteArrayOf(1), 1)
        recents.add("u2", "two", null, 2)
        recents.add("u3", "three", null, 3)
        recents.add("u1", "one", null, 4)
        recents.add("u4", "four", null, 5)
        val list = RecentProjects(dir, max = 3).list()
        assertEquals(listOf("u4", "u1", "u3"), list.map { it.uri })
        assertArrayEquals(byteArrayOf(1), list[1].thumb!!.readBytes())
        assertNull(list[0].thumb)
    }

    @Test
    fun removeDropsEntryAndThumb() {
        val recents = RecentProjects(dir)
        recents.add("u1", "one", byteArrayOf(1), 1)
        val thumb = recents.list().single().thumb!!
        assertEquals(emptyList<Any>(), recents.remove("u1"))
        assertEquals(false, thumb.exists())
    }

    @Test
    fun corruptIndexReadsEmpty() {
        File(dir, "recents").mkdirs()
        File(dir, "recents/index.bin").writeText("junk")
        assertEquals(emptyList<Any>(), RecentProjects(dir).list())
    }

    @Test
    fun linkRoundTripsAndDefaultsToUntitled() {
        val link = ProjectLink(dir)
        assertEquals(ProjectLinkState(null, null, false), link.read())
        link.write(ProjectLinkState("content://a", "a.jbart", true))
        assertEquals(ProjectLinkState("content://a", "a.jbart", true), ProjectLink(dir).read())
        link.write(ProjectLinkState(null, null, false))
        assertEquals(ProjectLinkState(null, null, false), ProjectLink(dir).read())
    }
}
