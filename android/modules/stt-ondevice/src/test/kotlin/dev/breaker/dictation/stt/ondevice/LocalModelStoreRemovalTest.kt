package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the store reports when the removal it performs does not work: a delete
 * that fails must come back as false, with the files still where they were.
 */
class LocalModelStoreRemovalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A removal that records what it was asked to remove and always fails. */
    private class FailingRemoval : (File) -> Boolean {
        val asked = ArrayList<File>()
        override fun invoke(target: File): Boolean {
            asked.add(target)
            return false
        }
    }

    private fun installModel(store: LocalModelStore, id: String): File {
        val dir = store.directoryFor(id)
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("model bytes".toByteArray())
        assertTrue("fixture: the model must be installed", store.isInstalled(id))
        return dir
    }

    @Test
    fun `delete returns false when the removal fails`() {
        val removal = FailingRemoval()
        val store = LocalModelStore(tmp.newFolder(), remove = removal)
        val dir = installModel(store, "tiny")

        assertFalse("a failed removal must be reported", store.delete("tiny"))
        assertEquals("the removal must be asked for the model directory", listOf(dir), removal.asked)
        assertTrue("the directory must still be there", dir.isDirectory)
        assertTrue("the archive must still be there", File(dir, LocalModelStore.ARCHIVE_NAME).isFile)

        // Control: the same fixture with the default removal deletes and says so.
        val plain = LocalModelStore(tmp.newFolder())
        val plainDir = installModel(plain, "tiny")
        assertTrue(plain.delete("tiny"))
        assertFalse(plainDir.exists())
    }

    @Test
    fun `discardFailedDownload returns false when the removal fails`() {
        val removal = FailingRemoval()
        val store = LocalModelStore(tmp.newFolder(), discard = removal)
        val file = Fixtures.writeBytes(File(tmp.newFolder(), "partial.bin"), "data".toByteArray())

        assertFalse("a failed removal must be reported", store.discardFailedDownload(file))
        assertEquals("the removal must be asked for the file", listOf(file), removal.asked)
        assertTrue("the file must still be there", file.isFile)

        // Control: the same fixture with the default removal discards and says so.
        val plain = LocalModelStore(tmp.newFolder())
        val other = Fixtures.writeBytes(File(tmp.newFolder(), "partial.bin"), "data".toByteArray())
        assertTrue(plain.discardFailedDownload(other))
        assertFalse(other.exists())
    }

    @Test
    fun `discardFailedDownload returns true for a missing file without asking the removal`() {
        val removal = FailingRemoval()
        val store = LocalModelStore(tmp.newFolder(), discard = removal)
        val missing = File(tmp.newFolder(), "never-written.bin")

        assertTrue(store.discardFailedDownload(missing))
        assertTrue("nothing exists, so nothing is removed", removal.asked.isEmpty())

        // Control: the same removal is asked once the file exists.
        Fixtures.writeBytes(missing, "data".toByteArray())
        assertFalse(store.discardFailedDownload(missing))
        assertEquals(listOf(missing), removal.asked)
    }

    @Test
    fun `discardFailedDownload returns false when the removal throws`() {
        val store = LocalModelStore(tmp.newFolder(), discard = { throw SecurityException("not allowed") })
        val file = Fixtures.writeBytes(File(tmp.newFolder(), "partial.bin"), "data".toByteArray())

        assertFalse("a throwing removal must be reported, not escape", store.discardFailedDownload(file))
        assertTrue("the file must still be there", file.isFile)
    }

    @Test
    fun `discardFailedDownload does not remove a non-empty directory`() {
        val store = LocalModelStore(tmp.newFolder())
        val directory = tmp.newFolder("handed-in")
        val inner = Fixtures.writeBytes(File(directory, "inner.bin"), "data".toByteArray())

        assertFalse("a directory with content must not be wiped", store.discardFailedDownload(directory))
        assertTrue("the directory must still be there", directory.isDirectory)
        assertTrue("its content must still be there", inner.isFile)

        // Control: an empty directory is removed (a plain file delete), so the call does try to delete.
        val empty = tmp.newFolder("handed-in-empty")
        assertTrue(store.discardFailedDownload(empty))
        assertFalse(empty.exists())
    }
}
