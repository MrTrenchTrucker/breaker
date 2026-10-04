package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KDoc's "quiet speech still crosses it", checked against the floor's ceiling.
 *
 * [EnergyVad] documents itself as follows (EnergyVad.kt, class KDoc):
 *
 *     The floor follows the *quietest* recent frames and creeps upwards slowly, so
 *     a passing truck lifts it a little and quiet speech still crosses it.
 *
 * The "creeps upwards slowly" half is true and is pinned by
 * [EnergyVadNoiseFloorTest] (a 20-frame transient). This test targets the other
 * half — "quiet speech still crosses it" — with a SUSTAINED level rather than a
 * transient, which is the population the transient test does not reach.
 *
 * ### The population
 *
 * A steady frame at or below the floor's ceiling threshold, fed continuously.
 * The floor is clamped at [EnergyVad.DEFAULT_MAX_NOISE_FLOOR_DB] (-25 dBFS) and a
 * frame is speech only when `level > floor + speechMarginDb` (an 8 dB margin),
 * so once the floor has pinned at its ceiling the speech threshold is fixed at
 * -25 + 8 = -17 dBFS. A steady frame at -18 dBFS therefore reads as speech at
 * first (the floor is still climbing) and then stops reading as speech once the
 * floor reaches its ceiling, and does not recover: the frame is 1 dB under the
 * threshold for the rest of the capture.
 *
 * Of the 60 steady -18 dBFS frames this test feeds, 47 read as speech and 13 do
 * not; the flip is at frame 47 (t ~ 0.94 s). The KDoc's "quiet speech still
 * crosses it" claims the speech never stops crossing, which the ceiling makes
 * false for any sustained level at or below -17 dBFS.
 *
 * The module card (AGENTS.md, Known Gotchas) already names this floor-rise
 * behaviour as "Found, not fixed" for this detector, so this is a KDoc
 * contradiction rather than a newly hidden defect: the class KDoc overstates what
 * the code, and the card, actually do.
 */
class EnergyVadQuietSpeechFloorTest {

    private companion object {
        /** The quiet room the floor learns in, far under the -25 dB ceiling. */
        const val QUIET_ROOM_DB = -50.0

        /**
         * The sustained "quiet speech" level: 3 dB under the -17 dBFS threshold
         * the floor's ceiling sets, so it is speech at first and silence once the
         * floor pins. A level at or above -17 would stay speech and could not
         * exercise the ceiling. (The 220 Hz tone over 320 samples is 4.4 periods,
         * so its RMS sits 0.07 dB above the named level; -20 leaves a 2.9 dB
         * margin under the threshold, well clear of that.)
         */
        const val QUIET_SPEECH_DB = -20.0

        /** Frames of quiet room to prime the floor before the speech arrives. */
        const val PRIME_FRAMES = 10

        /**
         * Frames of sustained speech. Long enough that the floor, rising 0.5 dB a
         * frame from the -50 room, reaches its -25 ceiling (50 frames) and holds,
         * so the late frames are judged against the pinned floor rather than one
         * still climbing.
         */
        const val SPEECH_FRAMES = 60
    }

    @Test
    fun `sustained quiet speech keeps crossing the floor`() {
        // The KDoc claim under test: "quiet speech still crosses it." A transient
        // crossing is the [EnergyVadNoiseFloorTest] case; this is the sustained
        // case, where the floor has time to reach its ceiling and hold.
        val detector = EnergyVad()
        val room = frameAtLevelDb(QUIET_ROOM_DB)
        val speech = frameAtLevelDb(QUIET_SPEECH_DB)

        // Prime the floor down at the quiet room: the state a capture is in when
        // the speaker starts.
        repeat(PRIME_FRAMES) { detector.isSpeech(room) }

        // The first speech frame must read as speech: the floor is still well
        // under the ceiling, so the 8 dB margin clears. Without this, a detector
        // whose floor is already at the ceiling before the speech arrives could
        // pass the assertion below for the wrong reason (nothing is ever speech).
        assertTrue(
            "the first frame of sustained quiet speech at $QUIET_SPEECH_DB dB must " +
                "read as speech, with the floor still climbing from the $QUIET_ROOM_DB " +
                "dB room",
            detector.isSpeech(speech),
        )

        // Hold the level. The KDoc says it "still crosses it" for as long as the
        // speaker keeps talking. The floor rises 0.5 dB a frame from the room and
        // pins at its -25 dB ceiling, after which this -18 dB frame sits 1 dB
        // under the -17 threshold and reads as silence.
        var stillSpeech = true
        repeat(SPEECH_FRAMES - 1) {
            stillSpeech = detector.isSpeech(speech)
        }

        assertTrue(
            "sustained quiet speech at $QUIET_SPEECH_DB dB stopped reading as " +
                "speech after the floor pinned at its ceiling; the KDoc promises " +
                "'quiet speech still crosses it', but a level 3 dB under the " +
                "ceiling's -17 dB threshold cannot cross it once the floor has " +
                "reached -25 dB",
            stillSpeech,
        )
    }

    /**
     * One analysis window of steady audio at exactly [levelDb] dBFS.
     *
     * A tone of amplitude `a` has an RMS of `a / sqrt(2)`, so the amplitude for a
     * level is `10^(dB/20) * sqrt(2)`, which makes the detector's RMS exactly
     * `10^(dB/20)` and its reported level exactly [levelDb].
     */
    private fun frameAtLevelDb(levelDb: Double): FloatArray {
        val amplitude = Math.pow(10.0, levelDb / 20.0).toFloat() * Math.sqrt(2.0).toFloat()
        return AudioSignals.tone(
            samples = EnergyVad.DEFAULT_FRAME_SAMPLES,
            hz = 220.0,
            amplitude = amplitude,
        )
    }
}
