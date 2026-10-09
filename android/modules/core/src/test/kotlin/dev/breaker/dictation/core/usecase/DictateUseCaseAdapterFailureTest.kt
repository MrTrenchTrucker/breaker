package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.WavEncoder
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import dev.breaker.dictation.core.testing.ThrowingSttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An adapter that throws instead of returning a failure must not take the flow
 * down. Every port the dictation reaches after the state machine has moved is
 * checked here: the failure comes back as a [DictationResult.Failure], the
 * session is in ERROR so the caller can carry on, and the exception's message
 * (which can hold the dictated text) never reaches the result.
 */
class DictateUseCaseAdapterFailureTest {
    private val secret = "SECRET words an adapter must never leak"

    private fun boom(): RuntimeException = IllegalStateException(secret)

    private class ThrowingSettings(private val error: Throwable) : SettingsStore {
        override fun load(): AppSettings = throw error
        override fun save(settings: AppSettings) = Unit
    }

    private class ThrowingProbe(private val error: Throwable) : ConnectivityProbe {
        override fun isServerReachable(): Boolean = throw error
    }

    private class ThrowingWav(private val error: Throwable) : WavEncoder {
        override fun encode(pcm: FloatArray): ByteArray = throw error
    }

    private class ThrowingFormatter(private val error: Throwable) : Formatter {
        override fun format(rawText: String): String = throw error
    }

    private class ThrowingIds(private val error: Throwable) : IdSource {
        override fun newId(): String = throw error
    }

    private class ThrowingClock(private val error: Throwable) : Clock {
        override fun nowEpochMillis(): Long = throw error
    }

    private class ThrowingEngine(private val error: Throwable) : SttEngine {
        override fun transcribe(request: SttRequest): SttResult = throw error
    }

    /** Not an adapter failure: a fault in the runtime itself. */
    private class Meltdown : Error("the runtime is gone")

    private val local = RecordingSttEngine.succeeding("hello world")
    private val server = RecordingSttEngine.succeeding("hello world")
    private val audio = FloatArray(1_600) { 0.1f }

    private fun recording() = DictationSession().arm().startRecording()

    private fun useCase(
        settings: SettingsStore = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe: ConnectivityProbe = ScriptedConnectivityProbe(reachable = true),
        formatter: Formatter = PassThroughFormatter,
        localFormatter: Formatter = PassThroughFormatter,
        wavEncoder: WavEncoder = FakeWavEncoder,
        clock: Clock = FixedClock(),
        ids: IdSource = SequentialIds(),
    ) = DictateUseCase(
        settings = settings,
        probe = probe,
        localEngine = local,
        serverEngine = server,
        serverFormatter = formatter,
        wavEncoder = wavEncoder,
        clock = clock,
        ids = ids,
        localFormatter = localFormatter,
    )

    private fun assertFailedCleanly(result: DictationResult, thrownClass: String) {
        assertTrue("expected a failure, got $result", result is DictationResult.Failure)
        result as DictationResult.Failure
        assertEquals(SttError.OTHER, result.error)
        assertEquals("the detail is the fixed sentence", "The speech could not be converted.", result.detail)
        assertFalse("the exception message leaked into the detail", result.detail!!.contains("SECRET"))
        assertFalse("the exception class name leaked into the detail", result.detail.contains(thrownClass))
        assertEquals(DictationState.ERROR, result.session.state)
        assertEquals(SttError.OTHER, result.session.lastError)
        assertEquals("the caller can carry on from the error", DictationState.IDLE, result.session.cancel().state)
    }

