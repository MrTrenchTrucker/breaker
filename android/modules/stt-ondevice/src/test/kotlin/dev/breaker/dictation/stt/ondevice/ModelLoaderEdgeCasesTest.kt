package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Loader behaviour the other loader tests do not look at: what a refusal
 * carries besides its sentence, what a loaded model names, how the pin's letter
 * case is treated, and what the default registry lookup finds.
 */
class ModelLoaderEdgeCasesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)

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

    private fun entryFor(family: ModelFamily = ModelFamily.SHERPA_ONNX, pin: String = digest) =
        ModelEntry(modelId, family, "https://example.com/model", pin, 1, "Apache-2.0", false)

    private fun lookupOf(entry: ModelEntry?): (String) -> ModelEntry? =
        { requested -> if (requested == modelId) entry else null }

    private fun newStore(removalWorks: Boolean = true): LocalModelStore =
        if (removalWorks) LocalModelStore(tmp.newFolder()) else LocalModelStore(tmp.newFolder(), remove = { false })

    /** Writes an archive with the verified marker, and stores [checksums] when given. */
    private fun seed(store: LocalModelStore, archiveBytes: ByteArray = bytes, checksums: String? = null) {
        Fixtures.writeBytes(File(store.directoryFor(modelId), LocalModelStore.ARCHIVE_NAME), archiveBytes)
        store.markVerified(modelId, digest)
        if (checksums != null) store.storeChecksums(modelId, checksums)
    }

    private fun load(loader: ModelLoader, id: String = modelId): ModelLoader.LoadResult =
        try {
            loader.load(id)
        } catch (t: Throwable) {
            throw AssertionError("load let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }

    private fun refusedOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Refused {
        assertTrue("expected a refusal but got $result", result is ModelLoader.LoadResult.Refused)
        return result as ModelLoader.LoadResult.Refused
    }

    private fun readyOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Ready {
        assertTrue("expected a loaded model but got $result", result is ModelLoader.LoadResult.Ready)
        return result as ModelLoader.LoadResult.Ready
    }

    /** One refusal of every kind except a wrong family, each from a fresh store. */
    private fun refusalsWithoutFamily(): List<Pair<String, ModelLoader.LoadResult.Refused>> {
        val cases = ArrayList<Pair<String, ModelLoader.LoadResult.Refused>>()
        cases.add("unknown model" to refusedOf(load(ModelLoader(newStore(), StubFactory(), lookupOf(null)))))
        cases.add("not installed" to refusedOf(load(ModelLoader(newStore(), StubFactory(), lookupOf(entryFor())))))

        val missing = newStore()
        seed(missing)
        cases.add("no checksums" to refusedOf(load(ModelLoader(missing, StubFactory(), lookupOf(entryFor())))))

        val garbage = newStore()
        seed(garbage, checksums = "not valid checksums")
        cases.add("unparseable checksums" to refusedOf(load(ModelLoader(garbage, StubFactory(), lookupOf(entryFor())))))

        val tampered = newStore()
        seed(tampered, "tampered".toByteArray(), "model.archive\t$digest")
        cases.add("failed check" to refusedOf(load(ModelLoader(tampered, StubFactory(), lookupOf(entryFor())))))

        val engine = newStore()
        seed(engine, checksums = "model.archive\t$digest")
        val failing = StubFactory(IllegalStateException("engine text"))
        cases.add("engine failure" to refusedOf(load(ModelLoader(engine, failing, lookupOf(entryFor())))))
        return cases
    }

    @Test
    fun `a refusal that is not a wrong family carries no family`() {
        val cases = refusalsWithoutFamily()
        assertEquals("every refusal kind must be built", 6, cases.size)
        var checked = 0
        for ((label, refused) in cases) {
            assertNull("$label: a refusal other than a wrong family must carry no family", refused.family)
            checked++
        }
        assertEquals(6, checked)

        // Control: the wrong family refusal does carry the family, so the field is read and set.
        val wrong = refusedOf(load(ModelLoader(newStore(), StubFactory(), lookupOf(entryFor(ModelFamily.WHISPER)))))
        assertEquals(ModelLoader.Refusal.WRONG_FAMILY, wrong.refusal)
        assertEquals(ModelFamily.WHISPER, wrong.family)
    }

    @Test
    fun `a refusal that deleted nothing says nothing is left on disk`() {
        val cases = refusalsWithoutFamily()
        assertEquals("every refusal kind must be built", 6, cases.size)
        var checked = 0
        for ((label, refused) in cases) {
            assertFalse("$label: nothing failed to delete, so nothing is left on disk", refused.leftOnDisk)
            checked++
        }
        assertEquals(6, checked)

        val wrong = refusedOf(load(ModelLoader(newStore(), StubFactory(), lookupOf(entryFor(ModelFamily.WHISPER)))))
        assertFalse("wrong family: nothing to delete", wrong.leftOnDisk)

        // Control: a failed check whose delete fails does say the file is left.
        val stuck = newStore(removalWorks = false)
        seed(stuck, "tampered".toByteArray(), "model.archive\t$digest")
        val left = refusedOf(load(ModelLoader(stuck, StubFactory(), lookupOf(entryFor()))))
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, left.refusal)
        assertTrue(left.leftOnDisk)
    }

    @Test
    fun `a loaded model names its model directory`() {
        val store = newStore()
        seed(store, checksums = "model.archive\t$digest")
        val ready = readyOf(load(ModelLoader(store, StubFactory(), lookupOf(entryFor()))))
        assertEquals(modelId, ready.model.modelId)
        assertEquals(store.directoryFor(modelId), ready.model.directory)
        assertTrue("the model directory must be a directory", ready.model.directory.isDirectory)
        assertNotEquals("the model directory is not the archive file", store.archiveFile(modelId), ready.model.directory)
    }

    @Test
    fun `a loaded model carries the computed lowercase digest when the pin is written in capitals`() {
        val capitals = digest.uppercase()
        assertNotEquals("fixture: the digest must contain letters", digest, capitals)
        val store = newStore()
        seed(store, checksums = "model.archive\t$digest")
        val ready = readyOf(load(ModelLoader(store, StubFactory(), lookupOf(entryFor(pin = capitals)))))
        assertEquals(digest, ready.model.digest)

        // Control: a lowercase pin gives the same digest.
        val other = newStore()
        seed(other, checksums = "model.archive\t$digest")
        val control = readyOf(load(ModelLoader(other, StubFactory(), lookupOf(entryFor(pin = digest)))))
        assertEquals(digest, control.model.digest)
    }

    @Test
    fun `the default lookup finds every model that is in the registry`() {
        assertTrue("the registry must list models", ModelRegistry.ALL.isNotEmpty())
        var checked = 0
        for (entry in ModelRegistry.ALL) {
            val refused = refusedOf(load(ModelLoader(newStore(), StubFactory()), entry.id))
            assertNotEquals("${entry.id} is in the registry", ModelLoader.Refusal.UNKNOWN_MODEL, refused.refusal)
            val expected = if (entry.family == ModelFamily.SHERPA_ONNX) {
                ModelLoader.Refusal.NOT_INSTALLED
            } else {
                ModelLoader.Refusal.WRONG_FAMILY
            }
            assertEquals("${entry.id} refusal", expected, refused.refusal)
            checked++
        }
        assertEquals(ModelRegistry.ALL.size, checked)

        // Control: an id that is not in the registry is unknown.
        val unknown = refusedOf(load(ModelLoader(newStore(), StubFactory()), "no-such-model"))
        assertEquals(ModelLoader.Refusal.UNKNOWN_MODEL, unknown.refusal)
    }
}
