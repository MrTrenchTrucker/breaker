package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttSegment
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
 * What the loader reports when a model that failed its check cannot be
 * deleted: the refusal stays, it says the file is still there, and the model
 * is still never loaded.
 */
class ModelLoaderDeleteFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(modelId, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript =
            SherpaTranscript("", emptyList<SttSegment>(), "en")

        override fun release() {}
    }

    private class CountingFactory : SherpaRecognizerFactory {
        var creates = 0
        override fun create(model: SherpaModel): SherpaRecognizer {
            creates++
            return IdleRecognizer
        }
    }

    /** One refused load: the result, every sink message, the factory and the store it ran on. */
    private class Outcome(
        val refused: ModelLoader.LoadResult.Refused,
        val sunk: List<String>,
        val factory: CountingFactory,
        val store: LocalModelStore,
    )

    /** Loads a model whose archive does not match its pin, on a store whose removal succeeds or fails. */
    private fun loadTamperedModel(removalWorks: Boolean): Outcome {
        val root = tmp.newFolder()
        val store = if (removalWorks) LocalModelStore(root) else LocalModelStore(root, remove = { false })
        Fixtures.writeBytes(File(store.directoryFor(modelId), LocalModelStore.ARCHIVE_NAME), "tampered".toByteArray())
        store.markVerified(modelId, digest)
        store.storeChecksums(modelId, "model.archive\t$digest")
        assertTrue("fixture: the model must be installed", store.isInstalled(modelId))

        val sunk = mutableListOf<String>()
        val factory = CountingFactory()
        val loader = ModelLoader(store, factory, { id -> if (id == modelId) entry else null }, ModelDebugSink { sunk.add(it) })
        val result = try {
            loader.load(modelId)
        } catch (t: Throwable) {
            throw AssertionError("load let ${t.javaClass.name} escape instead of returning a result: $t", t)
        }
        assertTrue("the load must be refused, got $result", result is ModelLoader.LoadResult.Refused)
        return Outcome(result as ModelLoader.LoadResult.Refused, sunk, factory, store)
    }

    @Test
    fun `load reports a rejected model that could not be deleted`() {
        val failed = loadTamperedModel(removalWorks = false)
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, failed.refused.refusal)
        assertTrue("the refusal must say the file is still on disk", failed.refused.leftOnDisk)
        assertEquals(ModelMessages.CHECK_FAILED_NOT_DELETED, failed.refused.detail)
        assertEquals("The model file failed its check but could not be deleted.", failed.refused.detail)
        assertFalse("the sentence must not claim a delete", failed.refused.detail.contains("was deleted"))
        assertFalse(failed.refused.detail.contains('/') || failed.refused.detail.contains('\\'))
        assertTrue("the rejected model must still be on disk", failed.store.directoryFor(modelId).isDirectory)
        assertEquals("the model must never be loaded", 0, failed.factory.creates)
        assertEquals("one sink call per refusal", 1, failed.sunk.size)
        assertTrue("the sink must hold the verdict: ${failed.sunk}", failed.sunk.single().contains("PIN_MISMATCH"))
        assertTrue("the sink must say the delete failed: ${failed.sunk}", failed.sunk.single().endsWith("; delete failed"))

        // Control: the same fixture with a removal that works.
        val worked = loadTamperedModel(removalWorks = true)
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, worked.refused.refusal)
        assertFalse("nothing is left on disk, so nothing is reported", worked.refused.leftOnDisk)
        assertEquals(ModelMessages.CHECK_FAILED_DELETED, worked.refused.detail)
        assertFalse(worked.store.directoryFor(modelId).exists())
        assertEquals(0, worked.factory.creates)
        assertEquals("one sink call per refusal", 1, worked.sunk.size)
        assertTrue("the sink must hold the verdict: ${worked.sunk}", worked.sunk.single().contains("PIN_MISMATCH"))
        assertFalse("no delete failure to report: ${worked.sunk}", worked.sunk.single().contains("delete failed"))
    }
}
