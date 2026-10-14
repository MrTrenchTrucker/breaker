package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelLoaderArchiveNameTest {

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

    private class StubRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            return SherpaTranscript("hello", emptyList<SttSegment>(), "en")
        }
        override fun release() {}
    }

    /** Counts the recognizers it creates, so a test can show that a refused model never reached the engine. */
    private class CountingFactory : SherpaRecognizerFactory {
        var creates: Int = 0
        override fun create(model: SherpaModel): SherpaRecognizer {
            creates++
            return StubRecognizer()
        }
    }

    private val archiveBytes = "archive bytes for the archive name test".toByteArray()
    private val archiveDigest = sha256Hex(archiveBytes)

    private fun entryNamed(archiveName: String): ModelEntry =
        ModelEntry(
            "tiny",
            ModelFamily.SHERPA_ONNX,
            "https://example.com/model",
            archiveDigest,
            1,
            "Apache-2.0",
            false,
            archiveName = archiveName,
        )

    /** Leaves an installed, verified "tiny" with [checksumsText] as its stored checksum list. */
    private fun seedWithChecksums(checksumsText: String) {
        val dir = store.directoryFor("tiny")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(archiveBytes)
        store.markVerified("tiny", archiveDigest)
        store.storeChecksums("tiny", checksumsText)
        Fixtures.seedExtracted(store, "tiny")
    }

    @Test
    fun `load refuses a pin that is listed only on another archive line`() {
        seedWithChecksums("archive-y.tar.bz2\t$archiveDigest")
        val factory = CountingFactory()
        val loader = ModelLoader(store, factory, { id -> if (id == "tiny") entryNamed("archive-x.tar.bz2") else null })
        val result = loader.load("tiny")
        assertFalse("a pin listed only on another archive's line must not load", result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
        assertEquals("a refused model must not reach the engine", 0, factory.creates)
        assertFalse("a refused model must be deleted", store.isInstalled("tiny"))
    }

    @Test
    fun `load accepts the same archive when the pin is on its own archive line`() {
        val otherDigest = sha256Hex("other archive".toByteArray())
        seedWithChecksums("archive-x.tar.bz2\t$archiveDigest\narchive-y.tar.bz2\t$otherDigest")
        val factory = CountingFactory()
        val loader = ModelLoader(store, factory, { id -> if (id == "tiny") entryNamed("archive-x.tar.bz2") else null })
        val result = loader.load("tiny")
        assertTrue("a pin listed on the archive's own line must load", result.isReady)
        assertEquals("the engine must be created once for a loaded model", 1, factory.creates)
    }

    @Test
    fun `load with a blank archive name keeps the set match`() {
        seedWithChecksums("archive-y.tar.bz2\t$archiveDigest")
        val factory = CountingFactory()
        val loader = ModelLoader(store, factory, { id -> if (id == "tiny") entryNamed("") else null })
        val result = loader.load("tiny")
        assertTrue("with no archive name the pin matched anywhere in the list must still load", result.isReady)
    }
}
