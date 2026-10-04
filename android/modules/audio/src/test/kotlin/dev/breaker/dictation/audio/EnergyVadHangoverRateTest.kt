package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hangover, held as a duration while the sample rate varies.
 *
 * The hangover is the pause the detector waits through after speech stops before
 * it decides the take has ended. As a duration it is the gap between words in a
 * sentence; as a frame count it is that only at the rate the count was chosen
 * for, because the same 320-sample window is 20 ms at 16 kHz and 6.67 ms at
 * 48 kHz — so ten frames is 200 ms at one rate and 67 ms at another.
 *
 * These tests measure the gap in wall-clock milliseconds, through `trim`, at
 * every rate. A detector that bridged the right *number* of frames at the wrong
 * length fails here; asserting frame counts instead would pass unchanged at
 * every rate and would say nothing at all.
 */
class EnergyVadHangoverRateTest {

    private companion object {
        val RATES_HZ = listOf(8_000, 16_000, 44_100, 48_000)

        /**
         * A gap well inside the hangover and a gap well beyond it: 160 ms and
         * 240 ms, either side of a 200 ms claim.
         *
         * Both are quantised up to whole analysis windows, so the fixture spans
         * 160–166.9 ms and 240–246.7 ms respectively across these four rates. The
         * assertions are written against the gap each rate actually produced, not
         * against the nominal figure, because a 6.67 ms window cannot hit 160 ms
         * exactly.
         */
        const val BRIDGED_GAP_MS = 160L
        const val CUT_GAP_MS = 240L

        /**
         * Frames of speech per word, well over [EnergyVad.MIN_RUN_FRAMES] so a
         * word is a word and not a knock.
         */
        const val SPEECH_FRAMES = 12

        /**
         * Room tone before the first word, and after the last.
         *
         * The floor primes itself onto the first frame it is fed, so a take has to
         * open on the room: one that opens on the voice primes its floor onto the
         * voice and then measures the voice against a threshold the voice set.
         *
         * Long enough that the trailing room outlasts the hangover at every rate,
         * so a detector that bridges the gap has room to bridge into and the
         * choice between bridging and cutting is the only thing being measured.
         */
        const val ROOM_FRAMES = 40
    }

    @Test
    fun `a gap inside the hangover is bridged at every sample rate`() {
        for (rateHz in RATES_HZ) {
            val (speechSpanMs, gapMs) = result(rateHz, BRIDGED_GAP_MS)

            assertTrue(
                "a ${gapMs} ms gap between two words was not bridged at $rateHz Hz, " +
                    "so the take was cut in the middle of a sentence; the hangover " +
                    "is a duration, and here it bridges less than $gapMs ms",
                speechSpanMs > wordSpanMs(rateHz),
            )
        }
    }

    @Test
    fun `a gap beyond the hangover is not bridged at any sample rate`() {
        for (rateHz in RATES_HZ) {
            val (speechSpanMs, gapMs) = result(rateHz, CUT_GAP_MS)

            // Never later than the first word ending: reaching past it means the
            // hangover bridged more than it was asked to, which is this same
            // defect pointing the other way rather than a separate failure.
            assertTrue(
                "a ${gapMs} ms gap was bridged at $rateHz Hz, so the hangover here " +
                    "reaches past the gap it was meant to stop at; speech ran to " +
                    "$speechSpanMs ms when one word is ${wordSpanMs(rateHz)} ms",
                speechSpanMs <= wordSpanMs(rateHz),
            )
        }
    }

    /**
     * How long the retained speech ran, and how wide the gap turned out to be
     * once quantised to this rate's window.
     *
     * Both fall out of one trim, so the figure the assertion compares against is
     * the figure the same run produced.
     */
    private fun result(rateHz: Int, gapMs: Long): Pair<Long, Long> {
        val frame = EnergyVad.DEFAULT_FRAME_SAMPLES
        val gapFrames = ceilFrames(gapMs, rateHz, frame)
        val pcm = FloatArray((ROOM_FRAMES + SPEECH_FRAMES + gapFrames + SPEECH_FRAMES + ROOM_FRAMES) * frame)

        var at = 0
        repeat(ROOM_FRAMES) {
            AudioSignals.roomTone(frame, amplitude = ROOM_AMPLITUDE).copyInto(pcm, at)
            at += frame
        }
        repeat(SPEECH_FRAMES) {
            AudioSignals.speech(frame, amplitude = SPEECH_AMPLITUDE).copyInto(pcm, at)
            at += frame
        }
        repeat(gapFrames) {
            AudioSignals.roomTone(frame, amplitude = ROOM_AMPLITUDE).copyInto(pcm, at)
            at += frame
        }
        repeat(SPEECH_FRAMES) {
            AudioSignals.speech(frame, amplitude = SPEECH_AMPLITUDE).copyInto(pcm, at)
            at += frame
        }
        repeat(ROOM_FRAMES) {
            AudioSignals.roomTone(frame, amplitude = ROOM_AMPLITUDE).copyInto(pcm, at)
            at += frame
        }

        // Padding would extend the retained span past the speech on both sides
        // and blur exactly the boundary under test; the minimum speech length
        // would reject a single 80 ms word at 48 kHz and report the whole take as
        // silence before the hangover was ever consulted. Both are set aside so
        // the only decision left is whether the interior gap was bridged.
        val trimmed = EnergyVad(
            sampleRateHz = rateHz,
            padMs = 0,
            minSpeechMs = 0,
        ).trim(pcm)

        return trimmed.speechEndMs - trimmed.speechStartMs to gapFrames * frame * 1000L / rateHz
    }

    /** One word, in milliseconds at this rate's window. */
    private fun wordSpanMs(rateHz: Int): Long =
        SPEECH_FRAMES * EnergyVad.DEFAULT_FRAME_SAMPLES * 1000L / rateHz

    private fun ceilFrames(ms: Long, rateHz: Int, frame: Int): Int =
        ((ms * rateHz + frame * 1000L - 1) / (frame * 1000L)).toInt()
}

/** Room tone: a room, and far enough under speech that the two are not in doubt. */
private const val ROOM_AMPLITUDE = 0.02f

/** A word, clear of the ceiling so the floor is never asked to climb past it. */
private const val SPEECH_AMPLITUDE = 0.3f
