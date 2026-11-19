package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The archive name an entry carries plays no part in the install: the bytes are trusted only
 * when their sha256 and byte size are the entry's own pin. No list from upstream is read.
 */
class ModelInstallerArchiveNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.root)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class FakeFetcher(private val modelBytes: ByteArray) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    private fun entryNamed(archiveName: String, pin: String, sizeBytes: Long): ModelEntry =
        ModelEntry(
            "tiny",
            ModelFamily.SHERPA_ONNX,
            "https://example.com/model",
            pin,
            1,
            "Apache-2.0",
            false,
            archiveName = archiveName,
            sizeBytes = sizeBytes,
        )

    @Test
    fun `install refuses bytes whose digest is not the entry's pin whatever the archive name`() {
        // A real archive of the right size, so only the digest can refuse it.
        val bytes = TarFixtures.tinyArchive()
        val foreignPin = sha256Hex("other archive".toByteArray())
        val entry = entryNamed("archive-x.tar.bz2", foreignPin, bytes.size.toLong())
        val sunk = ArrayList<String>()
        val result = ModelInstaller(store, FakeFetcher(bytes), ModelDebugSink { sunk.add(it) }, usableSpace = { Long.MAX_VALUE }).install(entry)
        assertFalse("bytes that are not the pinned digest must not install", result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertTrue("the sink must hold the verdict: $sunk", sunk.single().contains("PIN_MISMATCH"))
        assertFalse("a refused download must not be left installed", store.isInstalled("tiny"))
        assertFalse("the staged download must be discarded", File(store.stagingDirectory(), "model.archive").exists())
        assertFalse("the model directory must not be created", store.directoryFor("tiny").exists())
    }

    @Test
    fun `install accepts the same bytes under an entry with an archive name and writes no checksum record`() {
        val bytes = TarFixtures.tinyArchive()
        val pin = sha256Hex(bytes)
        val entry = entryNamed("archive-x.tar.bz2", pin, bytes.size.toLong())
        val result = ModelInstaller(store, FakeFetcher(bytes), usableSpace = { Long.MAX_VALUE }).install(entry)
        assertTrue("bytes that are the pinned digest and size must install: $result", result.isInstalled)
        assertTrue("the installed model must be on disk", store.isInstalled("tiny"))
        assertNull("the install never records an upstream list", Fixtures.legacyChecksums(store, "tiny"))
    }

    @Test
    fun `a re-download whose digest is not the pin leaves the earlier install in place`() {
        val bytes = TarFixtures.tinyArchive()
        val pin = sha256Hex(bytes)
        val entry = entryNamed("archive-x.tar.bz2", pin, bytes.size.toLong())
        val first = ModelInstaller(store, FakeFetcher(bytes), usableSpace = { Long.MAX_VALUE }).install(entry)
        assertTrue("setup: the earlier install must succeed first", first.isInstalled)
        val archiveBefore = store.archiveFile("tiny").readBytes()

        val foreign = entryNamed("archive-x.tar.bz2", sha256Hex("other archive".toByteArray()), bytes.size.toLong())
        val again = ModelInstaller(store, FakeFetcher(bytes), usableSpace = { Long.MAX_VALUE }).install(foreign)
        assertFalse("a re-download that is not the pinned digest must not install", again.isInstalled)
        val refused = again as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertTrue("the earlier install must still be installed", store.isInstalled("tiny"))
        assertTrue("the earlier unpacked files must still be there", store.isExtracted("tiny"))
        assertArrayEquals("the earlier archive must be unchanged", archiveBefore, store.archiveFile("tiny").readBytes())
        assertEquals("the earlier verified digest must be unchanged", pin, store.lastVerifiedDigest("tiny"))
        assertFalse("the refused download must be discarded", File(store.stagingDirectory(), "model.archive").exists())
    }
}
