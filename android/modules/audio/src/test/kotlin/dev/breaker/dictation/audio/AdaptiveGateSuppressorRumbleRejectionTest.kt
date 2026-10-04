package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The low side of the rumble high-pass: a frequency well BELOW the corner.
 *
 * [AdaptiveGateSuppressorHighPassTest] pins the corner by comparing 40 Hz
 * against 440 Hz, which shows the rolloff exists but not how hard it lands far
 * below the corner. A corner that is merely present still lets engine
 * rumble through, so the claim under test here is the depth: at 20 Hz the
 * suppressor must be cutting hard while the speech band is untouched.
 *
 * The measurement holds the gate open with a loud tone, so the gain read back
 * is the high-pass response at that frequency and nothing else.
 */
class AdaptiveGateSuppressorRumbleRejectionTest {

    private val sampleRate = 16_000
    private val frameSamples = 800 // 50 ms, past the high-pass settling
    private val tonePeak = 0.5f

    /**
     * The gain at [hz] for a suppressor whose gate is held open by the tone
     * itself. The second frame is the steady-state measurement; the first only
     * opens the gate and settles the filter.
     */
    private fun toneGain(hz: Double): Float {
        val suppressor = AdaptiveGateSuppressor(sampleRateHz = sampleRate)
        repeat(4) { suppressor.process(AudioSignals.tone(frameSamples, PRIMING_HZ, QUIET)) }
        suppressor.process(AudioSignals.tone(frameSamples, hz, tonePeak))
        val out = suppressor.process(AudioSignals.tone(frameSamples, hz, tonePeak))
        return AudioSignals.peak(out) / tonePeak
    }

    @Test
    fun `rumble far below the corner is cut hard while speech passes at unity`() {
        // 20 Hz is a quarter of the 80 Hz corner, where a one-pole rolloff is
        // already well into its stopband. Anything shallow here would pass cab
        // rumble straight through to the recogniser.
        val rumble = toneGain(RUMBLE_HZ)
        val speech = toneGain(SPEECH_HZ)

        assertTrue(
            "20 Hz is a quarter of the 80 Hz corner and must be cut hard, but " +
                "the high-pass gain was $rumble; a shallow rolloff leaves " +
                "vehicle rumble in the signal ahead of the recogniser",
            rumble < RUMBLE_CEILING,
        )
        assertTrue(
            "the speech band must be left alone by the rumble high-pass, but " +
                "440 Hz read $speech while 20 Hz read $rumble",
            speech > SPEECH_FLOOR,
        )
    }

    private companion object {
        /** A quiet cab, so a loud tone opens the gate. */
        const val QUIET = 0.01f

        /** Above the corner: a plain tone well inside the speech band. */
        const val SPEECH_HZ = 440.0

        /** A quarter of the default corner, where the rolloff is deep. */
        const val RUMBLE_HZ = 20.0

        /** The tone used to prime the floor low. */
        const val PRIMING_HZ = 1000.0

        /** More than a factor of two down, which a rolloff this deep clears. */
        const val RUMBLE_CEILING = 0.4f

        /** Roughly unity, so the rolloff above the corner is not a mute. */
        const val SPEECH_FLOOR = 0.8f
    }
}
