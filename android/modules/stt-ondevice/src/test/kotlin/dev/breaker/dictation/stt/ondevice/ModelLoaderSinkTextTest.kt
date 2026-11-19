package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The technical text the loader sends to the debug sink, one test per refusal,
 * and the fixed sentence the user gets with it. The sink text is for a
 * developer, so it is held exactly: the refusal name first, then what went
 * wrong, with the model id, the parser text or the engine text in it.
 */
class ModelLoaderSinkTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val tampered = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
    private val tamperedDigest = Fixtures.independentSha256(tampered)
    private val longer = bytes + byteArrayOf(0)

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            SherpaTranscript("", emptyList(), "en")

        override fun release() {}
    }

    private class StubFactory(private val failure: Exception? = null) : SherpaRecognizerFactory {
        override fun create(model: SherpaModel): SherpaRecognizer {
            if (failure != null) throw failure
            return IdleRecognizer
        }
    }

    /** One refused load: the result and every message the sink received. */
    private class Outcome(val refused: ModelLoader.LoadResult.Refused, val sunk: List<String>)

    private fun entryFor(family: ModelFamily = ModelFamily.SHERPA_ONNX) =
        ModelEntry(modelId, family, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())

    /** Loads [modelId] on [store] with a recording sink and requires a refusal. */
    private fun loadRefused(
        store: LocalModelStore,
        entry: ModelEntry?,
        factory: SherpaRecognizerFactory = StubFactory(),
    ): Outcome {
        val sunk = ArrayList<String>()
        val lookup: (String) -> ModelEntry? = { requested -> if (requested == modelId) entry else null }
        val loader = ModelLoader(store, factory, lookup, ModelDebugSink { message -> sunk.add(message) })
        val result = try {
            loader.load(modelId)
        } catch (t: Throwable) {
            throw AssertionError("load let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }
        assertTrue("the load must be refused, got $result", result is ModelLoader.LoadResult.Refused)
        return Outcome(result as ModelLoader.LoadResult.Refused, sunk)
    }

    private fun newStore(): LocalModelStore = LocalModelStore(tmp.newFolder())

    private fun seed(store: LocalModelStore, archiveBytes: ByteArray = bytes, checksums: String? = null) {
        Fixtures.writeBytes(File(store.directoryFor(modelId), LocalModelStore.ARCHIVE_NAME), archiveBytes)
        store.markVerified(modelId, digest)
        if (checksums != null) Fixtures.writeLegacyChecksums(store, modelId, checksums)
        Fixtures.seedExtracted(store, modelId)
    }

    /** Loads [modelId] on [store] and requires a loaded model; returns every message the sink received. */
    private fun loadReady(store: LocalModelStore, entry: ModelEntry): List<String> {
        val sunk = ArrayList<String>()
        val lookup: (String) -> ModelEntry? = { requested -> if (requested == modelId) entry else null }
        val result = ModelLoader(store, StubFactory(), lookup, ModelDebugSink { message -> sunk.add(message) }).load(modelId)
        assertTrue("the load must succeed, got $result", result is ModelLoader.LoadResult.Ready)
        return sunk
    }

    private fun assertSunk(outcome: Outcome, expected: String) {
        assertEquals("one sink call per refusal", 1, outcome.sunk.size)
        assertEquals(expected, outcome.sunk.single())
    }

    @Test
    fun `an unknown model sends its id to the sink after the refusal name`() {
        val outcome = loadRefused(newStore(), null)
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, outcome.refused.refusal)
        assertEquals("That model is not available.", outcome.refused.detail)
        assertSunk(outcome, "UNKNOWN_MODEL: no model registered as 'tiny'")
    }

    @Test
    fun `a wrong family sends the id and the family to the sink after the refusal name`() {
        val outcome = loadRefused(newStore(), entryFor(ModelFamily.WHISPER))
        assertEquals(ModelLoader.Refusal.WRONG_FAMILY, outcome.refused.refusal)
        assertEquals("The on-device engine cannot run that model.", outcome.refused.detail)
        assertSunk(outcome, "WRONG_FAMILY: model 'tiny' is WHISPER, not SHERPA_ONNX")
    }

    @Test
    fun `a model that is not installed sends its id to the sink after the refusal name`() {
        val outcome = loadRefused(newStore(), entryFor())
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, outcome.refused.refusal)
        assertEquals("The model is not installed. Download it first.", outcome.refused.detail)
        assertSunk(outcome, "NOT_INSTALLED: model 'tiny' is not installed")
    }

    @Test
    fun `a missing or empty stored checksums file loads and sends nothing to the sink`() {
        val store = newStore()
        seed(store)
        assertTrue("a model with no checksums file must load without a sink message", loadReady(store, entryFor()).isEmpty())

        val emptyStore = newStore()
        seed(emptyStore, checksums = "")
        assertTrue("a model with an empty checksums file must load without a sink message", loadReady(emptyStore, entryFor()).isEmpty())
    }

    @Test
    fun `unparseable stored checksums load and send nothing to the sink`() {
        val store = newStore()
        seed(store, checksums = "not valid checksums")
        assertTrue("unparseable stored checksums must not reach the sink", loadReady(store, entryFor()).isEmpty())
    }

    @Test
    fun `an archive of another length sends the size verdict to the sink`() {
        val store = newStore()
        seed(store, longer, "model.archive\t$digest")
        val outcome = loadRefused(store, entryFor())
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, outcome.refused.refusal)
        assertEquals("The model file failed its check and was deleted. Download it again.", outcome.refused.detail)
        assertSunk(outcome, "VERIFICATION_REFUSED: SIZE_MISMATCH: expected ${bytes.size} bytes, found ${longer.size}")
        assertFalse("the user sentence must not be the sink text", outcome.sunk.single().contains(outcome.refused.detail))
    }

    @Test
    fun `a failed check sends the verdict to the sink and notes a failed delete only when it failed`() {
        val deleted = newStore()
        seed(deleted, tampered, "model.archive\t$digest")
        val worked = loadRefused(deleted, entryFor())
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, worked.refused.refusal)
        assertEquals("The model file failed its check and was deleted. Download it again.", worked.refused.detail)
        assertSunk(worked, "VERIFICATION_REFUSED: PIN_MISMATCH: expected $digest, found $tamperedDigest")

        val stuck = LocalModelStore(tmp.newFolder(), remove = { false })
        seed(stuck, tampered, "model.archive\t$digest")
        val failed = loadRefused(stuck, entryFor())
        assertEquals("The model file failed its check but could not be deleted.", failed.refused.detail)
        assertSunk(failed, "VERIFICATION_REFUSED: PIN_MISMATCH: expected $digest, found $tamperedDigest; delete failed")
    }

    @Test
    fun `an engine that fails sends the engine text to the sink after the refusal name`() {
        val store = newStore()
        seed(store, checksums = "model.archive\t$digest")
        val outcome = loadRefused(store, entryFor(), StubFactory(IllegalStateException("init failed")))
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, outcome.refused.refusal)
        assertEquals("The on-device engine could not start with this model.", outcome.refused.detail)
        assertSunk(outcome, "ENGINE_UNUSABLE: engine failed: init failed")
    }
}
