package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins what preload does with the recognizer the loader hands back: it only
 * checks that the model loads, so it must release that recognizer itself.
 *
 * Every test fails by assertion on a counter. Preload is synchronous and the
 * fake loader returns at once, so a broken engine cannot hang these tests.
 */
class OnDeviceSttEnginePreloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engines = mutableListOf<OnDeviceSttEngine>()

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    /** A recognizer that counts its own calls. Plain vars: see [RecognizerPerLoadLoader]. */
    private class CountingRecognizer : SherpaRecognizer {
        var releaseCount = 0
        var decodeCount = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            decodeCount++
            return SherpaTranscript("hello", emptyList(), "en")
        }

        override fun release() {
            releaseCount++
        }
    }

    /**
     * Builds a NEW [CountingRecognizer] on every load and keeps each one in
     * [recognizers]. The counters are plain vars: preload and transcribe return
     * only after runBlocking finishes on the dispatcher thread, which gives the
     * test thread a happens-before edge to every write made there.
     */
    private inner class RecognizerPerLoadLoader : ModelLoaderPort {
        val recognizers = mutableListOf<CountingRecognizer>()

        override fun load(modelId: String): ModelLoader.LoadResult {
            val recognizer = CountingRecognizer()
            recognizers.add(recognizer)
            return ModelLoader.LoadResult.Ready(SherpaModel(modelId, tmp.root, "digest"), recognizer)
        }
    }

    private fun newEngine(loader: ModelLoaderPort): OnDeviceSttEngine =
        OnDeviceSttEngine(loader).also { engines.add(it) }

    private fun validRequest(model: String = "tiny"): SttRequest = SttRequest(
        pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = model,
        language = "en",
        sampleRateHz = AudioFormat.SAMPLE_RATE_HZ,
    )

    @Test
    fun `preload releases the recognizer it loaded exactly once`() {
        val loader = RecognizerPerLoadLoader()
        val engine = newEngine(loader)

        assertNull(engine.preload("tiny"))

        assertEquals("preload must load exactly one recognizer", 1, loader.recognizers.size)
        assertEquals(
            "the recognizer preload loaded must be released exactly once",
            1,
            loader.recognizers[0].releaseCount,
        )
    }

    @Test
    fun `preload releases a fresh recognizer on every call, not only the first`() {
        val loader = RecognizerPerLoadLoader()
        val engine = newEngine(loader)

        assertNull(engine.preload("tiny"))
        assertNull(engine.preload("tiny"))

        assertEquals("each preload must load its own recognizer", 2, loader.recognizers.size)
        assertEquals(
            "the recognizer from the first preload must be released exactly once",
            1,
            loader.recognizers[0].releaseCount,
        )
        assertEquals(
            "the recognizer from the second preload must be released exactly once",
            1,
            loader.recognizers[1].releaseCount,
        )
    }

    @Test
    fun `preload keeps its bookkeeping and does not decode`() {
        val loader = RecognizerPerLoadLoader()
        val engine = newEngine(loader)

        assertNull(engine.preload("tiny"))

        val diagnostics = engine.diagnostics()
        assertEquals("preload must count one verification", 1, diagnostics.verificationCount)
        assertEquals("preload must not count a decode", 0, diagnostics.decodeCount)
        assertEquals("preload must record the model it was asked for", "tiny", diagnostics.lastModelId)
        assertEquals("preload must record the model it loaded", "tiny", diagnostics.loadedModelId)
        assertEquals(
            "preload must never call decode on the recognizer",
            0,
            loader.recognizers[0].decodeCount,
        )
    }

    @Test
    fun `preload then transcribe releases each recognizer once`() {
        val loader = RecognizerPerLoadLoader()
        val engine = newEngine(loader)

        assertNull(engine.preload("tiny"))
        val result = engine.transcribe(validRequest("tiny"))

        assertTrue("transcribe after preload must succeed, was $result", result is SttResult.Success)
        assertEquals("preload and transcribe must each load one recognizer", 2, loader.recognizers.size)
        assertEquals(
            "the recognizer preload loaded must be released exactly once",
            1,
            loader.recognizers[0].releaseCount,
        )
        assertEquals(
            "the recognizer transcribe loaded must be released exactly once",
            1,
            loader.recognizers[1].releaseCount,
        )
    }

    @Test
    fun `preload of a refused model creates and releases nothing`() {
        var loads = 0
        val loader = ModelLoaderPort {
            loads++
            ModelLoader.LoadResult.Refused(ModelLoader.Refusal.UNKNOWN_MODEL, "x")
        }
        val engine = newEngine(loader)

        val failure = engine.preload("nope")

        assertNotNull("a refused model must make preload return a failure", failure)
        assertEquals("preload must ask the loader once", 1, loads)
    }
}
