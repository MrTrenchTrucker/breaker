package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

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

    private class FakeFetcher(
        private val modelBytes: ByteArray,
        private val checksumsText: String,
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    private fun entryNamed(archiveName: String, pin: String): ModelEntry =
        ModelEntry(
            "tiny",
            ModelFamily.SHERPA_ONNX,
            "https://example.com/model",
            pin,
            1,
            "Apache-2.0",
            false,
            archiveName = archiveName,
        )

    @Test
    fun `install refuses a pin that is listed only on another archive line`() {
        // A real archive, so the base code would accept it and unpack it.
        val bytes = TarFixtures.tinyArchive()
        val pin = sha256Hex(bytes)
        val entry = entryNamed("archive-x.tar.bz2", pin)
        val result = ModelInstaller(store, FakeFetcher(bytes, "archive-y.tar.bz2\t$pin")).install(entry)
        assertFalse("a pin listed only on another archive's line must not install", result.isInstalled)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertFalse("a refused download must not be left installed", store.isInstalled("tiny"))
        assertFalse("the staged download must be discarded", File(store.stagingDirectory(), "model.archive").exists())
        assertFalse("the model directory must not be created", store.directoryFor("tiny").exists())
    }

    @Test
    fun `install accepts the same archive when the pin is on its own archive line`() {
        val bytes = TarFixtures.tinyArchive()
        val pin = sha256Hex(bytes)
        val entry = entryNamed("archive-x.tar.bz2", pin)
        val otherDigest = sha256Hex("other archive".toByteArray())
        val checksums = "archive-x.tar.bz2\t$pin\narchive-y.tar.bz2\t$otherDigest"
        val result = ModelInstaller(store, FakeFetcher(bytes, checksums)).install(entry)
        assertTrue("a pin listed on the archive's own line must install", result.isInstalled)
        assertTrue("the installed model must be on disk", store.isInstalled("tiny"))
    }

    @Test
    fun `a re-download whose pin is listed only on another line leaves the earlier install in place`() {
        val bytes = TarFixtures.tinyArchive()
        val pin = sha256Hex(bytes)
        val entry = entryNamed("archive-x.tar.bz2", pin)
        val first = ModelInstaller(store, FakeFetcher(bytes, "archive-x.tar.bz2\t$pin")).install(entry)
        assertTrue("setup: the earlier install must succeed first", first.isInstalled)
        val archiveBefore = store.archiveFile("tiny").readBytes()
        val checksumsBefore = store.storedChecksums("tiny")

        val again = ModelInstaller(store, FakeFetcher(bytes, "archive-y.tar.bz2\t$pin")).install(entry)
        assertFalse("a re-download whose pin is only on another archive's line must not install", again.isInstalled)
        val refused = again as ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertTrue("the earlier install must still be installed", store.isInstalled("tiny"))
        assertTrue("the earlier unpacked files must still be there", store.isExtracted("tiny"))
        assertArrayEquals("the earlier archive must be unchanged", archiveBefore, store.archiveFile("tiny").readBytes())
        assertEquals("the earlier checksum record must be unchanged", checksumsBefore, store.storedChecksums("tiny"))
        assertEquals("the earlier verified digest must be unchanged", pin, store.lastVerifiedDigest("tiny"))
        assertFalse("the refused download must be discarded", File(store.stagingDirectory(), "model.archive").exists())
    }
}