    @Test
    fun `a settings store that throws is a failure and no engine is asked`() {
        val result = useCase(settings = ThrowingSettings(boom())).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalStateException")
        assertEquals(0, local.callCount)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a connectivity probe that throws is a failure`() {
        val useCase = useCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.AUTO)),
            probe = ThrowingProbe(boom()),
        )

        assertFailedCleanly(useCase.dictate(recording(), audio), "IllegalStateException")
        assertEquals(0, local.callCount)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a wav encoder that throws is a failure`() {
        val result = useCase(wavEncoder = ThrowingWav(boom())).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalStateException")
        assertEquals(0, local.callCount)
    }

    @Test
    fun `a wav encoder that returns nothing is a failure, not an escaping exception`() {
        val empty = object : WavEncoder {
            override fun encode(pcm: FloatArray): ByteArray = ByteArray(0)
        }

        val result = useCase(wavEncoder = empty).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalArgumentException")
        assertEquals(0, local.callCount)
    }

    @Test
    fun `a cloud formatter that throws on the server path is a failure`() {
        val useCase = useCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.SERVER)),
            formatter = ThrowingFormatter(boom()),
        )

        assertFailedCleanly(useCase.dictate(recording(), audio), "IllegalStateException")
    }

    @Test
    fun `an on-device formatter that throws on the phone is a failure`() {
        val useCase = useCase(localFormatter = ThrowingFormatter(boom()))

        assertFailedCleanly(useCase.dictate(recording(), audio), "IllegalStateException")
    }

    @Test
    fun `an id source that throws is a failure`() {
        val result = useCase(ids = ThrowingIds(boom())).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalStateException")
    }

    @Test
    fun `an id source that returns a blank id is a failure, not an escaping exception`() {
        val blank = IdSource { "  " }

        val result = useCase(ids = blank).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalArgumentException")
    }

    @Test
    fun `a clock that throws is a failure`() {
        val result = useCase(clock = ThrowingClock(boom())).dictate(recording(), audio)

        assertFailedCleanly(result, "IllegalStateException")
    }

    @Test
    fun `an engine that throws is still a failure`() {
        val throwing = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = ThrowingSttEngine(secret),
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        )

        assertFailedCleanly(throwing.dictate(recording(), audio), "IllegalStateException")
    }

    @Test
    fun `working adapters still produce a success`() {
        val result = useCase().dictate(recording(), audio)

        assertTrue("expected a success, got $result", result is DictationResult.Success)
    }

    @Test
    fun `a move the state machine refuses is still a wiring bug and still throws`() {
        try {
            useCase().dictate(DictationSession(), audio)
            fail("expected dictating from idle to be refused")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!, expected.message!!.contains("IDLE -> TRANSCRIBING"))
        }
    }

    /** Every adapter the dictation reaches, each made to throw [error]. */
    private fun adaptersThrowing(error: Throwable): Map<String, DictateUseCase> = mapOf(
        "settings store" to useCase(settings = ThrowingSettings(error)),
        "connectivity probe" to useCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.AUTO)),
            probe = ThrowingProbe(error),
        ),
        "wav encoder" to useCase(wavEncoder = ThrowingWav(error)),
        "server formatter" to useCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.SERVER)),
            formatter = ThrowingFormatter(error),
        ),
        "on-device formatter" to useCase(localFormatter = ThrowingFormatter(error)),
        "id source" to useCase(ids = ThrowingIds(error)),
        "clock" to useCase(clock = ThrowingClock(error)),
        "engine" to DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(reachable = true),
            localEngine = ThrowingEngine(error),
            serverEngine = server,
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        ),
    )

    @Test
    fun `a checked exception from an adapter is a failure and leaves no stranded session`() {
        adaptersThrowing(java.io.IOException(secret)).forEach { (adapter, useCase) ->
            try {
                assertFailedCleanly(useCase.dictate(recording(), audio), "IOException")
                assertFalse("$adapter: an ordinary failure must not set the interrupt flag", Thread.currentThread().isInterrupted)
            } catch (e: AssertionError) {
                throw AssertionError("$adapter: ${e.message}", e)
            }
        }
    }

    @Test
    fun `an interruption from an adapter is a failure and the interrupt flag is restored`() {
        try {
            val useCase = useCase(wavEncoder = ThrowingWav(InterruptedException(secret)))

            val result = useCase.dictate(recording(), audio)

            assertFailedCleanly(result, "InterruptedException")
            assertTrue("the interrupt flag was swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }

    @Test
    fun `an interruption from the speech engine is a failure and the interrupt flag is restored`() {
        // The engine call has its own catch for runtime failures. An interruption is a checked
        // exception, so it must get past that catch and be handled where the flag is restored.
        try {
            val useCase = adaptersThrowing(InterruptedException(secret)).getValue("engine")

            val result = useCase.dictate(recording(), audio)

            assertFailedCleanly(result, "InterruptedException")
            assertTrue("the interrupt flag was swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // leave the test thread clean for the next test
        }
    }

    @Test
    fun `an error from an adapter is not a failure and still propagates`() {
        adaptersThrowing(Meltdown()).forEach { (adapter, useCase) ->
            try {
                useCase.dictate(recording(), audio)
                fail("$adapter: expected the Error to propagate")
            } catch (expected: Meltdown) {
                // propagated, as it should
            }
        }
    }
}
