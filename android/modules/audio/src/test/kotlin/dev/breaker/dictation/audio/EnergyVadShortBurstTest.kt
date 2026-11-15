package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speech burst shorter than the detector's default minimum is not a dictation.
 *
 * A burst has to pass two gates: [EnergyVad.MIN_RUN_FRAMES] consecutive frames to
 * open a take (60 ms at the default window), and then the span from the first to
 * the last speech frame has to reach the default minimum speech length (150 ms).
 * A burst of three to seven frames passes the first gate and is stopped only by
 * the second. A 40 ms blip never reaches the second gate, so it cannot show it;
 * these fixtures sit between the two (100 ms) and above both (200 ms), with the
 * default detector.
 */
class EnergyVadShortBurstTest {

    private val vad = EnergyVad()

    @Test
    fun `a 100 ms burst of speech under the default minimum removes the whole take`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 100, 1_000)
        val burstFrames = AudioSignals.speech(1_600).size / vad.frameSize
        assertTrue(
            "the burst is long enough to open a take, so only the minimum can refuse it",
            burstFrames >= EnergyVad.MIN_RUN_FRAMES,
        )

        val result = vad.trim(pcm)

        assertFalse(
            "100 ms of speech is under the 150 ms default minimum, so it is no dictation",
            result.hasSpeech,
        )
        assertEquals("nothing of a refused take is kept", 0, result.pcm.size)
    }

    @Test
    fun `a 200 ms burst of speech over the default minimum is kept`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 200, 1_000)

        val result = vad.trim(pcm)

        assertTrue(
            "200 ms of speech clears the default minimum, so it is a dictation",
            result.hasSpeech,
        )
        assertTrue(
            "the kept audio is the burst plus its padding, not nothing",
            result.pcm.isNotEmpty(),
        )
    }
}
