package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The high-pass corner of [AdaptiveGateSuppressor] at every capture rate the
 * module is asked to support.
 *
 * ### The promise under test
 *
 * [AdaptiveGateSuppressor.highPassHz] is documented as "Below this frequency,
 * in hertz, the signal is vehicle rumble, not speech" and defaults to 80 Hz. A
 * frequency stated in hertz has to mean the same hertz whether the stream is
 * sampled at 8 kHz or at 48 kHz: the parameter is a promise about the sound, not
 * about the buffer.
 *
 * ### Why a single rate cannot show this
 *
 * The one-pole high-pass rolls off at `-ln(pole)` radians per SAMPLE, so a pole
 * held constant while the sample rate changes moves the corner with it:
 * `-ln(pole) * sampleRate / 2*pi`. A corner that is correct at one rate is
 * therefore only accidentally correct at the others, and a test that measures
 * one rate cannot distinguish "the pole was derived from the rate" from "the pole
 * was a number that happened to suit this rate".
 *
 * The rates below are the ones the capture path can be handed, and they span a
 * factor of six, which is enough for a fixed pole to be off by more than an
 * octave between the ends of the range.
 *
 * ### The population
 *
 * A steady tone at a stated frequency, fed into a suppressor whose gate is held
 * open by the tone itself, so the ratio of the output peak to the input peak is
 * the high-pass gain at that frequency and nothing else.
 *
 * ### Why the measurement is NOT divided by the passband coefficient
 *
 * [AdaptiveGateSuppressorTest] divides the output by
 * [AdaptiveGateSuppressor.highPassCoefficient] because it wants the GATE. That
 * coefficient is `(1 + pole) / 2`, a function of the pole, so dividing by it
 * removes the pole's own passband scaling and leaves the raw difference
 * equation's asymptote `2 / (1 + pole)`, a number that moves by about 2% across
 * these rates while the corner moves by an octave. The gate itself is a frame
 * amplitude ratio and is frequency-flat, so a quotient that emphasises it can
 * only dilute a statement about the filter. Reading the raw ratio instead keeps
 * the assertion on the quantity the KDoc promises: unity in the passband.
 *
 * ### Frame length
 *
 * Every rate is measured over the same number of SAMPLES, not the same number of
 * milliseconds. The filter's time constant is `1 / (-ln(pole))` samples, so it
 * shrinks in wall-clock terms as the rate rises and is invariant in samples; a
 * millisecond-sized frame would quietly measure a shorter settling window at
 * 48 kHz than at 8 kHz and the two rates would not be comparable.
 */
class AdaptiveGateSuppressorHighPassRateTest {

    /**
     * The high-pass gain the suppressor applies to a steady [hz] tone at
     * [sampleRateHz], with the gate held open by the tone.
     *
     * Only the second half of the measured frame is read. Every frame restarts
     * the tone at phase zero while the filter state carries on from the previous
     * frame, so each boundary is a small discontinuity that rings for a time
     * constant; half a frame is more than twenty of those at the slowest rate
     * here, and the peak of a frame that includes the ringing would report the
     * transient rather than the steady state.
     */
    private fun gainAt(sampleRateHz: Int, hz: Double): Float {
        val suppressor = AdaptiveGateSuppressor(sampleRateHz = sampleRateHz)
        val rate = sampleRateHz.toDouble()
        repeat(PRIMING_FRAMES) {
            suppressor.process(AudioSignals.tone(FRAME_SAMPLES, PRIMING_HZ, QUIET, sampleRateHz = rate))
        }
        // The first tone frame opens the gate and settles the filter; the second
        // is the steady state.
        suppressor.process(AudioSignals.tone(FRAME_SAMPLES, hz, TONE_PEAK, sampleRateHz = rate))
        val out = suppressor.process(AudioSignals.tone(FRAME_SAMPLES, hz, TONE_PEAK, sampleRateHz = rate))
        val steady = out.copyOfRange(FRAME_SAMPLES / 2, FRAME_SAMPLES)
        return AudioSignals.peak(steady) / TONE_PEAK
    }

