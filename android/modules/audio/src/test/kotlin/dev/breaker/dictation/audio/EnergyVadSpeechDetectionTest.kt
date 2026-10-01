package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the detector counts as speech, and where a take is judged to have
 * started and ended.
 *
 * Split from the trim tests: these pin the DECISION the detector makes about a
 * frame and a run of frames, where the trim tests pin what the trim returns
 * for a whole capture. The noise floor's rate of change and the reset have
 * their own file.
 */
class EnergyVadSpeechDetectionTest {

    private val vad = EnergyVad()

    // ── what counts as speech ───────────────────────────────────────────

    @Test
    fun `a loud frame is speech and a quiet one is not`() {
        val quiet = FloatArray(320) { AudioSignals.NOISE_AMPLITUDE * 0.01f }
        val loud = AudioSignals.speech(320)
        assertTrue("a loud frame should read as speech", vad.isSpeech(loud))
        assertFalse("a quiet frame should not read as speech", vad.isSpeech(quiet))
    }

    @Test
    fun `an empty frame is not speech`() {
        assertFalse(vad.isSpeech(FloatArray(0)))
    }

    @Test
    fun `a knock does not open a take`() {
        // 30 ms of noise is a door, not a dictation; requiring a run of frames
        // is what stops one from trimming away a real take that follows it.
        //
        // The speech behind the knock must clear minSpeechMs, the 150 ms
        // minimum a take has to reach before the detector will look at where
        // the speech starts — 2400 samples at 16 kHz. A take below that is
        // discarded outright and reports speechStartMs of 0, so the fixture
        // sizes the dictation at 4800 samples (300 ms, 15 frames) to clear it
        // with room to spare, while the knock stays short at the head.
        val pcm = FloatArray(480 + 4_800)
        AudioSignals.noise(480).copyInto(pcm, 0)
        AudioSignals.speech(4_800).copyInto(pcm, 480)
        val result = vad.trim(pcm)

        // The take has to have survived at all, or the assertion below would
        // pass for the wrong reason.
        assertTrue(
            "a 300 ms take should clear the 150 ms minimum, but nothing was kept",
            result.hasSpeech,
        )
        // The knock occupies samples 0..479, so any report of a start at 0 ms
        // means the knock was counted as the start of the dictation. Frame
        // quantisation puts the first real frame at 20 ms.
        assertTrue(
            "a 30 ms knock at the head should not be treated as the start of " +
                "speech, but the take was reported as starting at " +
                "${result.speechStartMs}ms",
            result.speechStartMs >= 20,
        )
    }

    @Test
    fun `a knock on its own never opens a take`() {
        // The other half of the knock rule: with no dictation behind it, a
        // single loud frame must leave nothing. A lead-in frame of silence puts
        // the knock past the point where the noise floor is primed from the
        // capture itself, so the knock frame is judged purely on its own level
        // and is the loudest thing in the take by a wide margin.
        //
        // This pins the run requirement from the other side. The test above can
        // only see a knock that is followed by real speech; this one has to
        // survive the detector being relaxed until a single loud frame is
        // enough on its own.
        val pcm = FloatArray(320 + 320 + 4_800)
        AudioSignals.silence(320).copyInto(pcm, 0)
        AudioSignals.speech(320).copyInto(pcm, 320)
        AudioSignals.silence(4_800).copyInto(pcm, 640)

        val result = vad.trim(pcm)

        assertFalse(
            "one loud frame between silences is a knock, not a take, but it " +
                "reported speech at ${result.speechStartMs}ms",
            result.hasSpeech,
        )
        assertEquals(
            "a knock on its own should keep nothing, kept ${result.pcm.size} samples",
            0,
            result.pcm.size,
        )
    }

    @Test
    fun `a knock before a real take does not become the start of it`() {
        // The sharpest form of the knock rule: a knock, a pause, then a real
        // dictation. The dictation is found and kept either way, so the only
        // thing that can differ is WHERE it is reported to have started.
        //
        // The knock is loud enough to pass the energy test on its own and sits
        // at 20 ms, behind a leading frame of silence so the floor is already
        // primed low enough for it to read as speech. If the run requirement
        // were dropped and a single loud frame were allowed to open the take,
        // the start would be reported as 20 ms; the dictation itself starts at
        // 80 ms and nothing earlier may be reported.
        val pcm = FloatArray(1_280 + 4_800)
        AudioSignals.silence(320).copyInto(pcm, 0)
        AudioSignals.speech(320).copyInto(pcm, 320)
        AudioSignals.silence(640).copyInto(pcm, 640)
        AudioSignals.speech(4_800).copyInto(pcm, 1_280)

        val result = vad.trim(pcm)

        assertTrue(
            "the 300 ms of dictation behind the knock should have been kept",
            result.hasSpeech,
        )
        assertTrue(
            "the take starts 80 ms into the capture, after the knock and the " +
                "pause, but a start of ${result.speechStartMs}ms means the " +
                "knock was counted as the beginning of the dictation",
            result.speechStartMs >= 60,
        )
    }

