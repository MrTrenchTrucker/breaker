package dev.breaker.dictation.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive half of the detector: how fast the noise floor is allowed to
 * rise, and what a reset has to put back.
 *
 * Both tests here are about state the detector carries between frames rather
 * than about any one frame's level, which is why they are held apart from the
 * decision tests.
 */
class EnergyVadNoiseFloorTest {

    private companion object {
        /**
         * The quiet room the floor learns in, and the level every fixture here
         * is measured up from.
         *
         * Deliberately far below the -25 dB ceiling: the two tests below are
         * about the floor's RATE of change and about whether it is re-primed,
         * and neither is observable at a level the ceiling clamps. 50 dB is
         * chosen so the margin between the room and the signals under test is
         * measured in tens of decibels rather than at the boundary.
         */
        const val QUIET_ROOM_DB = -50.0

        /**
         * A transient the floor must not follow: 23 dB above the room, and
         * still 2 dB under the ceiling, so the ceiling cannot answer for the
         * rise rate.
         */
        const val PASSING_TRUCK_DB = -27.0

        /**
         * The room that comes after the reset in the reset test, LOUDER than
         * the floor learned before the reset — the direction in which a stale
         * floor still reads as speech.
         */
        const val LOUDER_ROOM_DB = -27.0

        /** How far that louder room sits above the stale floor it must beat. */
        const val LOUDER_ROOM_SPAN_DB = 23

        /**
         * Speech in the room learned after the reset: 12 dB over it, so it
         * clears the 8 dB margin without needing the floor to be wrong.
         */
        const val QUIET_SPEECH_DB = -15.0

        /**
         * Frames of truck in the rise-rate test.
         *
         * At 0.5 dB a frame the floor climbs 10 dB across twenty of them and
         * is still below the truck, which is what the assertion needs. Fewer
         * frames would let a faster floor pass.
         */
        const val PASSING_FRAMES = 20

        /** Long enough for the floor to settle onto the quiet room. */
        const val QUIET_ROOM_FRAMES = 20

        /** Long enough for the floor to settle onto the room after the reset. */
        const val LOUDER_ROOM_FRAMES = 20
    }

    @Test
    fun `the noise floor rises slowly so a passing truck cannot swallow speech`() {
        // A transient the floor must NOT follow. The rate limit is the whole
        // claim, so the fixture has to be one in which the rate limit is the
        // only thing holding the answer up.
        //
        // Every level here sits BELOW the -25 dB ceiling, and that is the
        // point rather than an accident. The ceiling is a separate guarantee —
        // a room is never this loud — and it CLAMPS the floor from above. A
        // fixture built out of the ordinary loud signals sits around -11 dB,
        // above that ceiling, so a floor that leaps straight to the frame
        // level is pulled back down to -25 and still leaves the frame 14 dB
        // clear of it. The ceiling answers the question on its own and the
        // rise rate is never consulted, which is exactly how a detector with
        // no rise limit at all can pass this test.
        //
        // Under the ceiling the rate limit becomes the only answer: the truck
        // is 23 dB above the room, and a floor that crept at 0.5 dB a frame
        // would still be 4 dB below the truck after 20 frames of it, so the
        // truck reads as speech. A floor that jumped to the truck on the first
        // frame would sit exactly on it, 8 dB below the speech threshold, and
        // the truck would read as silence — swallowed, which is the failure
        // this test exists to catch.
        val detector = EnergyVad()
        val room = frameAtLevelDb(levelDb = QUIET_ROOM_DB)
        val truck = frameAtLevelDb(levelDb = PASSING_TRUCK_DB)

        // One quiet frame primes the floor down at the room, which is the state
        // a capture in a quiet room is actually in when the truck arrives.
        assertFalse(
            "the quiet room should not read as speech on the frame that primes it",
            detector.isSpeech(room),
        )
        // The truck passes. Twenty frames is deliberate and may not be
        // shortened: at 0.5 dB a frame the floor climbs 10 dB over them, and
        // the assertion below needs it to still be under the truck at the end.
        // Fewer frames would pass with a faster floor.
        repeat(PASSING_FRAMES) { detector.isSpeech(truck) }

        assertTrue(
            "a floor that climbed ${PASSING_FRAMES} loud frames by more than " +
                "${PASSING_FRAMES * 0.5f} dB has swallowed the truck; the floor " +
                "must creep up so a transient cannot ride it to silence",
            detector.isSpeech(truck),
        )
    }

    @Test
    fun `resetting lets a louder room be learned after a quiet one`() {
        // What a reset has to buy, stated as a failure it prevents.
        //
        // reset() does two things: it drops the floor to its initial value and
        // it UNPRIMES it, so the next frame is learned from that frame rather
        // than tracked against a floor belonging to the previous room. The
        // unpriming is the half that is easy to lose, because a floor handed
        // down from a QUIET room is already low and looks like it is working.
        //
        // So the room after the reset is LOUDER than the floor learned before
        // it. Under a reset that works, the first frame of the new room primes
        // the floor onto that room and the room reads as the room. Under a
        // reset that only empties its body, the floor is never unprimed and
        // never lowered, so it creeps up from the old quiet level 0.5 dB a
        // frame — and a room 12 dB above that stale floor reads as a
        // dictation. A quiet room after a loud one cannot detect this: there
        // the stale floor is too HIGH, a frame quieter than it drops the floor
        // outright, and the stale value is overwritten on the first frame
        // whether or not anything reset it.
        val detector = EnergyVad()
        val quietRoom = frameAtLevelDb(levelDb = QUIET_ROOM_DB)
        val louderRoom = frameAtLevelDb(levelDb = LOUDER_ROOM_DB)
        val speech = frameAtLevelDb(levelDb = QUIET_SPEECH_DB)

        // Learn a quiet room first, so the floor has a real low value to be
        // wrongly carried across the reset.
        repeat(QUIET_ROOM_FRAMES) { detector.isSpeech(quietRoom) }
        detector.reset()

        // The new room is louder than the old floor. It must still be a room.
        val firstNewRoomFrame = detector.isSpeech(louderRoom)
        assertFalse(
            "the room after the reset reads as speech at " +
                "$LOUDER_ROOM_DB dB, $LOUDER_ROOM_SPAN_DB dB above the floor " +
                "learned before it; the detector is still tracking the previous " +
                "room, so a reset that did not unprime the floor reads a louder " +
                "room as a dictation",
            firstNewRoomFrame,
        )
        // And the floor has genuinely settled onto this room, so speech in it
        // is still found. Without this the assertion above could pass on a
        // floor so low that nothing is ever speech, which is a detector with
        // no detector in it.
        repeat(LOUDER_ROOM_FRAMES) { detector.isSpeech(louderRoom) }
        assertTrue(
            "speech at $QUIET_SPEECH_DB dB did not read as speech in a room " +
                "learned after the reset",
            detector.isSpeech(speech),
        )
    }

    /**
     * One analysis window of steady audio at exactly [levelDb] dBFS.
     *
     * A plain tone rather than one of the shaped signals, because these two
     * tests are about LEVELS rather than about the identity of a signal, and
     * an amplitude named in decibels says what the fixture is doing in a way
     * `SPEECH_AMPLITUDE` does not. A tone of amplitude `a` has an RMS of
     * `a / sqrt(2)`, so the amplitude for a level is `10^(dB/20) * sqrt(2)`.
     *
     * The detector measures RMS, so this is the level the detector sees; every
     * level in these tests is chosen against the -25 dB ceiling and the 8 dB
     * speech margin on that scale.
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
