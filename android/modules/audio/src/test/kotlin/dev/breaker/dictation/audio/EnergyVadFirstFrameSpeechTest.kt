package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A capture that opens straight into loud speech. The first frame primes the noise floor, so a
 * normal first frame is never speech; but the floor is held under a ceiling, and a first frame
 * louder than that ceiling is speech at once. The run of speech frames that opens the take then
 * starts at frame zero, and the take is reported as starting at zero, not before it.
 */
class EnergyVadFirstFrameSpeechTest {
    private val vad = EnergyVad()

    /** Ten loud frames, far above the noise ceiling, then ten silent frames. */
    private fun loudOpening(): FloatArray {
        val frame = vad.frameSize
        val out = FloatArray(frame * 20)
        for (i in 0 until frame * 10) out[i] = 0.5f
        return out
    }

    @Test
    fun `a capture that opens with speech above the noise ceiling starts the take at zero`() {
        val result = vad.trim(loudOpening())

        assertTrue("the opening speech is a take", result.hasSpeech)
        assertEquals("the take starts at the first sample, not before it", 0L, result.speechStartMs)
        assertEquals("nothing is cut off the front", 0L, result.leadingSilenceMs)
    }

    @Test
    fun `the same opening keeps its first loud frame`() {
        val result = vad.trim(loudOpening())

        assertEquals("the kept audio starts with the first loud sample", 0.5f, result.pcm[0], 0f)
    }
}
