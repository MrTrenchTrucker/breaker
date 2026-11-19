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

/**
 * A load judges the archive by the registry entry's own pin (sha256 and byte size). The `.checksums`
 * file an earlier version stored in the model folder is never read: an installed model must keep
 * loading after an update whether that file is good, garbage, or absent.
 */
class ModelLoaderPinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    private val bytes = "installed archive bytes".toByteArray()
    private val digest = httpTestSha256Hex(bytes)

    private class Stub : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int) = SherpaTranscript("hi", emptyList<SttSegment>(), "en")
        override fun release() {}
    }

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.newFolder("models"))
    }

    private fun entry(sizeBytes: Long) = ModelEntry(
        "tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false,
        sizeBytes = sizeBytes,
    )

    /** An installed model with no stamp, so the load verifies the archive itself. */
    private fun seed() {
        val dir = store.directoryFor("tiny")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified("tiny", digest)
        Fixtures.seedExtracted(store, "tiny")
    }

    private fun loader(e: ModelEntry) = ModelLoader(store, { Stub() }, { id -> if (id == "tiny") e else null })

    @Test
    fun `a model with no checksums file loads`() {
        seed()
        assertTrue(loader(entry(bytes.size.toLong())).load("tiny").isReady)
    }

    @Test
    fun `a model with a garbage checksums file loads`() {
        seed()
        Fixtures.writeLegacyChecksums(store, "tiny", "not valid checksums")
        assertTrue(loader(entry(bytes.size.toLong())).load("tiny").isReady)
    }

    @Test
    fun `a model whose checksums file names another digest loads`() {
        seed()
        Fixtures.writeLegacyChecksums(store, "tiny", "model.archive\t${Fixtures.OTHER_PIN}")
        assertTrue(loader(entry(bytes.size.toLong())).load("tiny").isReady)
    }

    @Test
    fun `a wrong pinned size refuses and deletes the model even with a good checksums file`() {
        seed()
        Fixtures.writeLegacyChecksums(store, "tiny", "model.archive\t$digest")
        val refused = loader(entry(bytes.size.toLong() + 1L)).load("tiny") as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
        assertFalse(store.isInstalled("tiny"))
    }

    @Test
    fun `an entry with no pinned size refuses`() {
        seed()
        val refused = loader(entry(0L)).load("tiny") as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
    }
}
