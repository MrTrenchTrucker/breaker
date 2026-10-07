package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.root)
    }

    // --- isSafeName ---

    @Test
    fun `isSafeName accepts simple alphanumeric name`() {
        assertTrue(LocalModelStore.isSafeName("model1"))
    }

    @Test
    fun `isSafeName accepts name with dots dashes and underscores`() {
        assertTrue(LocalModelStore.isSafeName("my-model_v2.0"))
    }

    @Test
    fun `isSafeName rejects empty string`() {
        assertFalse(LocalModelStore.isSafeName(""))
    }

    @Test
    fun `isSafeName rejects dot dot`() {
        assertFalse(LocalModelStore.isSafeName(".."))
    }

    @Test
    fun `isSafeName rejects name starting with dot`() {
        assertFalse(LocalModelStore.isSafeName(".hidden"))
    }

    @Test
    fun `isSafeName rejects name with forward slash`() {
        assertFalse(LocalModelStore.isSafeName("a/b"))
    }

    @Test
    fun `isSafeName rejects name with backslash`() {
        assertFalse(LocalModelStore.isSafeName("a\\b"))
    }

    @Test
    fun `isSafeName rejects name longer than 64 characters`() {
        assertFalse(LocalModelStore.isSafeName("a".repeat(65)))
    }

    @Test
    fun `isSafeName accepts name of exactly 64 characters`() {
        assertTrue(LocalModelStore.isSafeName("a".repeat(64)))
    }

    // --- directoryFor ---

    @Test
    fun `directoryFor returns correct path for safe id`() {
        val dir = store.directoryFor("model1")
        assertEquals(File(tmp.root, "model1"), dir)
    }

    @Test
    fun `directoryFor throws for unsafe id`() {
        val unsafeIds = listOf("..", "a/b", ".hidden", "", "a".repeat(65))
        for (id in unsafeIds) {
            try {
                store.directoryFor(id)
                fail("Expected IllegalArgumentException for id: '$id'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- isInstalled ---

    @Test
    fun `isInstalled returns false for unsafe id`() {
        assertFalse(store.isInstalled(".."))
        assertFalse(store.isInstalled("a/b"))
        assertFalse(store.isInstalled(".hidden"))
        assertFalse(store.isInstalled(""))
        assertFalse(store.isInstalled("a".repeat(65)))
    }

    @Test
    fun `isInstalled returns false when directory does not exist`() {
        assertFalse(store.isInstalled("model1"))
    }

    @Test
    fun `isInstalled returns false when archive does not exist`() {
        store.directoryFor("model1").mkdirs()
        assertFalse(store.isInstalled("model1"))
    }

    @Test
    fun `isInstalled returns false when archive is empty`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(ByteArray(0))
        assertFalse(store.isInstalled("model1"))
    }

    @Test
    fun `isInstalled returns false when the archive path is a directory`() {
        // A directory holding a file: on common filesystems it reports a
        // non-zero length, so a length check alone would accept it.
        val archive = store.archiveFile("model1")
        archive.mkdirs()
        File(archive, "occupied").writeText("data")
        assertTrue("fixture: the archive path must be a directory", archive.isDirectory)
        assertTrue("fixture: the archive path must exist", archive.exists())
        // Where a directory reports length 0 the length check alone already
        // rejects it, so this test cannot tell the two checks apart there.
        assumeTrue(archive.length() > 0L)

        // Control: same layout under another id, with a regular non-empty file.
        val other = store.archiveFile("model2")
        other.parentFile.mkdirs()
        other.writeBytes("data".toByteArray())
        assertTrue(store.isInstalled("model2"))

        assertFalse(
            "a directory at the archive path is not an installed model",
            store.isInstalled("model1"),
        )
    }

    @Test
    fun `isInstalled returns true when directory and non-empty archive exist`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        assertTrue(store.isInstalled("model1"))
    }

    // --- archiveFile ---

    @Test
    fun `archiveFile returns correct path`() {
        val archive = store.archiveFile("model1")
        assertEquals(File(tmp.root, "model1/model.archive"), archive)
    }

    // --- modelFiles ---

    @Test
    fun `modelFiles returns empty map for unsafe id`() {
        assertTrue(store.modelFiles("..").isEmpty())
        assertTrue(store.modelFiles("a/b").isEmpty())
        assertTrue(store.modelFiles(".hidden").isEmpty())
        assertTrue(store.modelFiles("").isEmpty())
        assertTrue(store.modelFiles("a".repeat(65)).isEmpty())
    }

    @Test
    fun `modelFiles returns empty map when directory does not exist`() {
        assertTrue(store.modelFiles("model1").isEmpty())
    }

    @Test
    fun `modelFiles excludes marker files`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        File(dir, LocalModelStore.VERIFIED_FILE).writeText("digest")
        File(dir, LocalModelStore.CHECKSUMS_FILE).writeText("checksums")
        val files = store.modelFiles("model1")
        assertEquals(1, files.size)
        assertTrue(files.containsKey(LocalModelStore.ARCHIVE_NAME))
    }

    @Test
    fun `modelFiles returns all non-marker files`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        File(dir, "extra.txt").writeText("extra")
        val files = store.modelFiles("model1")
        assertEquals(2, files.size)
    }

    // --- installedModelIds ---

    @Test
    fun `installedModelIds returns empty list when root does not exist`() {
        val badStore = LocalModelStore(File(tmp.root, "nonexistent"))
        assertTrue(badStore.installedModelIds().isEmpty())
    }

    @Test
    fun `installedModelIds returns sorted installed ids`() {
        for (id in listOf("charlie", "alpha", "bravo")) {
            val dir = store.directoryFor(id)
            dir.mkdirs()
            File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        }
        File(store.directoryFor("bravo"), LocalModelStore.ARCHIVE_NAME).writeBytes(ByteArray(0))
        val ids = store.installedModelIds()
        assertEquals(listOf("alpha", "charlie"), ids)
    }

    @Test
    fun `installedModelIds excludes staging directory`() {
        val staging = store.stagingDirectory()
        staging.mkdirs()
        File(staging, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        assertTrue(store.installedModelIds().isEmpty())
    }

    @Test
    fun `installedModelIds excludes unsafe directory names`() {
        val hiddenDir = File(tmp.root, ".hidden")
        hiddenDir.mkdirs()
        File(hiddenDir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        assertTrue(store.installedModelIds().isEmpty())
    }

    // --- markVerified / lastVerifiedDigest ---

    @Test
    fun `markVerified creates directory and writes digest`() {
        store.markVerified("model1", Fixtures.VALID_PIN)
        val marker = File(store.directoryFor("model1"), LocalModelStore.VERIFIED_FILE)
        assertTrue(marker.isFile)
        assertEquals(Fixtures.VALID_PIN, marker.readText())
    }

    @Test
    fun `markVerified returns quietly for unsafe id`() {
        store.markVerified("..", Fixtures.VALID_PIN)
        store.markVerified("a/b", Fixtures.VALID_PIN)
        store.markVerified(".hidden", Fixtures.VALID_PIN)
        store.markVerified("", Fixtures.VALID_PIN)
        store.markVerified("a".repeat(65), Fixtures.VALID_PIN)
        assertTrue(store.installedModelIds().isEmpty())
    }

    @Test
    fun `lastVerifiedDigest returns null for unsafe id`() {
        assertNull(store.lastVerifiedDigest(".."))
        assertNull(store.lastVerifiedDigest("a/b"))
        assertNull(store.lastVerifiedDigest(".hidden"))
        assertNull(store.lastVerifiedDigest(""))
        assertNull(store.lastVerifiedDigest("a".repeat(65)))
    }

    @Test
    fun `lastVerifiedDigest returns null when no marker exists`() {
        store.directoryFor("model1").mkdirs()
        assertNull(store.lastVerifiedDigest("model1"))
    }

    @Test
    fun `lastVerifiedDigest returns digest after markVerified`() {
        store.markVerified("model1", Fixtures.VALID_PIN)
        assertEquals(Fixtures.VALID_PIN, store.lastVerifiedDigest("model1"))
    }

    @Test
    fun `lastVerifiedDigest trims whitespace`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.VERIFIED_FILE).writeText("  ${Fixtures.VALID_PIN}  \n")
        assertEquals(Fixtures.VALID_PIN, store.lastVerifiedDigest("model1"))
    }

    // --- storeChecksums / storedChecksums ---

    @Test
    fun `storeChecksums creates directory and writes text`() {
        val text = "model.tar.bz2\t${Fixtures.VALID_PIN}"
        store.storeChecksums("model1", text)
        val file = File(store.directoryFor("model1"), LocalModelStore.CHECKSUMS_FILE)
        assertTrue(file.isFile)
        assertEquals(text, file.readText())
    }

    @Test
    fun `storeChecksums returns quietly for unsafe id`() {
        store.storeChecksums("..", "text")
        store.storeChecksums("a/b", "text")
        store.storeChecksums(".hidden", "text")
        store.storeChecksums("", "text")
        store.storeChecksums("a".repeat(65), "text")
        assertTrue(store.installedModelIds().isEmpty())
    }

    @Test
    fun `storedChecksums returns null for unsafe id`() {
        assertNull(store.storedChecksums(".."))
        assertNull(store.storedChecksums("a/b"))
        assertNull(store.storedChecksums(".hidden"))
        assertNull(store.storedChecksums(""))
        assertNull(store.storedChecksums("a".repeat(65)))
    }

    @Test
    fun `storedChecksums returns null when no file exists`() {
        store.directoryFor("model1").mkdirs()
        assertNull(store.storedChecksums("model1"))
    }

    @Test
    fun `storedChecksums returns text after storeChecksums`() {
        val text = "model.tar.bz2\t${Fixtures.VALID_PIN}"
        store.storeChecksums("model1", text)
        assertEquals(text, store.storedChecksums("model1"))
    }

    // --- delete ---

    @Test
    fun `delete returns false for unsafe id`() {
        assertFalse(store.delete(".."))
        assertFalse(store.delete("a/b"))
        assertFalse(store.delete(".hidden"))
        assertFalse(store.delete(""))
        assertFalse(store.delete("a".repeat(65)))
    }

    @Test
    fun `delete returns false when directory does not exist`() {
        assertFalse(store.delete("model1"))
    }

    @Test
    fun `delete removes directory and returns true`() {
        val dir = store.directoryFor("model1")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes("data".toByteArray())
        assertTrue(store.delete("model1"))
        assertFalse(dir.exists())
    }

    // --- discardFailedDownload ---

    @Test
    fun `discardFailedDownload deletes existing file`() {
        val file = Fixtures.writeBytes(File(tmp.root, "partial.bin"), "data".toByteArray())
        assertTrue(store.discardFailedDownload(file))
        assertFalse(file.exists())
    }

    @Test
    fun `discardFailedDownload returns true for non-existent file`() {
        val file = File(tmp.root, "nonexistent.bin")
        assertTrue(store.discardFailedDownload(file))
    }

    // --- stagingDirectory ---

    @Test
    fun `stagingDirectory returns correct path`() {
        assertEquals(File(tmp.root, ".staging"), store.stagingDirectory())
    }
}
