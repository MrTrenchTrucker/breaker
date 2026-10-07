package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the loader does when no engine factory is supplied: a model that passes
 * every check is still refused, because nothing can run it.
 */
class ModelLoaderDefaultEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(modelId, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)

    private class RecordingSink : ModelDebugSink {
        val messages = ArrayList<String>()
        override fun debug(message: String) {
            messages.add(message)
        }
    }

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            SherpaTranscript("", emptyList<SttSegment>(), "en")

        override fun release() {}
    }

    /** An installed model whose archive matches its pin and its stored checksum list. */
    private fun installedStore(): LocalModelStore {
        val store = LocalModelStore(tmp.newFolder())
        Fixtures.writeBytes(store.archiveFile(modelId), bytes)
        store.markVerified(modelId, digest)
        store.storeChecksums(modelId, "model.archive\t$digest")
        assertTrue("fixture: the model must be installed", store.isInstalled(modelId))
        return store
    }

    private fun lookup(requested: String): ModelEntry? = if (requested == modelId) entry else null

    @Test
    fun `load refuses a verified model with ENGINE_UNUSABLE when no engine factory is supplied`() {
        val store = installedStore()

        // Control: the same model and checks, with an engine supplied, load.
        val withEngine = ModelLoader(store, SherpaRecognizerFactory { IdleRecognizer }, { requested -> lookup(requested) })
        assertTrue("control: the model must load with an engine", withEngine.load(modelId).isReady)

        val sink = RecordingSink()
        val loader = ModelLoader(store, lookup = { requested -> lookup(requested) }, debug = sink)
        val result = loader.load(modelId)
        assertTrue("without an engine the load must be refused but got $result", result is ModelLoader.LoadResult.Refused)
        val refused = result as ModelLoader.LoadResult.Refused
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertEquals(ModelMessages.ENGINE_COULD_NOT_START, refused.detail)
        assertEquals(1, sink.messages.size)
        assertTrue(
            "the debug text must say no engine is wired in: ${sink.messages}",
            sink.messages[0].contains("no on-device engine is wired in"),
        )
        assertTrue("the verified archive must stay", store.isInstalled(modelId))
    }

    @Test
    fun `the default engine factory throws instead of creating a recognizer`() {
        val model = SherpaModel(modelId, tmp.newFolder(), digest)
        try {
            UnavailableRecognizerFactory.create(model)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("no on-device engine is wired in", e.message)
        }
    }
}
