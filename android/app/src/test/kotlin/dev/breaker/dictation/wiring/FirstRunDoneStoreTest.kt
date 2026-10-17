package dev.breaker.dictation.wiring

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FirstRunDoneStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun marker(): File = File(tmp.root, FirstRunDoneStore.FILE_NAME)

    private fun names(folder: File): List<String> = (folder.list() ?: emptyArray()).sorted()

    @Test
    fun `a missing marker file is not done`() {
        assertFalse("app: a missing marker must not count as done", FirstRunDoneStore(marker()).isDone())
    }

    @Test
    fun `the marker is done after markDone`() {
        val store = FirstRunDoneStore(marker())
        assertTrue("app: markDone must report the write", store.markDone())
        assertTrue("app: the marker must be done after markDone", store.isDone())
    }

    @Test
    fun `a marker file made by hand is done`() {
        marker().writeText("anything")
        assertTrue("app: a marker file made by hand must count as done", FirstRunDoneStore(marker()).isDone())
    }

    @Test
    fun `markDone creates a folder that does not exist yet`() {
        val file = File(tmp.root, "deep/nested/" + FirstRunDoneStore.FILE_NAME)
        assertTrue("app: markDone must write into a folder it creates", FirstRunDoneStore(file).markDone())
        assertTrue("app: the marker must exist inside the new folder", file.isFile)
    }

    @Test
    fun `markDone answers false and does not throw when the parent is a regular file`() {
        val blocker = File(tmp.root, "blocker")
        blocker.writeText("x")
        val store = FirstRunDoneStore(File(blocker, FirstRunDoneStore.FILE_NAME))
        assertFalse("app: a failed write must answer false", store.markDone())
        assertFalse("app: a failed write must leave no marker", store.isDone())
    }

    @Test
    fun `a good write leaves no temporary file behind`() {
        assertTrue(FirstRunDoneStore(marker()).markDone())
        assertEquals("app: only the marker may remain in its folder", listOf(FirstRunDoneStore.FILE_NAME), names(tmp.root))
    }

    @Test
    fun `a failed write leaves no temporary file behind`() {
        val blocking = marker()
        assertTrue(blocking.mkdir())
        File(blocking, "occupant").writeText("x")
        val store = FirstRunDoneStore(marker())
        assertFalse("app: a write onto a folder must answer false", store.markDone())
        assertFalse("app: a folder at the marker path is not a marker", store.isDone())
        assertEquals("app: only the blocking folder may remain in its folder", listOf(FirstRunDoneStore.FILE_NAME), names(tmp.root))
    }

    @Test
    fun `decide gives the first run when nothing is done and no model is ready`() {
        assertEquals(FirstRunGate.Decision.FIRST_RUN, FirstRunGate.decide(done = false, modelReady = false))
    }

    @Test
    fun `decide marks done quietly when the model is ready and the marker is missing`() {
        assertEquals(FirstRunGate.Decision.MARK_DONE_QUIETLY, FirstRunGate.decide(done = false, modelReady = true))
    }

    @Test
    fun `decide gives the normal app when the marker is present and no model is ready`() {
        assertEquals(FirstRunGate.Decision.NORMAL, FirstRunGate.decide(done = true, modelReady = false))
    }

    @Test
    fun `decide gives the normal app when the marker and the model are both present`() {
        assertEquals(FirstRunGate.Decision.NORMAL, FirstRunGate.decide(done = true, modelReady = true))
    }
}
