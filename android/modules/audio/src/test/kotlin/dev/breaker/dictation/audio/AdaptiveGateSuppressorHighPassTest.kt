package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The high half of [AdaptiveGateSuppressor]: the "rumble high-pass".
 *
 * [AdaptiveGateSuppressorTest] measures the GATE by dividing the output by
 * [AdaptiveGateSuppressor.highPassCoefficient], so it sees the gate gain alone and
 * never the high-pass. This test measures the high-pass alone, by holding the gate
 * open (a loud tone) so the applied gain is the high-pass response.
 *
 * ### The promise under test
 *
 * The class KDoc (AdaptiveGateSuppressor.kt) describes the filter as "a DC blocker,
 * a rumble high-pass, and a downward gate," and the [highPassHz] parameter is
 * documented as "Below this frequency, in hertz, the signal is vehicle rumble, not
 * speech" with a default of 80 Hz. A rumble high-pass at 80 Hz has its corner at
 * 80 Hz: rumble below it is cut, speech above it passes at roughly unity.
 *
 * ### The population
 *
 * A steady tone at a stated frequency, fed into a suppressor whose gate is held
 * open, so the output peak over the input peak is the high-pass gain at that
 * frequency. Two frequencies on either side of the promised 80 Hz corner, and one
 * in the speech band.
 *
 * ### What the code actually does
 *
 * The one-pole pole is the hard-coded `HIGH_PASS_POLE = 0.995`, which puts the
 * corner at about 13 Hz, not 80 Hz. The [highPassHz] parameter does not set the
 * corner; it sets the passband GAIN, `highPassCoefficient(16000, 80) = 0.0305`,
 * which is a -30 dB scale on the whole output. So rumble between 13 Hz and 80 Hz
 * is not separated from speech (it passes at the same -30 dB), and in-band speech
 * is delivered at -30 dB, an attenuation the KDoc never states.
 */
class AdaptiveGateSuppressorHighPassTest {

    private val sampleRate = 16_000
    private val frameSamples = 800 // 50 ms, past the high-pass settling

    /** A quiet 1 kHz frame, used to prime the floor low so a loud tone opens the gate. */
    private val quiet = QUIET

    /** A sine of constant absolute amplitude [peak] at [hz]. */
    private fun toneFrame(hz: Double, peak: Float): FloatArray =
        FloatArray(frameSamples) {
            (peak * sin(2.0 * Math.PI * hz * it / sampleRate)).toFloat()
        }

    /** A suppressor whose floor has settled on a quiet cab, so a loud tone opens the gate. */
    private fun primed(): AdaptiveGateSuppressor {
        val s = AdaptiveGateSuppressor(sampleRateHz = sampleRate)
        repeat(4) { s.process(toneFrame(1000.0, quiet)) }
        return s
    }

    /**
     * The gain the suppressor applies to a steady [hz] tone of [peak] amplitude,
     * with the gate held open by the tone itself. One frame opens the gate and
     * settles the high-pass; the second is the steady-state measurement.
     */
    private fun toneGain(suppressor: AdaptiveGateSuppressor, hz: Double, peak: Float): Float {
        suppressor.process(toneFrame(hz, peak))
        val out = suppressor.process(toneFrame(hz, peak))
        return out.maxOf { abs(it) } / peak
    }

    @Test
    fun `an in-band speech tone passes at roughly unity`() {
        // 440 Hz sits well inside the speech band, above the promised 80 Hz corner.
        // A rumble high-pass passes it at roughly unity, so the output peak is
        // within a factor of two of the input peak.
        val gain = toneGain(primed(), 440.0, 0.5f)

        assertTrue(
            "a 440 Hz tone (in the speech band, above the 80 Hz corner) must pass " +
                "at roughly unity, but the high-pass gain was $gain; the KDoc " +
                "describes a rumble high-pass, and this one scales the whole " +
                "output by highPassCoefficient(16000, 80) = " +
                "${AdaptiveGateSuppressor.highPassCoefficient(sampleRate, 80f)} " +
                "(-30 dB), so in-band speech is delivered 30 dB quieter than " +
                "promised",
            gain > 0.5f,
        )
    }

    @Test
    fun `the corner sits at the documented 80 hz`() {
        // A corner at 80 Hz separates 40 Hz (rumble, below) from 440 Hz (speech,
        // above): the speech tone passes at well over a factor of the rumble
        // tone. A corner at ~13 Hz (the hard-coded pole) puts both in the
        // passband, so they come out at the same gain.
        //
        // The 1.5x margin is chosen against a proper 80 Hz one-pole high-pass
        // with unity passband, where gain(440) ~ 0.98 and gain(40) ~ 0.45, a
        // ratio of ~2.2; a 1.5x floor leaves the test clear of float noise
        // while still far from the ~1.0 ratio the hard-coded pole produces.
        val low = toneGain(primed(), 40.0, 0.5f)
        val high = toneGain(primed(), 440.0, 0.5f)

        assertTrue(
            "an 80 Hz corner must pass 440 Hz at well over 1.5x the gain of 40 Hz, " +
                "but 40 Hz read $low and 440 Hz read $high (ratio " +
                "${high / low}); the corner is set by the hard-coded " +
                "HIGH_PASS_POLE = 0.995 (about 13 Hz), not by highPassHz = 80, so " +
                "rumble between 13 Hz and 80 Hz is not separated from speech",
            high > 1.5f * low,
        )
    }

    private companion object {
        /** A quiet cab: 0.01 sits inside [-1, 1] with room for a margin above it. */
        const val QUIET = 0.01f
    }
}
