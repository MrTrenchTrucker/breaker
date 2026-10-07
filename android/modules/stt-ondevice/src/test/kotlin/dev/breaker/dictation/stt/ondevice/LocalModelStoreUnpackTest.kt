package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store's part of the unpack step: where the unpacked files live, when
 * they count as present, where the work directory is, and how they are removed
 * on their own or together with the rest of the model.
 */
class LocalModelStoreUnpackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val unsafeIds = listOf("../evil", "", ".hidden", "a/b", "a b", "a\\b")

    /** A removal that records what it was asked to remove and answers [answer]. */
    private class RecordingRemoval(private val answer: Boolean?) : (File) -> Boolean {
        val asked = ArrayList<File>()
        override fun invoke(target: File): Boolean {
            asked.add(target)
            return answer ?: target.deleteRecursively()
        }
    }

    private fun storeWith(removal: RecordingRemoval? = null) =
        if (removal == null) LocalModelStore(tmp.newFolder()) else LocalModelStore(tmp.newFolder(), remove = removal)

    /** A model directory with an archive, both markers and the given unpacked file names. */
    private fun installedWithFiles(store: LocalModelStore, vararg names: String): File {
        val dir = store.directoryFor("tiny")
        Fixtures.writeBytes(File(dir, LocalModelStore.ARCHIVE_NAME), "archive".toByteArray())
        store.markVerified("tiny", "digest")
        store.storeChecksums("tiny", "model.archive\tdigest")
        for (name in names) Fixtures.writeBytes(File(store.extractedDirectory("tiny"), name), name.toByteArray())
        return dir
    }

    @Test
    fun `the unpacked directory and the work suffix have the stated names`() {
        assertEquals("files", LocalModelStore.EXTRACTED_DIR)
        assertEquals(".extracting", LocalModelStore.EXTRACTING_SUFFIX)
    }

    @Test
    fun `the extracted directory is the files directory inside the model directory`() {
        val root = tmp.newFolder()
        val store = LocalModelStore(root)
        assertEquals(File(File(root, "tiny"), "files"), store.extractedDirectory("tiny"))
        assertEquals(store.directoryFor("tiny"), store.extractedDirectory("tiny").parentFile)
        assertEquals("a different id gives a different directory", File(File(root, "small"), "files"), store.extractedDirectory("small"))
    }

    @Test
    fun `the paths for an unsafe id are refused like the model directory`() {
        val store = LocalModelStore(tmp.newFolder())
        var refused = 0
        for (id in unsafeIds) {
            for (ask in listOf<(String) -> File>(store::directoryFor, store::extractedDirectory, store::extractionWorkDirectory)) {
                try {
                    ask(id)
                    fail("Expected IllegalArgumentException for id: '$id'")
                } catch (e: IllegalArgumentException) {
                    refused++
                }
            }
        }
        assertEquals(unsafeIds.size * 3, refused)
    }

    @Test
    fun `isExtracted is true only for a directory with at least one entry`() {
        val store = LocalModelStore(tmp.newFolder())
        assertFalse("no model directory at all", store.isExtracted("tiny"))

        val files = store.extractedDirectory("tiny")
        store.directoryFor("tiny").mkdirs()
        assertFalse("no files directory", store.isExtracted("tiny"))

        files.mkdirs()
        assertFalse("an empty files directory", store.isExtracted("tiny"))

        files.delete()
        files.writeText("a plain file where the directory goes")
        assertFalse("a plain file at the path", store.isExtracted("tiny"))

        files.delete()
        Fixtures.writeBytes(File(files, "tokens.txt"), "t".toByteArray())
        assertTrue("a directory holding a file", store.isExtracted("tiny"))
    }

    @Test
    fun `isExtracted answers false for an unsafe id instead of throwing`() {
        val store = LocalModelStore(tmp.newFolder())
        for (id in unsafeIds) {
            val answer = try {
                store.isExtracted(id)
            } catch (e: IllegalArgumentException) {
                throw AssertionError("isExtracted threw for id '$id'", e)
            }
            assertFalse("id '$id'", answer)
        }
    }

    @Test
    fun `an archive alone is installed but not extracted and files alone are extracted but not installed`() {
        val store = LocalModelStore(tmp.newFolder())
        Fixtures.writeBytes(store.archiveFile("tiny"), "archive".toByteArray())
        assertTrue(store.isInstalled("tiny"))
        assertFalse(store.isExtracted("tiny"))
        assertEquals(listOf("tiny"), store.installedModelIds())

        val other = LocalModelStore(tmp.newFolder())
        Fixtures.writeBytes(File(other.extractedDirectory("tiny"), "tokens.txt"), "t".toByteArray())
        assertTrue(other.isExtracted("tiny"))
        assertFalse(other.isInstalled("tiny"))
        assertEquals(emptyList<String>(), other.installedModelIds())
    }

    @Test
    fun `the work directory is named by the id inside the staging directory`() {
        val store = LocalModelStore(tmp.newFolder())
        val work = store.extractionWorkDirectory("tiny")
        assertEquals(store.stagingDirectory(), work.parentFile)
        assertEquals("tiny.extracting", work.name)
        assertEquals("another id has its own work directory", "small.extracting", store.extractionWorkDirectory("small").name)
        assertFalse("not inside the model directory", work.path.startsWith(store.directoryFor("tiny").path + File.separator))
    }

    @Test
    fun `a work directory can be moved into the extracted place by one rename`() {
        val store = LocalModelStore(tmp.newFolder())
        val work = store.extractionWorkDirectory("tiny")
        Fixtures.writeBytes(File(work, "tokens.txt"), "t".toByteArray())
        store.directoryFor("tiny").mkdirs()
        assertTrue("the rename must work on this layout", work.renameTo(store.extractedDirectory("tiny")))
        assertTrue(store.isExtracted("tiny"))
        assertFalse(work.exists())
    }

    @Test
    fun `removeExtracted removes the files and keeps the archive and the markers`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = installedWithFiles(store, "tokens.txt", "encoder.onnx")
        assertTrue(store.isExtracted("tiny"))

        assertTrue(store.removeExtracted("tiny"))
        assertFalse(store.isExtracted("tiny"))
        assertFalse(store.extractedDirectory("tiny").exists())
        assertTrue("the archive stays", File(dir, LocalModelStore.ARCHIVE_NAME).isFile)
        assertEquals("digest", store.lastVerifiedDigest("tiny"))
        assertEquals("model.archive\tdigest", store.storedChecksums("tiny"))
        assertTrue(store.isInstalled("tiny"))
    }

    @Test
    fun `removeExtracted does not ask the remove function when there is nothing to remove`() {
        val removal = RecordingRemoval(answer = false)
        val store = storeWith(removal)
        assertTrue("no model directory", store.removeExtracted("tiny"))
        installedWithFiles(store)
        assertTrue("a model directory without files", store.removeExtracted("tiny"))
        for (id in unsafeIds) {
            val answer = try {
                store.removeExtracted(id)
            } catch (e: IllegalArgumentException) {
                throw AssertionError("removeExtracted threw for id '$id'", e)
            }
            assertTrue("unsafe id '$id' has nothing to remove", answer)
        }
        assertTrue("remove was asked for ${removal.asked}", removal.asked.isEmpty())
    }

    @Test
    fun `removeExtracted asks for the files directory and reports a failed removal`() {
        val removal = RecordingRemoval(answer = false)
        val store = storeWith(removal)
        installedWithFiles(store, "tokens.txt")

        assertFalse("a failed removal must be reported", store.removeExtracted("tiny"))
        assertEquals(listOf(store.extractedDirectory("tiny")), removal.asked)
        assertTrue("the files are still there", store.isExtracted("tiny"))
    }

    @Test
    fun `delete removes the archive and the unpacked files together with one request`() {
        val removal = RecordingRemoval(answer = null)
        val store = storeWith(removal)
        val dir = installedWithFiles(store, "tokens.txt")

        assertTrue(store.delete("tiny"))
        assertEquals("one request, for the model directory", listOf(dir), removal.asked)
        assertFalse(dir.exists())
        assertFalse(store.isExtracted("tiny"))
        assertFalse(store.isInstalled("tiny"))
    }

    @Test
    fun `modelFiles does not list the unpacked directory`() {
        val store = LocalModelStore(tmp.newFolder())
        installedWithFiles(store, "tokens.txt")
        assertEquals(listOf(LocalModelStore.ARCHIVE_NAME), store.modelFiles("tiny").keys.sorted())
    }
}
