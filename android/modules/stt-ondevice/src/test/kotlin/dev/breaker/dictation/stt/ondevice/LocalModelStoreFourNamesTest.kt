package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * When the unpacked files of a model count as present: all four file names of its
 * profile, each a regular file with at least one byte. A half-removed earlier unpack
 * can leave only some of them, and the loader must not accept that.
 *
 * The names come from [TarFixtures], written out by hand, so these tests do not
 * trust the profile table they check against.
 */
class LocalModelStoreFourNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Each model with the four file names its profile writes. */
    private val models = listOf(
        "tiny" to TarFixtures.TINY_FILES,
        "small" to TarFixtures.SMALL_FILES,
    )

    /** Writes [names] (each with content) into the unpacked directory of [id] and returns that directory. */
    private fun seedNames(store: LocalModelStore, id: String, names: List<String>): File {
        val dir = store.extractedDirectory(id)
        for (name in names) Fixtures.writeBytes(File(dir, name), name.toByteArray())
        return dir
    }

    @Test
    fun `isExtracted is true for all four names of each model and extra files do not matter`() {
        for ((id, names) in models) {
            val store = LocalModelStore(tmp.newFolder())
            val dir = seedNames(store, id, names)
            assertEquals("$id: fixture: exactly the four names", names.sorted(), dir.list()!!.sorted())
            assertTrue("$id: all four names", store.isExtracted(id))

            Fixtures.writeBytes(File(dir, "README.md"), "extra".toByteArray())
            Fixtures.writeBytes(File(dir, "test_wavs/0.wav"), byteArrayOf(1))
            assertTrue("$id: all four names and extra files", store.isExtracted(id))
        }
    }

    @Test
    fun `isExtracted is false when any one of the four names is missing`() {
        var checked = 0
        for ((id, names) in models) {
            val store = LocalModelStore(tmp.newFolder())
            val dir = seedNames(store, id, names)
            assertTrue("$id: control with all four", store.isExtracted(id))
            for (missing in names) {
                val file = File(dir, missing)
                val saved = file.readBytes()
                assertTrue("$id: fixture: removing $missing", file.delete())
                assertEquals("$id: fixture: three of four left", names.size - 1, dir.list()!!.size)
                assertFalse("$id: without $missing", store.isExtracted(id))
                Fixtures.writeBytes(file, saved)
                assertTrue("$id: with $missing back", store.isExtracted(id))
                checked++
            }
        }
        assertEquals("every name of both models was left out once", 8, checked)
    }

    @Test
    fun `isExtracted is false when one of the four files is empty`() {
        var checked = 0
        for ((id, names) in models) {
            val store = LocalModelStore(tmp.newFolder())
            val dir = seedNames(store, id, names)
            for (emptied in names) {
                val file = File(dir, emptied)
                Fixtures.writeBytes(file, ByteArray(0))
                assertEquals("$id: fixture: $emptied is empty", 0L, file.length())
                assertFalse("$id: $emptied empty", store.isExtracted(id))
                Fixtures.writeBytes(file, emptied.toByteArray())
                assertTrue("$id: $emptied with content", store.isExtracted(id))
                checked++
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun `isExtracted is false when a directory stands in place of one of the four files`() {
        var checked = 0
        for ((id, names) in models) {
            val store = LocalModelStore(tmp.newFolder())
            val dir = seedNames(store, id, names)
            for (replaced in names) {
                val file = File(dir, replaced)
                assertTrue("$id: fixture: removing $replaced", file.delete())
                // The directory is not empty, so its reported size is not zero on any file system.
                Fixtures.writeBytes(File(file, "inside.bin"), byteArrayOf(1, 2, 3))
                assertTrue("$id: fixture: $replaced is a directory", file.isDirectory)
                assertFalse("$id: a directory named $replaced", store.isExtracted(id))
                assertTrue("$id: fixture: clearing $replaced", file.deleteRecursively())
                Fixtures.writeBytes(file, replaced.toByteArray())
                assertTrue("$id: $replaced as a file", store.isExtracted(id))
                checked++
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun `isExtracted is false for a model with no profile even when every known name is there`() {
        val everyName = (TarFixtures.TINY_FILES + TarFixtures.SMALL_FILES).distinct()
        for (id in listOf("base", "tiny-2", "Tiny")) {
            val store = LocalModelStore(tmp.newFolder())
            val dir = seedNames(store, id, everyName)
            assertTrue("$id: fixture: a files directory with content", dir.list()!!.isNotEmpty())
            assertFalse("$id: no profile", store.isExtracted(id))
        }
    }

    @Test
    fun `isExtracted does not accept the file names of the other model`() {
        // The two models differ in the joiner name only, so each one's names are three right and one wrong for the other.
        assertNotEquals(TarFixtures.TINY_FILES, TarFixtures.SMALL_FILES)
        assertEquals(
            "fixture: the joiner is the one name that differs",
            1,
            TarFixtures.TINY_FILES.count { it !in TarFixtures.SMALL_FILES },
        )

        val smallWithTinyNames = LocalModelStore(tmp.newFolder())
        seedNames(smallWithTinyNames, "small", TarFixtures.TINY_FILES)
        assertFalse("small holding the names of tiny", smallWithTinyNames.isExtracted("small"))

        val tinyWithSmallNames = LocalModelStore(tmp.newFolder())
        seedNames(tinyWithSmallNames, "tiny", TarFixtures.SMALL_FILES)
        assertFalse("tiny holding the names of small", tinyWithSmallNames.isExtracted("tiny"))

        val both = LocalModelStore(tmp.newFolder())
        seedNames(both, "tiny", TarFixtures.TINY_FILES)
        seedNames(both, "small", TarFixtures.SMALL_FILES)
        assertTrue("control: each holding its own names", both.isExtracted("tiny") && both.isExtracted("small"))
    }

    @Test
    fun `isExtracted is false for a plain file in place of the directory and for an absent directory`() {
        val store = LocalModelStore(tmp.newFolder())
        store.directoryFor("tiny").mkdirs()
        assertFalse("no files directory", store.isExtracted("tiny"))
        store.extractedDirectory("tiny").writeText("a plain file where the directory goes")
        assertFalse("a plain file", store.isExtracted("tiny"))
        assertTrue(store.extractedDirectory("tiny").delete())
        seedNames(store, "tiny", TarFixtures.TINY_FILES)
        assertTrue("control: the four names", store.isExtracted("tiny"))
    }

    @Test
    fun `a partly removed unpack is not extracted and a removal of what is left leaves nothing`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = seedNames(store, "tiny", TarFixtures.TINY_FILES)
        assertTrue(File(dir, TarFixtures.TINY_FILES[0]).delete())
        assertTrue(File(dir, TarFixtures.TINY_FILES[1]).delete())
        assertEquals("fixture: two of the four are left", 2, dir.list()!!.size)
        assertFalse(store.isExtracted("tiny"))

        assertTrue("removing what is left", store.removeExtracted("tiny"))
        assertFalse(dir.exists())
        assertFalse(store.isExtracted("tiny"))
    }
}
