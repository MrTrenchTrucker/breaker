package dev.breaker.dictation.core.model

import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import dev.breaker.dictation.core.usecase.DictateUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio shape every engine is fed is 16 kHz, one channel. The numbers are
 * written out here rather than read back from [AudioFormat], because a test that
 * compares the constant with itself, or with a copy of itself, passes whatever
 * the constant is set to.
 */
class AudioFormatTest {
    @Test
    fun `the audio shape is 16 kHz and one channel`() {
        assertEquals(16_000, AudioFormat.SAMPLE_RATE_HZ)
        assertEquals(1, AudioFormat.CHANNEL_COUNT)
    }

    @Test
    fun `the audio port restates the same rate`() {
        assertEquals(16_000, AudioSource.SAMPLE_RATE_HZ)
    }

    @Test
    fun `sixteen thousand samples are one second of a request`() {
        val request = SttRequest(
            pcm = FloatArray(16_000),
            wavBytes = byteArrayOf(1),
            model = "small",
            language = "en",
        )

        assertEquals(16_000, request.sampleRateHz)
        assertEquals(1_000L, request.durationMs)
    }

    @Test
    fun `sixteen thousand captured samples are recorded as one second of dictation`() {
        val engine = RecordingSttEngine.succeeding("hello")
        val useCase = DictateUseCase(
            settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = ScriptedConnectivityProbe(reachable = false),
            localEngine = engine,
            serverEngine = RecordingSttEngine.succeeding("unused"),
            serverFormatter = PassThroughFormatter,
            wavEncoder = FakeWavEncoder,
            clock = FixedClock(),
            ids = SequentialIds(),
            localFormatter = PassThroughFormatter,
        )

        val result = useCase.dictate(DictationSession().arm().startRecording(), FloatArray(16_000) { 0.1f })

        assertTrue(result is DictationResult.Success)
        result as DictationResult.Success
        assertEquals(1_000L, result.transcription.durationMs)
        assertEquals(1_000L, engine.requests.single().durationMs)
        assertEquals(16_000, engine.requests.single().pcm.size)
    }

    @Test
    fun `the duration recorded on a transcription is the duration of the request the engine received`() {
        // (samples, milliseconds at 16 kHz), the milliseconds rounded down.
        listOf(1 to 0L, 160 to 10L, 16_000 to 1_000L, 16_001 to 1_000L, 24_000 to 1_500L).forEach { (samples, ms) ->
            val engine = RecordingSttEngine.succeeding("hello")
            val useCase = DictateUseCase(
                settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
                probe = ScriptedConnectivityProbe(reachable = false),
                localEngine = engine,
                serverEngine = RecordingSttEngine.succeeding("unused"),
                serverFormatter = PassThroughFormatter,
                wavEncoder = FakeWavEncoder,
                clock = FixedClock(),
                ids = SequentialIds(),
                localFormatter = PassThroughFormatter,
            )

            val result = useCase.dictate(DictationSession().arm().startRecording(), FloatArray(samples) { 0.1f })

            assertTrue(result is DictationResult.Success)
            result as DictationResult.Success
            assertEquals("$samples samples: the transcription", ms, result.transcription.durationMs)
            assertEquals("$samples samples: the request", ms, engine.requests.single().durationMs)
        }
    }
}
