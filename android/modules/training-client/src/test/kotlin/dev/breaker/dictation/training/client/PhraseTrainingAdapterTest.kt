package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The core port wrapper. Not compiled or run yet. */
class PhraseTrainingAdapterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(wake: Int = 0, send: Int = 0) = TrainingFixture(folder.root, wake, send)

    @Test
    fun `record sample throws unsupported with the fixed text and touches nothing`() {
        val fx = fixture(10, 10)
        val adapter = PhraseTrainingAdapter(fx.client)
        val thrown = try {
            adapter.recordSample(PhraseKind.WAKE)
            null
        } catch (e: UnsupportedOperationException) {
            e
        }
        assertNotNull(thrown)
        assertEquals("recording is frame-fed: use PhraseRecorder", thrown?.message)
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
        assertFalse(File(folder.root, "ledger.txt").exists())
    }

    @Test
    fun `upload throws unsupported with the fixed text and touches nothing`() {
        val fx = fixture(10, 10)
        val adapter = PhraseTrainingAdapter(fx.client)
        val sample = PhraseSample(PhraseKind.WAKE, FloatArray(4) { 0.5f })
        val thrown = try {
            adapter.upload(listOf(sample))
            null
        } catch (e: UnsupportedOperationException) {
            e
        }
        assertNotNull(thrown)
        assertEquals("training starts only through startTraining", thrown?.message)
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
        assertFalse(File(folder.root, "ledger.txt").exists())
    }

    @Test
    fun `download model for either kind returns the same combined model with kind wake`() {
        val fx = fixture()
        fx.saveJob("job-1")
        fx.server.putModel(goodReply(samples = 9))
        val adapter = PhraseTrainingAdapter(fx.client)
        val wake = adapter.downloadModel(PhraseKind.WAKE)
        val send = adapter.downloadModel(PhraseKind.SEND)
        assertNotNull(wake)
        assertNotNull(send)
        assertEquals(PhraseKind.WAKE, wake?.kind)
        assertEquals(PhraseKind.WAKE, send?.kind)
        assertEquals(9, send?.samplesUsed)
        assertTrue(wake?.model?.bytes?.contentEquals(send?.model?.bytes) == true)
    }

    @Test
    fun `each adapter download makes one server call with the wake kind`() {
        val fx = fixture()
        fx.saveJob("job-1")
        fx.server.putModel(goodReply())
        val adapter = PhraseTrainingAdapter(fx.client)
        adapter.downloadModel(PhraseKind.SEND)
        assertEquals(listOf("downloadModel job-1 WAKE"), fx.server.calls)
        adapter.downloadModel(PhraseKind.WAKE)
        assertEquals(listOf("downloadModel job-1 WAKE", "downloadModel job-1 WAKE"), fx.server.calls)
    }

    @Test
    fun `download model is null for both kinds when there is no job`() {
        val fx = fixture()
        val adapter = PhraseTrainingAdapter(fx.client)
        assertNull(adapter.downloadModel(PhraseKind.WAKE))
        assertNull(adapter.downloadModel(PhraseKind.SEND))
    }

    @Test
    fun `download model is null for both kinds when the download fails`() {
        val fx = fixture()
        fx.saveJob("job-1")
        fx.server.scriptDownload(TrainingResult.Network, TrainingResult.Unavailable)
        val adapter = PhraseTrainingAdapter(fx.client)
        assertNull(adapter.downloadModel(PhraseKind.WAKE))
        assertNull(adapter.downloadModel(PhraseKind.SEND))
    }

    @Test
    fun `download model is null for both kinds when the model is refused`() {
        val fx = fixture()
        fx.saveJob("job-1")
        fx.server.putModel(ModelReply("0".repeat(64), 64L, 5, ByteArray(64)))
        val adapter = PhraseTrainingAdapter(fx.client)
        assertNull(adapter.downloadModel(PhraseKind.WAKE))
        assertNull(adapter.downloadModel(PhraseKind.SEND))
    }
}
