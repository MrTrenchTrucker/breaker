package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.WavEncoder
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The text a user sees when a step of a dictation throws is one fixed sentence.
 * The exception's class name and its message never reach the result, because a
 * message can carry the dictated text.
 */
class DictateUseCaseFailedTextTest {
    private companion object {
        const val MARKER = "marker text that must not reach the user"
    }

    private class EngineBlewUp(message: String) : RuntimeException(message)

    private class ThrowingEngine(private val error: Throwable) : SttEngine {
        override fun transcribe(request: SttRequest): SttResult = throw error
    }

    private class ThrowingWav(private val error: Throwable) : WavEncoder {
        override fun encode(pcm: FloatArray): ByteArray = throw error
    }

    private val audio = FloatArray(1_600) { 0.1f }

    private fun recording() = DictationSession().arm().startRecording()

    private fun useCase(
        localEngine: SttEngine = RecordingSttEngine.succeeding("hello world"),
        wavEncoder: WavEncoder = FakeWavEncoder,
    ) = DictateUseCase(
        settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe = ScriptedConnectivityProbe(reachable = true),
        localEngine = localEngine,
        serverEngine = RecordingSttEngine.succeeding("hello world"),
        serverFormatter = PassThroughFormatter,
        wavEncoder = wavEncoder,
        clock = FixedClock(),
        ids = SequentialIds(),
        localFormatter = PassThroughFormatter,
    )

    private fun assertFixedSentence(result: DictationResult, hidden: List<String>) {
        assertTrue("expected a failure, got $result", result is DictationResult.Failure)
        result as DictationResult.Failure
        assertEquals("the detail is the fixed sentence", "The speech could not be converted.", result.detail)
        for (text in hidden) {
            assertFalse("$text leaked into the detail", result.detail!!.contains(text))
        }
        assertEquals(SttError.OTHER, result.error)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
    }

    @Test
    fun `an engine exception shows the fixed sentence and hides its class and message`() {
        val result = useCase(localEngine = ThrowingEngine(EngineBlewUp(MARKER))).dictate(recording(), audio)

        assertFixedSentence(result, listOf("EngineBlewUp", MARKER))
    }

    @Test
    fun `a step outside the engine that throws shows the fixed sentence and hides its class and message`() {
        val result = useCase(wavEncoder = ThrowingWav(IOException(MARKER))).dictate(recording(), audio)

        assertFixedSentence(result, listOf("IOException", MARKER))
    }

    @Test
    fun `an anonymous exception class shows the fixed sentence from the engine and from a step outside it`() {
        val fromEngine = useCase(localEngine = ThrowingEngine(object : RuntimeException(MARKER) {}))
            .dictate(recording(), audio)
        val fromWav = useCase(wavEncoder = ThrowingWav(object : RuntimeException(MARKER) {}))
            .dictate(recording(), audio)

        assertFixedSentence(fromEngine, listOf(MARKER))
        assertFixedSentence(fromWav, listOf(MARKER))
    }

    @Test
    fun `an adapter failure result keeps its own detail text`() {
        val result = useCase(localEngine = RecordingSttEngine.failing(SttError.OTHER, "adapter text"))
            .dictate(recording(), audio)

        assertTrue("expected a failure, got $result", result is DictationResult.Failure)
        result as DictationResult.Failure
        assertEquals("the adapter detail is passed through unchanged", "adapter text", result.detail)
        assertEquals(SttError.OTHER, result.error)
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
    }

    @Test
    fun `an interruption shows the fixed sentence and leaves the interrupt flag set`() {
        try {
            val result = useCase(wavEncoder = ThrowingWav(InterruptedException(MARKER))).dictate(recording(), audio)

            assertFixedSentence(result, listOf("InterruptedException", MARKER))
            assertTrue("the interrupt flag was swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }
}
