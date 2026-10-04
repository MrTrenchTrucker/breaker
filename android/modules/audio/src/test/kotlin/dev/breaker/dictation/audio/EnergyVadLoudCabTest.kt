package dev.breaker.dictation.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loud end of the detector's range, where the noise floor has climbed.
 *
 * A quiet room is the easy half: the floor sits far below any voice and the
 * margin alone answers every question. These two tests are the other half, and
 * they pull in opposite directions on purpose.
 *
 *  * Steady LOUD NOISE has to stay out. A cab at speed sits well above a
 *    quiet room, and a noise floor that cannot rise to meet it calls the whole
 *    engine note a dictation.
 *  * LOUD SPEECH has to stay in. Raising the floor to reject that noise is
 *    exactly what puts raised speech at risk, because the floor cannot tell the
 *    two apart by level — it can only tell them apart by the ceiling, which is
 *    the one claim about how loud a room never gets.
 *
 * So the pair is the trade in test form. A fix that satisfies the first by
 * simply letting the floor follow the noise fails the second, and a fix that
 * satisfies the second by moving the ceiling down fails the first — the floor
 * then cannot climb far enough to exclude the noise at all, and every frame of
 * it reads as speech.
 */
class EnergyVadLoudCabTest {

    private companion object {
        /**
         * Steady road and engine noise in a moving cab, 5 dB under the
         * [EnergyVad.DEFAULT_MAX_NOISE_FLOOR_DB] ceiling.
         *
         * Under it rather than over it, because a level above the ceiling is
         * speech by the detector's own absolute claim and no test here would be
         * saying anything about the floor.
         */
        const val CAB_NOISE_DB = -30.0

        /**
         * A raised voice in that cab: 6 dB and 8 dB over the noise, and 1 dB
         * and 3 dB under the ceiling.
         *
         * Both are in the band where the two halves of the trade disagree. The
         * margin alone would put the threshold at noise + 8, so each of these
         * sits under it and reads as silence; the ceiling puts it at the
         * ceiling itself, so both clear it.
         *
         * A level of exactly -25 is deliberately NOT in this list. It is the
         * ceiling, and the comparison is a strict `>`, so a frame sitting
         * precisely on the boundary is silence. That is the documented edge of
         * the claim rather than a level worth pinning a fixture to.
         */
        const val LOUD_SPEECH_LOW_DB = -24.0
        const val LOUD_SPEECH_HIGH_DB = -22.0

        /**
         * Frames of cab noise, 5 s at 20 ms a frame.
         *
         * Long enough for the floor to reach the noise and stop climbing: from
         * the first frame the floor is primed onto the noise itself, and 250
         * frames is far more than the 0.5 dB a frame needs to travel the last
         * few dB. The last frames are the ones that matter — a floor still
         * climbing has not yet decided anything about the frames after it.
         */
        const val CAB_NOISE_FRAMES = 250

        /**
         * Frames of raised speech once the floor has settled on the cab.
         *
         * Held rather than sampled once, because a single frame can pass on a
         * floor that is still moving. Every frame has to pass, or the detector
         * is reporting the start of a word and nothing after it.
         */
        const val LOUD_SPEECH_FRAMES = 40
    }

    @Test
    fun `steady loud cab noise is never speech`() {
        // The half of the trade that a lowered ceiling loses. A capture that
        // starts in a moving cab has no quiet frames to learn from, so the
        // floor is primed onto the noise on the very first frame and then
        // tracks it. Every frame of that is the same loud non-speech, and a
        // take made entirely of it is an engine, not a dictation.
        val detector = EnergyVad()
        val cabNoise = frameAtLevelDb(CAB_NOISE_DB)

        var speechSeen = false
        repeat(CAB_NOISE_FRAMES) {
            if (detector.isSpeech(cabNoise)) speechSeen = true
        }

        assertTrue(
            "steady cab noise at $CAB_NOISE_DB dB read as speech on at least one " +
                "of $CAB_NOISE_FRAMES frames; a floor that cannot climb to the " +
                "noise it is tracking reports the engine as a dictation",
            !speechSeen,
        )
    }

    @Test
    fun `raised speech in a loud cab is still speech`() {
        // The other half, and the one a fix for the test above is most likely to
        // break. The floor is settled onto the cab noise before the voice
        // arrives, which is the state a moving cab is in when someone speaks:
        // the hardest state for the detector, and the one where a floor that
        // has been pushed up to reject the noise now risks the voice too.
        for (speechDb in listOf(LOUD_SPEECH_LOW_DB, LOUD_SPEECH_HIGH_DB)) {
            val detector = EnergyVad()
            val cabNoise = frameAtLevelDb(CAB_NOISE_DB)
            val speech = frameAtLevelDb(speechDb)

            repeat(CAB_NOISE_FRAMES) { detector.isSpeech(cabNoise) }

            // The cab has to have been learned, or a threshold low enough to
            // pass the assertion below would prove nothing: a detector that
            // calls everything speech also passes it.
            assertTrue(
                "the cab noise at $CAB_NOISE_DB dB should not read as speech " +
                    "once the floor has settled onto it",
                !detector.isSpeech(cabNoise),
            )

            var allSpeech = true
            repeat(LOUD_SPEECH_FRAMES) {
                if (!detector.isSpeech(speech)) allSpeech = false
            }

            assertTrue(
                "raised speech at $speechDb dB in a cab whose floor has climbed " +
                    "to $CAB_NOISE_DB dB stopped reading as speech; a floor that " +
                    "has been taught the loud room has also been taught to " +
                    "reject a voice only a few dB above it",
                allSpeech,
            )
        }
    }

    /**
     * One analysis window of steady audio at exactly [levelDb] dBFS.
     *
     * A tone of amplitude `a` has an RMS of `a / sqrt(2)`, so the amplitude for
     * a level is `10^(dB/20) * sqrt(2)`, which makes the detector's RMS exactly
     * `10^(dB/20)` and its reported level exactly [levelDb]. The detector
     * measures RMS, so this is the level it sees.
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
