package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hangover's frame count, pinned at the one rate where the rounding shows.
 *
 * A duration is converted to whole analysis windows, and 200 ms is 27.5625
 * windows of 320 samples at 44.1 kHz. Rounding that down holds 195.92 ms — less
 * than the stated minimum — and rounds up to 28 windows, 203.17 ms. The other
 * rates divide evenly (5, 10 and 30 windows), so this is the only rate at which
 * the direction of the rounding is observable at all, and it is observable here
 * as a frame boundary rather than as a duration: a gap of exactly 28 windows is
 * inside the hold and a gap of 29 is outside it.
 *
 * The boundary is asserted in whole windows, not in milliseconds, so nothing
 * here depends on the millisecond a window rounds to — only on how many windows
 * the hold spans.
 */
class EnergyVadHangoverRoundingTest {

    private companion object {
        /**
         * The rate at which 200 ms is a whole number of windows plus a fraction,
         * and so the only one of these where rounding up and rounding down give
         * different holds.
         */
        const val RATE_HZ = 44_100

        /**
         * Windows of hold either side of the boundary: 28 windows is 200 ms
         * rounded up, 27 is 200 ms rounded down.
         */
        const val HELD_GAP_FRAMES = 28

        /** One window past the hold, which no rounding of 200 ms can reach. */
        const val CUT_GAP_FRAMES = 29

        /** Frames of speech per word, well over [EnergyVad.MIN_RUN_FRAMES]. */
        const val SPEECH_FRAMES = 12

        /**
         * Room tone before the first word, and after the last — long enough that
         * the trailing room outlasts the hold, so a bridged gap has somewhere to
         * bridge into.
         */
        const val ROOM_FRAMES = 40
    }

    @Test
    fun `the hold spans 28 windows at 44 kHz and 29 windows is past it`() {
        val held = result(HELD_GAP_FRAMES)
        assertTrue(
            "a gap of exactly $HELD_GAP_FRAMES windows at $RATE_HZ Hz was cut, so " +
                "the hold spans at most ${HELD_GAP_FRAMES - 1} windows — 200 ms " +
                "rounded down, which is ${HELD_GAP_FRAMES - 1} x 320 / $RATE_HZ " +
                "samples of hold and less than the ${EnergyVad.DEFAULT_HANGOVER_MS} " +
                "ms the hold is stated as; speech ran to $held ms when one word is " +
                "${wordSpanMs()} ms",
            held > wordSpanMs(),
        )

        val cut = result(CUT_GAP_FRAMES)
        assertEquals(
            "a gap of $CUT_GAP_FRAMES windows at $RATE_HZ Hz was bridged, so the " +
                "hold spans at least $CUT_GAP_FRAMES windows, past the " +
                "${EnergyVad.DEFAULT_HANGOVER_MS} ms it is stated as; speech ran " +
                "to $cut ms when one word is ${wordSpanMs()} ms",
            wordSpanMs(),
            cut,
        )
    }

    /**
     * How long the retained speech ran, in milliseconds, for a take whose two
     * words are separated by [gapFrames] whole windows of room.
     *
     * The gap is laid down in whole windows rather than in milliseconds, so what
     * this measures is the number of windows the hold spans and not how a window
     * converts to a millisecond.
     */
    private fun result(gapFrames: Int): Long {
        val frame = EnergyVad.DEFAULT_FRAME_SAMPLES
        val totalFrames = ROOM_FRAMES + SPEECH_FRAMES + gapFrames + SPEECH_FRAMES + ROOM_FRAMES
        val pcm = FloatArray(totalFrames * frame)

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
        // and blur the boundary under test; the minimum speech length would
        // reject a single word. Both are set aside so the only decision left is
        // whether the interior gap was bridged.
        val trimmed = EnergyVad(
            sampleRateHz = RATE_HZ,
            padMs = 0,
            minSpeechMs = 0,
        ).trim(pcm)

        return trimmed.speechEndMs - trimmed.speechStartMs
    }

    /** One word, in milliseconds at this rate's window. */
    private fun wordSpanMs(): Long =
        SPEECH_FRAMES * EnergyVad.DEFAULT_FRAME_SAMPLES * 1000L / RATE_HZ
}

/** Room tone: a room, and far enough under speech that the two are not in doubt. */
private const val ROOM_AMPLITUDE = 0.02f

/** A word, clear of the ceiling so the floor is never asked to climb past it. */
private const val SPEECH_AMPLITUDE = 0.3f
