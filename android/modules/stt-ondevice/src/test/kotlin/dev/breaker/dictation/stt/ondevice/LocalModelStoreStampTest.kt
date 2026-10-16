package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The store members that read, write and delete the unpacked-file stamp, and the checks that must not change for it. */
class LocalModelStoreStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val unsafeIds = listOf("../evil", "", ".hidden", "a/b", "a b", "a\\b")

    /** The four file names the profile of "tiny" opens. */
    private val tinyNames = ExtractionProfiles.forModel("tiny")!!.files

    /** A 64-character lower case digest made of one repeated hex letter. */
    private fun hex(letter: Char): String = letter.toString().repeat(64)

    /** Writes the four profile files of "tiny" into the files folder of [store] and returns that folder. */
    private fun unpacked(store: LocalModelStore): File {
        for (name in tinyNames) Fixtures.writeText(File(store.extractedDirectory("tiny"), name), "t")
        return store.extractedDirectory("tiny")
    }

    /** A stamp for the four files in [dir], with their real sizes and modified times. */
    private fun stampOf(dir: File): UnpackedStamp = UnpackedStamp(
        hex('a'),
        tinyNames.map { name ->
            val file = File(dir, name)
            StampedFile(name, file.length(), file.lastModified(), hex('b'))
        },
    )

    private fun differentStampOf(dir: File): UnpackedStamp = UnpackedStamp(
        hex('c'),
        tinyNames.map { name ->
            val file = File(dir, name)
            StampedFile(name, file.length() + 1, file.lastModified(), hex('d'))
        },
    )

    @Test
    fun `a written stamp is read back`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        val written = stampOf(dir)
        store.writeStamp("tiny", written)
        assertTrue("the stamp file is in the files folder", File(dir, UnpackedStamp.FILE_NAME).isFile)
        assertEquals(written.encode(), store.readStamp("tiny")?.encode())
    }

    @Test
    fun `a failed move leaves the old stamp intact, says so, and leaves no temporary file`() {
        val root = tmp.newFolder()
        val dir = unpacked(LocalModelStore(root))
        val written = stampOf(dir)
        LocalModelStore(root).writeStamp("tiny", written)
        val before = File(dir, UnpackedStamp.FILE_NAME).readBytes()
        val store = LocalModelStore(root, beforeMove = { throw IOException("forced") })
        try {
            store.writeStamp("tiny", differentStampOf(dir))
            fail("a forced move failure must reach the caller")
        } catch (e: IOException) {
            // the failure reaches the caller
        }
        val after = File(dir, UnpackedStamp.FILE_NAME).takeIf { it.isFile }
        assertNotNull("the stamp file is still there after a failed move", after)
        assertArrayEquals("the old stamp is byte-for-byte intact", before, after!!.readBytes())
        assertNull("no temporary file is left behind", dir.listFiles()?.firstOrNull { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a failed write of a new stamp leaves no stamp and no temporary file`() {
        val root = tmp.newFolder()
        val store = LocalModelStore(root, beforeMove = { throw IOException("forced") })
        val dir = unpacked(store)
        try {
            store.writeStamp("tiny", stampOf(dir))
            fail("a forced move failure must reach the caller")
        } catch (e: IOException) {
            // the failure reaches the caller
        }
        assertFalse("no stamp is created", File(dir, UnpackedStamp.FILE_NAME).exists())
        assertNull("no temporary file is left behind", dir.listFiles()?.firstOrNull { it.name.endsWith(".tmp") })
    }

    @Test
    fun `no stamp reads as null`() {
        val store = LocalModelStore(tmp.newFolder())
        assertNull("no model folder at all", store.readStamp("tiny"))
        unpacked(store)
        assertNull("the files without a stamp", store.readStamp("tiny"))
    }

    @Test
    fun `an unsafe model id reads and writes nothing`() {
        val root = tmp.newFolder()
        val store = LocalModelStore(root)
        val stamp = UnpackedStamp(hex('a'), listOf(StampedFile(tinyNames[0], 1L, 1L, hex('b'))))
        for (id in unsafeIds) {
            assertNull("read for id '$id'", store.readStamp(id))
            store.writeStamp(id, stamp)
        }
        assertTrue("nothing was written under the models root", root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `the stamp file does not change what isExtracted says`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        assertTrue("the four files without a stamp", store.isExtracted("tiny"))
        store.writeStamp("tiny", stampOf(dir))
        assertTrue("the four files with the stamp", store.isExtracted("tiny"))
        assertTrue(File(dir, tinyNames[1]).delete())
        assertFalse("the stamp with one profile file gone", store.isExtracted("tiny"))
    }

    @Test
    fun `removeExtracted removes the stamp with the folder`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        store.writeStamp("tiny", stampOf(dir))
        assertTrue(store.removeExtracted("tiny"))
        assertFalse("the folder is gone", dir.exists())
        assertNull("no stamp is left", store.readStamp("tiny"))
    }

    @Test
    fun `deleteUnpackedFile deletes one profile file and leaves the others and the stamp`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        store.writeStamp("tiny", stampOf(dir))
        assertTrue(store.deleteUnpackedFile("tiny", tinyNames[1]))
        assertFalse("the named file is gone", File(dir, tinyNames[1]).exists())
        for (name in tinyNames.filter { it != tinyNames[1] }) {
            assertTrue("$name stays", File(dir, name).isFile)
        }
        assertTrue("the stamp stays", File(dir, UnpackedStamp.FILE_NAME).isFile)
    }

    @Test
    fun `deleteUnpackedFile refuses a name that is not a profile file of the model`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        store.writeStamp("tiny", stampOf(dir))
        for (name in listOf(UnpackedStamp.FILE_NAME, "../tokens.txt", "..", "encoder.onnx")) {
            assertFalse("refused for '$name'", store.deleteUnpackedFile("tiny", name))
        }
        for (name in tinyNames + UnpackedStamp.FILE_NAME) {
            assertTrue("$name is still there", File(dir, name).isFile)
        }
        assertTrue("control: a profile file name is deleted", store.deleteUnpackedFile("tiny", tinyNames[0]))
    }

    @Test
    fun `deleteUnpackedFile reports false when the delete fails and true when the file is already gone`() {
        val stuck = LocalModelStore(tmp.newFolder(), discard = { false })
        val stuckDir = unpacked(stuck)
        assertFalse("the delete failed", stuck.deleteUnpackedFile("tiny", tinyNames[0]))
        assertTrue("the file is still there", File(stuckDir, tinyNames[0]).exists())

        val clean = LocalModelStore(tmp.newFolder(), discard = { it.delete() })
        val cleanDir = unpacked(clean)
        assertTrue(File(cleanDir, tinyNames[0]).delete())
        assertTrue("a file that is already gone counts as removed", clean.deleteUnpackedFile("tiny", tinyNames[0]))
    }

    @Test
    fun `modelFiles and installedModelIds are not changed by the stamp`() {
        val store = LocalModelStore(tmp.newFolder())
        val dir = unpacked(store)
        Fixtures.writeBytes(store.archiveFile("tiny"), "archive".toByteArray())
        store.markVerified("tiny", hex('a'))
        val listedBefore = store.modelFiles("tiny").keys.sorted()
        val idsBefore = store.installedModelIds()
        store.writeStamp("tiny", stampOf(dir))
        assertEquals(listedBefore, store.modelFiles("tiny").keys.sorted())
        assertEquals(idsBefore, store.installedModelIds())
        assertEquals("the model folder lists the archive only", listOf(LocalModelStore.ARCHIVE_NAME), listedBefore)
        assertEquals(listOf("tiny"), idsBefore)
    }
}
