package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private val tamperedDigest = Fixtures.independentSha256("tampered".toByteArray())

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
        ModelEntry(modelId, family, "https://example.com/model", digest, 1, "Apache-2.0", false)

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
        if (checksums != null) store.storeChecksums(modelId, checksums)
    }

    /** The text the parser gives for [text]; the test fails if the parser accepts it. */
    private fun parserMessage(text: String): String {
        try {
            UpstreamChecksums.parse(text)
        } catch (e: UpstreamChecksums.ChecksumFormatException) {
            return e.message ?: ""
        }
        throw AssertionError("the parser accepted: $text")
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
    fun `missing stored checksums send a technical reason and not the user sentence to the sink`() {
        val store = newStore()
        seed(store)
        val missing = loadRefused(store, entryFor())
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, missing.refused.refusal)
        assertEquals("The model could not be checked right now.", missing.refused.detail)
        assertSunk(missing, "CHECKSUMS_UNREADABLE: no stored checksums for 'tiny'")

        // A stored but empty text is a different case: it is read and then fails to parse.
        val emptyStore = newStore()
        seed(emptyStore, checksums = "")
        val empty = loadRefused(emptyStore, entryFor())
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, empty.refused.refusal)
        assertEquals(missing.refused.detail, empty.refused.detail)
        assertNotEquals("the sink must tell a missing list from an empty one", missing.sunk.single(), empty.sunk.single())
        assertTrue(empty.sunk.single(), empty.sunk.single().startsWith("CHECKSUMS_UNREADABLE: stored checksums unparseable: "))
    }

    @Test
    fun `unparseable stored checksums send the parser text to the sink`() {
        val garbage = "not valid checksums"
        val parserText = parserMessage(garbage)
        assertTrue("fixture: the parser must say something", parserText.isNotEmpty())
        val store = newStore()
        seed(store, checksums = garbage)
        val outcome = loadRefused(store, entryFor())
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, outcome.refused.refusal)
        assertEquals("The model could not be checked right now.", outcome.refused.detail)
        assertSunk(outcome, "CHECKSUMS_UNREADABLE: stored checksums unparseable: $parserText")
        assertFalse("the user sentence must not be the sink text", outcome.sunk.single().contains(outcome.refused.detail))
    }

    @Test
    fun `a failed check sends the verdict to the sink and notes a failed delete only when it failed`() {
        val deleted = newStore()
        seed(deleted, "tampered".toByteArray(), "model.archive\t$digest")
        val worked = loadRefused(deleted, entryFor())
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, worked.refused.refusal)
        assertEquals("The model file failed its check and was deleted. Download it again.", worked.refused.detail)
        assertSunk(worked, "VERIFICATION_REFUSED: PIN_MISMATCH: expected $digest, found $tamperedDigest")

        val stuck = LocalModelStore(tmp.newFolder(), remove = { false })
        seed(stuck, "tampered".toByteArray(), "model.archive\t$digest")
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
