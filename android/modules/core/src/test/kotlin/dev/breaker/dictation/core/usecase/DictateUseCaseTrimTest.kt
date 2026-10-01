package dev.breaker.dictation.core.usecase

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.testing.FakeAudioSource
import dev.breaker.dictation.core.testing.FakeWavEncoder
import dev.breaker.dictation.core.testing.FixedClock
import dev.breaker.dictation.core.testing.InMemorySettingsStore
import dev.breaker.dictation.core.testing.PassThroughFormatter
import dev.breaker.dictation.core.testing.RecordingSttEngine
import dev.breaker.dictation.core.testing.ScriptedConnectivityProbe
import dev.breaker.dictation.core.testing.SequentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The trim offset handed to [DictateUseCase.stopCapture] is where the send
 * phrase began, so it can be anywhere from the start of the capture to (and
 * past) its end, but never before it. A negative offset is a caller bug; it is
 * refused before it can cost the user their audio.
 */
class DictateUseCaseTrimTest {
    private val engine = RecordingSttEngine.succeeding("hello world")

    private val useCase = DictateUseCase(
        settings = InMemorySettingsStore(AppSettings(mode = SttMode.LOCAL)),
        probe = ScriptedConnectivityProbe(reachable = true),
        localEngine = engine,
        serverEngine = RecordingSttEngine.succeeding("unused"),
        serverFormatter = PassThroughFormatter,
        wavEncoder = FakeWavEncoder,
        clock = FixedClock(),
        ids = SequentialIds(),
        localFormatter = PassThroughFormatter,
    )

    private fun capturing(audio: FakeAudioSource): DictationSession {
        val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
        audio.emitTone(1_000)
        return session
    }

    @Test
    fun `a negative trim offset is refused before the source is stopped or any audio is lost`() {
        val audio = FakeAudioSource()
        val session = capturing(audio)

        try {
            useCase.stopCapture(session, audio, trimBeforeMs = -1)
            fail("expected a negative trim offset to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("negative"))
            assertTrue(expected.message!!, expected.message!!.contains("-1"))
        }

        assertEquals("the refusal must come before the source is stopped", 0, audio.stopCount)
        assertTrue("the source must still be capturing", audio.isRunning)
        assertEquals("nothing may be transcribed on a refusal", 0, engine.callCount)

        // The buffer is intact, so the caller can try again with a good offset.
        val retried = useCase.stopCapture(session, audio) as DictationResult.Success
        assertEquals(1_000L, retried.transcription.durationMs)
        assertEquals(1, audio.stopCount)
    }

    @Test
    fun `any negative offset is refused, however large`() {
        listOf(-1L, -1_000L, Long.MIN_VALUE).forEach { negative ->
            val audio = FakeAudioSource()
            val session = capturing(audio)
            try {
                useCase.stopCapture(session, audio, trimBeforeMs = negative)
                fail("expected $negative to be refused")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!, expected.message!!.contains("$negative"))
            }
            assertEquals("stopped the source for $negative", 0, audio.stopCount)
        }
    }

    @Test
    fun `zero is a valid offset and means the whole capture was the phrase`() {
        val audio = FakeAudioSource()
        val session = capturing(audio)

        val result = useCase.stopCapture(session, audio, trimBeforeMs = 0)

        assertTrue("expected a failure, got $result", result is DictationResult.Failure)
        assertEquals(1, audio.stopCount)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `an offset at or past the end of the capture keeps the whole capture, however large`() {
        // 1_000 ms is 16_000 samples. Turning an offset into a sample count multiplies it by the
        // sample rate, and that product wraps for offsets above Long.MAX_VALUE / 16_000, so the
        // values around that threshold are the ones that matter. Just past it the product goes
        // negative and no audio is kept; 1_152_921_504_606_848 times 16_000 is 2^64 + 16_384, which
        // wraps to 16_384 and would silently keep 16 samples of a one-second capture.
        val threshold = Long.MAX_VALUE / AudioFormat.SAMPLE_RATE_HZ
        val wrapsToSixteenSamples = 1_152_921_504_606_848L
        val offsets = listOf(
            1_000L,
            1_001L,
            Int.MAX_VALUE.toLong(),
            threshold - 1,
            threshold,
            threshold + 1,
            1L shl 59,
            wrapsToSixteenSamples,
            1L shl 60,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE,
        )

        // Every offset is tried and every wrong answer reported, so one failure cannot hide another.
        val wrong = offsets.mapNotNull { offset ->
            val audio = FakeAudioSource()
            val session = capturing(audio)
            val before = engine.requests.size

            val result = useCase.stopCapture(session, audio, trimBeforeMs = offset)

            val kept = engine.requests.drop(before).singleOrNull()?.pcm?.size
            when {
                result !is DictationResult.Success -> "offset $offset: expected a success, got $result"
                kept != 16_000 -> "offset $offset: $kept of 16000 samples reached the engine"
                else -> null
            }
        }

        assertTrue("an offset past the end must keep the whole capture:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `an offset inside the capture still cuts to the sample`() {
        // The clamp that stops the overflow must not change any offset that was already fine.
        val cases = mapOf(0L to 0, 1L to 16, 250L to 4_000, 500L to 8_000, 999L to 15_984)

        cases.forEach { (offset, expectedSamples) ->
            val audio = FakeAudioSource()
            val session = capturing(audio)

            val result = useCase.stopCapture(session, audio, trimBeforeMs = offset)

            if (expectedSamples == 0) {
                assertTrue("offset $offset: expected a failure, got $result", result is DictationResult.Failure)
            } else {
                assertTrue("offset $offset: expected a success, got $result", result is DictationResult.Success)
                assertEquals("offset $offset", expectedSamples, engine.requests.last().pcm.size)
            }
        }
    }

    @Test
    fun `an offset in the last partial millisecond of the capture cuts where the sample rate says`() {
        // 16_001 samples is a little over 1_000 ms. An offset of 1_000 ms is sample 16_000, so the
        // last sample lies after the phrase began and is dropped; 1_001 ms is past the end.
        val cases = mapOf(1_000L to 16_000, 1_001L to 16_001)

        cases.forEach { (offset, expectedSamples) ->
            val audio = FakeAudioSource()
            val session = useCase.startCapture(DictationSession().arm().startRecording(), audio)
            audio.emit(FloatArray(16_001) { 0.5f })

            val result = useCase.stopCapture(session, audio, trimBeforeMs = offset)

            assertTrue("offset $offset: expected a success, got $result", result is DictationResult.Success)
            assertEquals("offset $offset", expectedSamples, engine.requests.last().pcm.size)
        }
    }
}