    /**
     * The whole contract at one rate: a corner at the documented frequency, a
     * hard cut well below it, and an untouched passband above it.
     */
    private fun assertCornerIsRateIndependent(sampleRateHz: Int) {
        val atCorner = gainAt(sampleRateHz, CORNER_HZ)
        val belowCorner = gainAt(sampleRateHz, HALF_CORNER_HZ)
        val speech = gainAt(sampleRateHz, SPEECH_HZ)
        val rumble = gainAt(sampleRateHz, RUMBLE_HZ)

        assertTrue(
            "at $sampleRateHz Hz the corner must sit at ${CORNER_HZ.toInt()} Hz, " +
                "so ${CORNER_HZ.toInt()} Hz must read about -3 dB, but it read $atCorner",
            atCorner in CORNER_DB_BAND,
        )
        assertTrue(
            "at $sampleRateHz Hz a ${CORNER_HZ.toInt()} Hz corner must pass " +
                "${SPEECH_HZ.toInt()} Hz at well over 1.5x the gain of " +
                "${HALF_CORNER_HZ.toInt()} Hz, but ${HALF_CORNER_HZ.toInt()} Hz read " +
                "$belowCorner and ${SPEECH_HZ.toInt()} Hz read $speech (ratio " +
                "${speech / belowCorner}); a pole held constant across rates moves " +
                "the corner with the sample rate, so the filter no longer cuts where " +
                "the parameter says it does",
            speech > 1.5f * belowCorner,
        )
        assertTrue(
            "at $sampleRateHz Hz, ${RUMBLE_HZ.toInt()} Hz is a quarter of the corner " +
                "and must be cut hard, but it read $rumble; a shallow rolloff leaves " +
                "vehicle rumble in the signal ahead of the recogniser",
            rumble < RUMBLE_CEILING,
        )
        assertTrue(
            "at $sampleRateHz Hz the passband must not be muted, but " +
                "${SPEECH_HZ.toInt()} Hz read $speech",
            speech > SPEECH_FLOOR,
        )
    }

    @Test
    fun `the corner sits at the documented frequency at 8 kHz`() =
        assertCornerIsRateIndependent(8_000)

    @Test
    fun `the corner sits at the documented frequency at 16 kHz`() =
        assertCornerIsRateIndependent(16_000)

    @Test
    fun `the corner sits at the documented frequency at 44 point 1 kHz`() =
        assertCornerIsRateIndependent(44_100)

    @Test
    fun `the corner sits at the documented frequency at 48 kHz`() =
        assertCornerIsRateIndependent(48_000)

    @Test
    fun `the rolloff does not move with the sample rate`() {
        // The rate-independent statement, stated across rates rather than within
        // one: a corner measured in hertz reads the same everywhere. Reference is
        // the lowest rate, where a fixed pole is furthest from the corner the
        // parameter names.
        val reference = gainAt(8_000, HALF_CORNER_HZ)
        val others = listOf(16_000, 44_100, 48_000).map { it to gainAt(it, HALF_CORNER_HZ) }

        others.forEach { (sampleRateHz, gain) ->
            val drift = abs(gain - reference) / reference
            assertTrue(
                "the gain at ${HALF_CORNER_HZ.toInt()} Hz must be the same fraction " +
                    "of full scale at every capture rate: 8 kHz read $reference and " +
                    "$sampleRateHz Hz read $gain, a drift of " +
                    "${(drift * 100).toInt()}% against a $TOLERANCE_PCT% budget. The " +
                    "high-pass pole is derived from the sample rate, so this drift is " +
                    "what a pole held constant would look like: the corner sits at " +
                    "-ln(pole) * sampleRate / 2*pi and climbs with the rate",
                drift < TOLERANCE_PCT / 100.0,
            )
        }
    }

    private companion object {
        /**
         * Samples per frame at every rate.
         *
         * Fixed in samples rather than in milliseconds because the settling time
         * is a number of samples. The longest time constant a pole near this one
         * can have is a few hundred samples, so a frame of this size is tens of
         * time constants at 8 kHz and still tens of them at 48 kHz.
         */
        const val FRAME_SAMPLES = 4096

        /** Quiet frames fed before a tone, so the floor settles low and opens the gate. */
        const val PRIMING_FRAMES = 4

        /** A quiet cab: 0.01 sits inside [-1, 1] with room for a margin above it. */
        const val QUIET = 0.01f

        /** Loud enough to open the gate by a wide margin, so the gain read is the filter's. */
        const val TONE_PEAK = 0.5f

        /** The tone used to prime the floor low. */
        const val PRIMING_HZ = 1000.0

        /** The documented corner. */
        const val CORNER_HZ = 80.0

        /** Half the corner: the low side of the rolloff, which must already be cut. */
        const val HALF_CORNER_HZ = 40.0

        /** A quarter of the corner, deep enough that a one-pole rolloff is well down. */
        const val RUMBLE_HZ = 20.0

        /** Well inside the speech band and far above the corner. */
        const val SPEECH_HZ = 440.0

        /**
         * -3 dB at the corner, to a band rather than a point: the one-pole
         * asymptote is approached from just above the corner, so the reading sits
         * a hair under 0.7071 and the tolerance is on that side.
         */
        val CORNER_DB_BAND = 0.65f..0.75f

        /**
         * A quarter of the corner reads about -12 dB on a correctly cornered
         * one-pole high-pass; the budget leaves a third of that in hand and still
         * rejects a pole that never leaves the passband at this frequency.
         */
        const val RUMBLE_CEILING = 0.35f

        /** Roughly unity, so the rolloff above the corner is not a mute. */
        const val SPEECH_FLOOR = 0.8f

        /** How far the same frequency may read differently at two rates. */
        const val TOLERANCE_PCT = 5
    }
}