    @Test
    fun `a take of nothing but noise keeps nothing`() {
        // The floor case for the run requirement: loud-ish noise for the whole
        // capture, with no speech anywhere in it. Nothing here should read as
        // speech however few consecutive frames the detector insists on.
        val pcm = AudioSignals.noise(4_800)
        val result = vad.trim(pcm)

        assertFalse(
            "300 ms of noise with no speech in it must not report speech",
            result.hasSpeech,
        )
        assertEquals(
            "a take of noise should keep nothing, kept ${result.pcm.size} samples",
            0,
            result.pcm.size,
        )
    }

    @Test
    fun `a cough at the end does not extend the take`() {
        val pcm = FloatArray(1_600 + 960)
        AudioSignals.speech(1_600).copyInto(pcm, 0)
        AudioSignals.noise(960).copyInto(pcm, 1_600)
        val result = EnergyVad(padMs = 0).trim(pcm)
        // The end is reported in whole frames plus whatever the hangover bridged;
        // it must not run to the end of the capture.
        assertTrue(
            "speechEndMs was ${result.speechEndMs} for a take whose speech ends " +
                "at 100 ms into a ${pcm.size}-sample capture — trailing noise " +
                "extended it",
            result.speechEndMs <= 200,
        )
    }

    @Test
    fun `a gap between words is bridged, not cut`() {
        // 200 ms of room between two spoken words: the hangover exists so the
        // dictation is not cut in half at every pause.
        //
        // Both fixture dimensions are deliberate and neither may be changed:
        //
        //  * The gap is 3200 samples — 200 ms, exactly 10 frames, exactly the
        //    hangover. The loop in the detector stops only once a gap runs PAST
        //    the hangover, so a gap of exactly the hangover length is still
        //    bridged and this take is kept whole. This is the boundary the test
        //    exists to pin, so the gap must not be shortened: at any shorter
        //    length the test would pass without ever reaching the boundary.
        //
        //  * Each word is 1600 samples — 100 ms, 5 frames. A word has to be at
        //    least the 3 consecutive frames the detector requires before it
        //    commits to a take at all, and 1600 samples clears that
        //    comfortably; 3200 of speech also clears the 150 ms minimum a take
        //    has to reach.
        val word = AudioSignals.speech(1_600)
        val gap = AudioSignals.roomTone(3_200)
        val pcm = FloatArray(word.size * 2 + gap.size)
        word.copyInto(pcm, 0)
        gap.copyInto(pcm, word.size)
        word.copyInto(pcm, word.size + gap.size)

        val result = EnergyVad(padMs = 0).trim(pcm)
        assertTrue(
            "the 200 ms gap should be inside the retained span, but the take " +
                "kept only ${result.pcm.size} of ${pcm.size} samples",
            result.pcm.size > word.size * 2,
        )
        // Bridged means bridged: the whole capture, both words and the gap
        // between them, and nothing dropped at either end.
        assertEquals(
            "a 200 ms gap is bridged whole, so the take should be the whole " +
                "${pcm.size}-sample capture, kept ${result.pcm.size}",
            pcm.size,
            result.pcm.size,
        )
    }

    @Test
    fun `a gap shorter than the hangover is bridged`() {
        // The near side of the boundary the test above sits on: 5 frames of
        // silence, 100 ms, half the hangover. Comfortably inside it, so this
        // take must come back whole — and it must still do so if the hangover
        // were shortened to 9 frames, which would be enough to cut the 200 ms
        // gap in the test above.
        val word = AudioSignals.speech(3_200)
        val gap = AudioSignals.roomTone(1_600)
        val pcm = FloatArray(word.size * 2 + gap.size)
        word.copyInto(pcm, 0)
        gap.copyInto(pcm, word.size)
        word.copyInto(pcm, word.size + gap.size)

        val result = EnergyVad(padMs = 0).trim(pcm)
        assertEquals(
            "a 100 ms gap is well inside the 200 ms hangover, so the whole " +
                "${pcm.size}-sample take should be kept, kept ${result.pcm.size}",
            pcm.size,
            result.pcm.size,
        )
    }

    @Test
    fun `a gap longer than the hangover cuts the take`() {
        // The far side of the boundary: 20 frames of silence, 400 ms, twice the
        // hangover. That is not a pause inside a dictation, it is the end of
        // one, so the second word must be dropped along with the gap in front
        // of it and only the first word kept. If the hangover were lengthened
        // to 11 frames this would change, which is the point: the two tests
        // either side of it bracket the boundary from both directions.
        val word = AudioSignals.speech(3_200)
        val gap = AudioSignals.roomTone(6_400)
        val pcm = FloatArray(word.size * 2 + gap.size)
        word.copyInto(pcm, 0)
        gap.copyInto(pcm, word.size)
        word.copyInto(pcm, word.size + gap.size)

        val result = EnergyVad(padMs = 0).trim(pcm)
        assertEquals(
            "a 400 ms gap is twice the hangover, so the take should stop at the " +
                "end of the first word (${word.size} samples), kept " +
                "${result.pcm.size} of ${pcm.size}",
            word.size,
            result.pcm.size,
        )
    }

}
