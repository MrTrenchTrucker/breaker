package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor's climb, held as a rate in decibels per second while the sample
 * rate varies.
 *
 * The floor follows the quietest recent frames and creeps up towards louder ones,
 * so a passage that would otherwise inflate it has to be outrun frame by frame.
 * How fast it creeps is a wall-clock property — 25 dB/s is 0.5 dB on a 20 ms
 * window and 0.167 dB on a 6.67 ms one — so stating it per frame fixes it to
 * whichever rate the frame count was measured at, and at 48 kHz the same 0.5 dB
 * is a floor that climbs three times as fast and learns a passing truck as the
 * room.
 *
 * The measurement is the time a fixed climb takes: the floor is primed low and
 * then fed steady audio, and the elapsed milliseconds until that audio stops
 * reading as speech is the rate, whatever the window length happens to be. The
 * room is primed far enough below the speech that the margin is the only thing
 * the two are separated by, and the ceiling is left out of it.
 */
class EnergyVadFloorRiseRateTest {

    private companion object {
        val RATES_HZ = listOf(8_000, 16_000, 44_100, 48_000)

        /**
         * The room the floor is primed onto, and the steady audio that then walks
         * it upwards.
         *
         * 40 dB apart, so the floor has 32 dB to climb before the margin closes on
         * the audio: the level has to reach the floor plus the margin, so it takes
         * 32 dB of climb, and at the intended 25 dB/s that is 1.28 s. A narrower
         * spread would make the measurement shorter than the quantisation of the
         * window at 44.1 kHz, and a wider one would reach the ceiling and stop
         * measuring the rate at all.
         */
        const val ROOM_DB = -80.0
        const val STEADY_DB = -40.0

        /** Frames of room to prime the floor, so the first speech frame is not the first frame seen. */
        const val PRIME_FRAMES = 5

        /**
         * The climb the detector owes, in milliseconds.
         *
         * There is no fixed tolerance: the climb is only ever measured in whole
         * windows, so the honest band is one window of this rate's own length
         * either side of the expected time. That admits the four true answers
         * (1280 ms at 8 kHz and 16 kHz, 1284 ms at 44.1 kHz, 1280 ms at 48 kHz)
         * while staying two orders of magnitude tighter than the defect it
         * catches, whose windows at these rates run from 426 ms to 2560 ms.
         */
        const val EXPECTED_MS = 1280L

        /** Long enough that no rate can run past its answer before the loop stops. */
        const val MAX_FRAMES = 4000
    }

    @Test
    fun `the floor climbs at the same decibels per second at every sample rate`() {
        // Every rate is measured before any is asserted, so a failure can name all
        // four: stopping at the first one hides whether the rates disagree.
        val climbsMs = RATES_HZ.associateWith { elapsedMsUntilSteadyAudioStops(it) }
        val everyRate = RATES_HZ.joinToString(", ") { "${it} Hz ${climbsMs.getValue(it)} ms" }

        for (rateHz in RATES_HZ) {
            val elapsedMs = climbsMs.getValue(rateHz)
            val toleranceMs = windowCeilMs(rateHz)

            assertTrue(
                "the floor took $elapsedMs ms to climb its 32 dB at $rateHz Hz, " +
                    "outside $EXPECTED_MS ms +/-$toleranceMs ms (climbs: $everyRate); " +
                    "a rise stated per frame climbs at the speed of whichever rate " +
                    "it was measured at, and a window of ${windowMs(rateHz)} ms here " +
                    "is not the ${windowMs(16_000)} ms the constant was chosen for",
                elapsedMs in (EXPECTED_MS - toleranceMs)..(EXPECTED_MS + toleranceMs),
            )
        }
    }

    /**
     * Milliseconds of steady audio fed before it stops reading as speech.
     *
     * The count includes the window that ended the climb. That window is a whole
     * window of audio at the given rate, and the floor finished climbing inside
     * it, so leaving it out reports the climb as having finished one window
     * earlier than it did.
     */
    private fun elapsedMsUntilSteadyAudioStops(rateHz: Int): Long {
        val detector = EnergyVad(sampleRateHz = rateHz)
        val room = frameAtLevelDb(ROOM_DB, rateHz)
        val steady = frameAtLevelDb(STEADY_DB, rateHz)

        repeat(PRIME_FRAMES) { detector.isSpeech(room) }

        var windows = 0
        var stillSpeech = true
        while (windows < MAX_FRAMES && stillSpeech) {
            stillSpeech = detector.isSpeech(steady)
            windows++
        }

        assertTrue(
            "steady audio at $STEADY_DB dB still read as speech after $MAX_FRAMES " +
                "frames at $rateHz Hz, so the floor never reached it and the climb " +
                "is not being measured at all",
            !stillSpeech,
        )
        return elapsedMs(windows, rateHz)
    }

    /**
     * Milliseconds of audio in [windows] whole windows at this rate.
     *
     * The multiply has to precede the divide. `320000 / rateHz` is not a whole
     * number of milliseconds at most rates — it is 7 at 44.1 kHz and 6 at 48 kHz
     * where the window is really 7.2562 ms and 6.6667 ms — so dividing first
     * charges that shortfall on every window and it accumulates with the count,
     * which at 48 kHz is a sixth of the total and lands the reported time far
     * outside a band one window wide. Scaling first leaves a single rounding on
     * the whole sum, bounded by one millisecond.
     *
     * [windows] is capped by [MAX_FRAMES], so the numerator is at most
     * `4000 * 320 * 1000`, which is well inside a [Long] and is kept in [Long]
     * throughout: as [Int] the product overflows.
     */
    private fun elapsedMs(windows: Int, rateHz: Int): Long =
        windows.toLong() * EnergyVad.DEFAULT_FRAME_SAMPLES * 1000L / rateHz

    /** One analysis window at this rate, in milliseconds, exactly and unrounded. */
    private fun windowMs(rateHz: Int): Double =
        EnergyVad.DEFAULT_FRAME_SAMPLES * 1000.0 / rateHz

    /**
     * One analysis window at this rate, in whole milliseconds, rounded up.
     *
     * The band has to admit a whole window of quantisation, and rounding up keeps
     * it that side: a band narrower than the window it exists to absorb would
     * exclude the very answers it is there for.
     */
    private fun windowCeilMs(rateHz: Int): Long {
        val exactWindowMs = EnergyVad.DEFAULT_FRAME_SAMPLES * 1000L
        return (exactWindowMs + rateHz - 1) / rateHz
    }

    /**
     * One analysis window of steady audio at exactly [levelDb] dBFS.
     *
     * A tone of amplitude `a` has an RMS of `a / sqrt(2)`, so the amplitude for a
     * level is `10^(dB/20) * sqrt(2)`, which makes the detector's RMS exactly
     * `10^(dB/20)` and its reported level exactly [levelDb]. The detector measures
     * RMS, so this is the level it sees, and the whole measurement rests on those
     * two numbers being exact rather than near.
     */
    private fun frameAtLevelDb(levelDb: Double, rateHz: Int): FloatArray {
        val amplitude = Math.pow(10.0, levelDb / 20.0).toFloat() * Math.sqrt(2.0).toFloat()
        return AudioSignals.tone(
            samples = EnergyVad.DEFAULT_FRAME_SAMPLES,
            hz = 220.0,
            amplitude = amplitude,
            // Without the rate the tone is generated at 16 kHz spacing, so at 48 kHz
            // it is a 660 Hz tone laid down at a 220 Hz period — still a tone, and
            // still the right RMS, but not the signal the frequency asked for.
            sampleRateHz = rateHz.toDouble(),
        )
    }
}
