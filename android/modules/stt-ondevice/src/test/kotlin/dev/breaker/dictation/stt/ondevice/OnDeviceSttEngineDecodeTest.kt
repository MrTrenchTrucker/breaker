package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.SttSegment
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What the engine hands to the recognizer, what it returns from a decode, and
 * which failures it reports instead of throwing.
 *
 * A wrong result must fail an assertion that names the claim. An exception
 * that escapes the engine where a result is expected is turned into such an
 * assertion.
 */
class OnDeviceSttEngineDecodeTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()

    private fun track(engine: OnDeviceSttEngine): OnDeviceSttEngine {
        engines.add(engine)
        return engine
    }

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    /** Records what it was given and answers with a fixed transcript. */
    private class RecordingRecognizer(private val transcript: SherpaTranscript) : SherpaRecognizer {
        var pcm: FloatArray? = null
        var rate = -1

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            this.pcm = pcm.copyOf()
            this.rate = sampleRateHz
            return transcript
        }

        override fun release() {}
    }

    /** Fails every decode with [failure] and counts releases. */
    private class FailingRecognizer(private val failure: Throwable) : SherpaRecognizer {
        var releases = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = throw failure

        override fun release() {
            releases++
        }
    }

    /** An Error type of this test's own, so no test depends on a real stack or heap failure. */
    private class LoaderError : Error("the store failed hard")

    private fun loaderFor(recognizer: SherpaRecognizer): ModelLoaderPort =
        ModelLoaderPort {
            ModelLoader.LoadResult.Ready(SherpaModel("tiny", File("unused-model-dir"), "unused-digest"), recognizer)
        }

    private fun engineFor(recognizer: SherpaRecognizer): OnDeviceSttEngine =
        track(OnDeviceSttEngine(loaderFor(recognizer), newSlotWatcher()))

    private fun request(
        pcm: FloatArray = floatArrayOf(0.1f, 0.2f, 0.3f),
        language: String = "en",
    ): SttRequest = SttRequest(
        pcm = pcm,
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = "tiny",
        language = language,
    )

    private fun transcribeOrFail(engine: OnDeviceSttEngine, claim: String): SttResult =
        try {
            engine.transcribe(request())
        } catch (e: Throwable) {
            throw AssertionError("$claim: transcribe threw $e instead of returning a failure", e)
        }

    @Test
    fun `transcribe hands the recognizer the request audio and the request sample rate`() {
        val audio = floatArrayOf(0.25f, -0.5f, 0.75f, 1.0f)
        val recognizer = RecordingRecognizer(SherpaTranscript("hi", emptyList(), "en"))

        engineFor(recognizer).transcribe(request(pcm = audio))

        assertArrayEquals(
            "the recognizer must receive the audio of the request",
            audio,
            recognizer.pcm ?: FloatArray(0),
            0.0f,
        )
        assertEquals("the recognizer must receive the sample rate of the request", 16_000, recognizer.rate)
    }

    @Test
    fun `transcribe returns the text, the segments and the language the recognizer produced`() {
        val segments = listOf(SttSegment(0L, 500L, "hi"))
        val recognizer = RecordingRecognizer(SherpaTranscript("hi", segments, "de"))

        val result = engineFor(recognizer).transcribe(request(language = "en"))

        assertTrue("transcribe did not return a transcript", result is SttResult.Success)
        val success = result as SttResult.Success
        assertEquals("the text must be the recognizer's text", "hi", success.text)
        assertEquals("the segments must be the recognizer's segments", segments, success.segments)
        assertEquals("a language the recognizer reports must win over the request language", "de", success.language)
    }

    @Test
    fun `transcribe falls back to the request language when the recognizer reports a blank language`() {
        for (blank in listOf("", "  ")) {
            val recognizer = RecordingRecognizer(SherpaTranscript("hi", emptyList(), blank))

            val result = engineFor(recognizer).transcribe(request(language = "fr"))

            assertTrue("transcribe did not return a transcript", result is SttResult.Success)
            assertEquals(
                "a blank language (length ${blank.length}) must be replaced by the request language",
                "fr",
                (result as SttResult.Success).language,
            )
        }
    }

    @Test
    fun `a decode that throws the engine's transcription exception returns the decode failure and releases once`() {
        val recognizer = FailingRecognizer(SherpaTranscriptionException("decode failed"))

        val result = transcribeOrFail(engineFor(recognizer), "a transcription exception from decode")

        assertEquals("the failure must be the decode failure", ErrorMapping.decodeFailed(), result)
        assertEquals("the recognizer must be released once", 1, recognizer.releases)
    }

    @Test
    fun `a decode that overflows the stack returns the decode failure and releases once`() {
        val recognizer = FailingRecognizer(StackOverflowError())

        val result = transcribeOrFail(engineFor(recognizer), "a stack overflow from decode")

        assertEquals("the failure must be the decode failure", ErrorMapping.decodeFailed(), result)
        assertEquals("the recognizer must be released once", 1, recognizer.releases)
    }

    @Test
    fun `a decode that runs out of memory returns the decode failure and releases once`() {
        val recognizer = FailingRecognizer(OutOfMemoryError("test"))

        val result = transcribeOrFail(engineFor(recognizer), "an out-of-memory error from decode")

        assertEquals("the failure must be the decode failure", ErrorMapping.decodeFailed(), result)
        assertEquals("the recognizer must be released once", 1, recognizer.releases)
    }

    @Test
    fun `an Error thrown by the loader during transcribe reaches the caller instead of being reported`() {
        val engine = track(OnDeviceSttEngine(ModelLoaderPort { throw LoaderError() }, newSlotWatcher()))

        val outcome: Any? = try {
            engine.transcribe(request())
        } catch (e: Throwable) {
            e
        }

        assertTrue(
            "only Exceptions are mapped: an Error from the loader must be thrown to the caller, but got $outcome",
            outcome is LoaderError,
        )
    }

    @Test
    fun `an Error thrown by the loader during preload reaches the caller instead of being reported`() {
        val engine = track(OnDeviceSttEngine(ModelLoaderPort { throw LoaderError() }, newSlotWatcher()))

        val outcome: Any? = try {
            engine.preload("tiny")
        } catch (e: Throwable) {
            e
        }

        assertTrue(
            "only Exceptions are mapped: an Error from the loader must be thrown to the caller, but got $outcome",
            outcome is LoaderError,
        )
    }
}
