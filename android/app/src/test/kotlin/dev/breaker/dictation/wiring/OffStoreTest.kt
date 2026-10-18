package dev.breaker.dictation.wiring

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Protects the file that remembers the user's off choice: present means off, a second store over
 * the same file sees it (a new process), and a write goes through a temporary file that is renamed
 * into place, so the real file is never half written.
 */
internal class OffStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun target(): File = File(tmp.root, "dictation-off")

    private fun names(folder: File): List<String> = (folder.list() ?: emptyArray()).sorted()

    @Test
    fun `a store over no file is not off`() {
        assertFalse("app: with no file the user has not switched off", FileOffStore(target()).isOff())
    }

    @Test
    fun `switching off is read back by a new store over the same file`() {
        FileOffStore(target()).setOff(true)
        assertTrue("app: the file must exist after switching off", target().isFile)
        assertTrue("app: a new store (a new process) must read off", FileOffStore(target()).isOff())
    }

    @Test
    fun `switching on removes the file and a new store reads not off`() {
        val store = FileOffStore(target())
        store.setOff(true)
        store.setOff(false)
        assertFalse("app: the file must be gone after switching on", target().exists())
        assertFalse("app: a new store must read not off", FileOffStore(target()).isOff())
    }

    @Test
    fun `switching on with nothing stored does nothing and does not throw`() {
        FileOffStore(target()).setOff(false)
        assertEquals("app: clearing nothing must leave the folder empty", emptyList<String>(), names(tmp.root))
    }

    @Test
    fun `switching off twice keeps one file and stays off`() {
        val store = FileOffStore(target())
        store.setOff(true)
        store.setOff(true)
        assertEquals("app: two writes must leave the one file", listOf("dictation-off"), names(tmp.root))
        assertTrue("app: it must still read off", store.isOff())
    }

    @Test
    fun `the folder is created when it is missing`() {
        val deep = File(tmp.root, "a/b/dictation-off")
        FileOffStore(deep).setOff(true)
        assertTrue("app: the file must be written inside the new folder", FileOffStore(deep).isOff())
    }

    @Test
    fun `a path under a plain file is not off and does not throw`() {
        val plain = File(tmp.root, "plain")
        plain.writeText("x")
        assertFalse("app: an unreadable path must answer not off", FileOffStore(File(plain, "dictation-off")).isOff())
    }

    @Test
    fun `a folder at the path is not off`() {
        target().mkdirs()
        assertFalse("app: only a file means off", FileOffStore(target()).isOff())
    }

    @Test
    fun `the mark is written to a temporary file and only the rename puts it in place`() {
        val seen: MutableList<String> = ArrayList()
        val store = FileOffStore(target()) { temp ->
            seen.add("before-move temp=${temp.name} tempExists=${temp.isFile} finalExists=${target().exists()} folder=${names(tmp.root)}")
        }
        store.setOff(true)
        assertEquals(
            "app: at the moment before the rename the data must be in the temporary file beside the target and the target absent",
            listOf("before-move temp=dictation-off.tmp tempExists=true finalExists=false folder=[dictation-off.tmp]"),
            seen,
        )
        assertEquals("app: after the rename only the target is left", listOf("dictation-off"), names(tmp.root))
        assertTrue("app: the target holds the mark", target().length() > 0L)
    }

    @Test
    fun `a write that fails before the rename leaves no target and no temporary file`() {
        val store = FileOffStore(target()) { throw IOException("disk full") }
        assertThrows("app: a failed write must raise an IOException", IOException::class.java) { store.setOff(true) }
        assertEquals("app: a failed write must leave the folder as it was", emptyList<String>(), names(tmp.root))
        assertFalse("app: a failed write must not read as off", store.isOff())
    }

    @Test
    fun `a write that fails over an existing mark keeps the old mark whole`() {
        FileOffStore(target()).setOff(true)
        val before = target().readText()
        val store = FileOffStore(target()) { throw IOException("disk full") }
        assertThrows("app: a failed write must raise an IOException", IOException::class.java) { store.setOff(true) }
        assertEquals("app: the old mark must be unchanged", before, target().readText())
        assertEquals("app: no temporary file may be left", listOf("dictation-off"), names(tmp.root))
        assertTrue("app: the user's off must still hold", store.isOff())
    }

    @Test
    fun `a rename that the file system refuses raises an IOException and removes the temporary file`() {
        val occupied = target()
        File(occupied, "inside").apply { parentFile.mkdirs() }.writeText("x")
        assertThrows("app: a refused rename must raise an IOException", IOException::class.java) { FileOffStore(occupied).setOff(true) }
        assertEquals("app: the refused write must leave only what was there", listOf("dictation-off"), names(tmp.root))
        assertTrue("app: what was at the target must be untouched", File(occupied, "inside").isFile)
    }

    @Test
    fun `clearing a mark that cannot be deleted raises an IOException`() {
        val occupied = target()
        File(occupied, "inside").apply { parentFile.mkdirs() }.writeText("x")
        assertThrows("app: a refused delete must raise an IOException", IOException::class.java) { FileOffStore(occupied).setOff(false) }
    }
}
