package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelLoaderTest {

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

    private class FakeFactory(
        private val recognizer: SherpaRecognizer?,
        private val throwOnCreate: Boolean = false,
    ) : SherpaRecognizerFactory {
        override fun create(model: SherpaModel): SherpaRecognizer {
            if (throwOnCreate) throw RuntimeException("engine failed")
            return recognizer!!
        }
    }

    private fun seedInstalledModel(id: String, bytes: ByteArray, digest: String) {
        val dir = store.directoryFor(id)
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified(id, digest)
        store.storeChecksums(id, "model.archive\t$digest")
        Fixtures.seedExtracted(store, id)
    }

    @Test
    fun `load refuses with UNKNOWN_MODEL when id is not in registry`() {
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { null })
        val result = loader.load("does-not-exist")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, refused.refusal)
    }

    @Test
    fun `load refuses with WRONG_FAMILY when entry is not SHERPA_ONNX`() {
        val entry = ModelEntry("base", ModelFamily.WHISPER, "https://example.com/model", "0".repeat(64), 1, "MIT", false)
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { id -> if (id == "base") entry else null })
        val result = loader.load("base")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.WRONG_FAMILY, refused.refusal)
    }

    @Test
    fun `load refuses with NOT_INSTALLED when model is not in store`() {
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", "0".repeat(64), 1, "Apache-2.0", false)
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
    }

    @Test
    fun `load refuses with CHECKSUMS_UNREADABLE when no checksums are stored`() {
        val bytes = "test".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val dir = store.directoryFor("tiny")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified("tiny", digest)
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
    }

    @Test
    fun `load refuses with CHECKSUMS_UNREADABLE when stored checksums are unparseable`() {
        val bytes = "test".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        val dir = store.directoryFor("tiny")
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified("tiny", digest)
        store.storeChecksums("tiny", "not valid checksums")
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
    }

    @Test
    fun `load refuses with VERIFICATION_REFUSED and deletes model when archive is tampered`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        seedInstalledModel("tiny", bytes, digest)
        File(store.directoryFor("tiny"), LocalModelStore.ARCHIVE_NAME).writeBytes("tampered".toByteArray())
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, refused.refusal)
        assertFalse(store.directoryFor("tiny").exists())
    }

    @Test
    fun `load refuses with ENGINE_UNUSABLE when factory throws`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        seedInstalledModel("tiny", bytes, digest)
        val loader = ModelLoader(store, FakeFactory(null, throwOnCreate = true), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
    }

    private class ThrowingFactory(private val failure: Throwable) : SherpaRecognizerFactory {
        override fun create(model: SherpaModel): SherpaRecognizer = throw failure
    }

    /** Seeds an installed, verifiable model "tiny" and returns a loader whose factory throws [failure]. */
    private fun loaderWhoseFactoryThrows(failure: Throwable): ModelLoader {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        seedInstalledModel("tiny", bytes, digest)
        return ModelLoader(store, ThrowingFactory(failure), { id -> if (id == "tiny") entry else null })
    }

    /** Loads "tiny" and fails with a clear message if the load throws instead of returning. */
    private fun loadWithoutEscape(loader: ModelLoader): ModelLoader.LoadResult =
        try {
            loader.load("tiny")
        } catch (t: Throwable) {
            throw AssertionError("load let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }

    @Test
    fun `load refuses with ENGINE_UNUSABLE when the factory throws a SherpaTranscriptionException`() {
        val loader = loaderWhoseFactoryThrows(SherpaTranscriptionException("init failed"))
        val result = loadWithoutEscape(loader)
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertTrue(File(store.directoryFor("tiny"), LocalModelStore.ARCHIVE_NAME).exists())
    }

    @Test
    fun `load refuses with ENGINE_UNUSABLE when the factory throws a checked-style Exception`() {
        val loader = loaderWhoseFactoryThrows(IOException("x"))
        val result = loadWithoutEscape(loader)
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertTrue(File(store.directoryFor("tiny"), LocalModelStore.ARCHIVE_NAME).exists())
    }

    @Test
    fun `load lets an Error from the factory pass through`() {
        val failure = StackOverflowError()
        val loader = loaderWhoseFactoryThrows(failure)
        val thrown: Throwable? = try {
            loader.load("tiny")
            null
        } catch (t: Throwable) {
            t
        }
        assertNotNull("load returned a result instead of letting the Error pass through", thrown)
        assertSame(failure, thrown)
    }

    @Test
    fun `load returns Ready when model is installed and verified`() {
        val bytes = "test model bytes".toByteArray()
        val digest = sha256Hex(bytes)
        val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
        seedInstalledModel("tiny", bytes, digest)
        val recognizer = StubRecognizer()
        val loader = ModelLoader(store, FakeFactory(recognizer), { id -> if (id == "tiny") entry else null })
        val result = loader.load("tiny")
        assertTrue(result.isReady)
        val ready = result as ModelLoader.LoadResult.Ready
        assertEquals("tiny", ready.model.modelId)
        assertEquals(digest, ready.model.digest)
        assertSame(recognizer, ready.recognizer)
    }

    @Test
    fun `load uses default registry lookup when no lookup is injected`() {
        val loader = ModelLoader(store, FakeFactory(StubRecognizer()))
        val result = loader.load("does-not-exist")
        assertFalse(result.isReady)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, refused.refusal)
    }
}
