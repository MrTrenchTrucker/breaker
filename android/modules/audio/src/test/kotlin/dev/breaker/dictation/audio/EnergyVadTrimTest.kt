package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VAD, and above all the trim.
 *
 * ### Why this file leans so hard on direction
 *
 * A trim has two plausible-looking mistakes. Keeping the silence and dropping
 * the speech, or keeping the audio *after* the speech and discarding the
 * audio *before* it, both produce a shorter array of the right general shape.
 * Neither throws. The second one is worse: it reads as "trimming worked", and
 * what it returns is the tail of the room rather than the user's dictation.
 *
 * So the tests here are built so that an inverted trim cannot pass by accident:
 * the speech is loud, the room is quiet, the two are different signals, and
 * every assertion checks *which* signal came back and from *where*. The tests
 * that pin this most tightly are marked, and they are the ones to re-run first
 * when the trim is touched.
 */
class EnergyVadTrimTest {

    private val vad = EnergyVad()

    // ── direction: the tests that matter most ───────────────────────────

    @Test
    fun `trim keeps the speech, not the silence around it`() {
        // 1 s room, 1 s speech, 1 s room.
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 1_000, 1_000)
        val result = vad.trim(pcm)

        val speechLevel = AudioSignals.rms(AudioSignals.speech(1_600))
        val roomLevel = AudioSignals.rms(AudioSignals.roomTone(1_600))
        assertTrue(
            "the retained audio is at room level (${AudioSignals.rms(result.pcm)}), " +
                "so the trim kept the silence instead of the speech",
            AudioSignals.rms(result.pcm) > roomLevel * 4,
        )
        assertTrue(
            "the retained audio should be close to the speech level " +
                "($speechLevel) but was ${AudioSignals.rms(result.pcm)}",
            AudioSignals.rms(result.pcm) > speechLevel * 0.4f,
        )
    }

    @Test
    fun `trim keeps the audio BEFORE the speech, not the audio after it`() {
        // This is the inverted-trim guard. The two halves of this take are
        // different signals: quiet room tone, then loud speech. A trim that kept
        // the tail and dropped the head would return room tone at roughly its
        // own level, which is what the level assertions below reject.
        val leadingRoomMs = 800L
        val speechMs = 600L
        val pcm = AudioSignals.takeWithSurroundingSilence(leadingRoomMs, speechMs, 900)
        val result = vad.trim(pcm)

        val headLevel = AudioSignals.rms(AudioSignals.roomTone(1_600))
        val speechLevel = AudioSignals.rms(AudioSignals.speech(1_600))

        assertTrue(
            "the retained audio is at the level of the SILENCE THAT CAME FIRST " +
                "(${AudioSignals.rms(result.pcm)} vs head $headLevel) — the trim " +
                "direction is inverted: it kept the audio after the speech and " +
                "discarded the speech",
            AudioSignals.rms(result.pcm) > headLevel * 4f,
        )
        assertTrue(
            "the retained audio should be at speech level ($speechLevel), was " +
                AudioSignals.rms(result.pcm),
            AudioSignals.rms(result.pcm) > speechLevel * 0.4f,
        )
    }

    @Test
    fun `trim reports speech at the position the speech actually was`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 1_000, 1_000)
        val result = vad.trim(pcm)

        // The speech began at 1000 ms. A trim that reported the leading edge of
        // the capture, or of the trailing silence, would be off by a second.
        assertTrue(
            "speechStartMs was ${result.speechStartMs}, expected near 1000",
            result.speechStartMs in 900..1_100,
        )
        assertTrue(
            "speechEndMs was ${result.speechEndMs}, expected near 2000",
            result.speechEndMs in 1_900..2_100,
        )
        assertTrue(
            "leadingSilenceMs was ${result.leadingSilenceMs}, expected near 1000",
            result.leadingSilenceMs in 800..1_100,
        )
        assertTrue(
            "trailingSilenceMs was ${result.trailingSilenceMs}, expected near 1000",
            result.trailingSilenceMs in 800..1_100,
        )
    }

    @Test
    fun `the retained audio is the speech sample-for-sample, not merely loud audio`() {
        // Level alone cannot catch a trim that keeps the wrong *span* of the
        // right loudness. Comparing the retained samples against the speech they
        // came from can.
        val leadingMs = 700L
        val speechMs = 800L
        val leadingSamples = (leadingMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        val speechSamples = (speechMs * AudioFormat.SAMPLE_RATE_HZ / 1000L).toInt()
        val pcm = AudioSignals.takeWithSurroundingSilence(leadingMs, speechMs, 1_100)

        val result = EnergyVad(padMs = 0).trim(pcm)

        // With padding off, the retained samples must be exactly the speech.
        val expected = AudioSignals.speech(speechSamples)
        val offset = findOffset(expected, result.pcm)
        assertTrue(
            "the retained audio is not the speech anywhere in the take; the " +
                "trim kept ${result.pcm.size} samples that match nothing in a " +
                "${pcm.size}-sample capture",
            offset >= 0,
        )
        assertEquals(
            "the retained span should be the ${speechSamples}-sample speech, " +
                "was ${result.pcm.size} samples from offset $offset",
            speechSamples,
            result.pcm.size,
        )
    }

    @Test
    fun `trim removes the leading silence even when there is no trailing silence`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 1_000, 0)
        val result = vad.trim(pcm)
        assertTrue(
            "the trailing silence was empty but ${result.leadingSilenceMs}ms of " +
                "leading silence was reported as removed",
            result.leadingSilenceMs in 800..1_100,
        )
        assertEquals(0, result.trailingSilenceMs)
    }

    @Test
    fun `trim removes the trailing silence even when there is no leading silence`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(0, 1_000, 1_000)
        val result = vad.trim(pcm)
        assertEquals(0, result.leadingSilenceMs)
        assertTrue(
            "1 s of trailing silence was in the take but ${result.trailingSilenceMs}ms " +
                "was reported as removed",
            result.trailingSilenceMs in 800..1_100,
        )
    }

    @Test
    fun `padding widens the retained span without moving its start`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(1_000, 1_000, 1_000)
        val unpadded = EnergyVad(padMs = 0).trim(pcm)
        val padded = EnergyVad(padMs = 100).trim(pcm)

        assertEquals(
            "padding should not move where the speech starts",
            unpadded.speechStartMs,
            padded.speechStartMs,
        )
        assertTrue(
            "100 ms of padding on each side should add about 200 ms, added " +
                "${padded.durationMs - unpadded.durationMs}ms",
            padded.durationMs - unpadded.durationMs in 150..250,
        )
    }

    @Test
    fun `padding is clipped to the capture rather than reaching past it`() {
        // Speech that starts at sample 0 with 500 ms of padding asked for must
        // not produce a negative start index.
        val pcm = AudioSignals.speech(1_600)
        val result = EnergyVad(padMs = 500).trim(pcm)
        assertEquals(0, result.leadingSilenceMs)
        assertTrue(
            "the trim reported ${result.pcm.size} samples from a ${pcm.size}-sample " +
                "capture — padding reached past the end",
            result.pcm.size <= pcm.size,
        )
    }

    // ── degenerate captures ─────────────────────────────────────────────

    @Test
    fun `a capture with no speech in it keeps nothing and says so`() {
        val result = vad.trim(AudioSignals.roomTone(3_200))
        assertFalse("a room with no speech in it must not report speech", result.hasSpeech)
        assertEquals(0, result.pcm.size)
        assertEquals(3_200 * 1000L / AudioFormat.SAMPLE_RATE_HZ, result.trailingSilenceMs)
    }

    @Test
    fun `digital silence keeps nothing and says so`() {
        val result = vad.trim(AudioSignals.silence(3_200))
        assertFalse(result.hasSpeech)
        assertEquals(0, result.pcm.size)
    }

    @Test
    fun `an empty capture is handled rather than thrown on`() {
        val result = vad.trim(FloatArray(0))
        assertEquals(0, result.pcm.size)
        assertFalse(result.hasSpeech)
    }

    @Test
    fun `a capture shorter than one analysis window is handed back untouched`() {
        // Throwing here would lose a short dictation; returning it untrimmed is
        // the conservative answer.
        val pcm = AudioSignals.speech(100)
        val result = vad.trim(pcm)
        assertTrue(result.pcm.contentEquals(pcm))
        assertTrue(result.hasSpeech)
    }

    @Test
    fun `a blip shorter than the minimum dictation length is not speech`() {
        val pcm = AudioSignals.takeWithSurroundingSilence(500, 40, 500)
        val result = EnergyVad().trim(pcm)
        assertFalse(
            "a 40 ms blip is a cough, not a dictation, but ${result.pcm.size} " +
                "samples were kept",
            result.hasSpeech,
        )
    }

    @Test
    fun `the trim result never prints the audio`() {
        val result = vad.trim(AudioSignals.takeWithSurroundingSilence(500, 500, 500))
        val text = result.toString()
        assertTrue(
            "the result should describe the trim, was: $text",
            text.contains("speech="),
        )
        assertTrue(
            "the result printed something long enough to be audio: $text",
            text.length < 200,
        )
    }

    /**
     * The offset at which [needle] appears in [haystack], or -1.
     *
     * A tolerant cross-correlation rather than an exact match, because the VAD
     * retains a whole number of frames and the speech's phase within the frame
     * is whatever it was.
     */
    private fun findOffset(needle: FloatArray, haystack: FloatArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val window = 64
        for (offset in 0..(haystack.size - needle.size)) {
            var error = 0f
            var checked = 0
            var i = 0
            while (i < needle.size) {
                if (kotlin.math.abs(needle[i] - haystack[offset + i]) > 0.05f) {
                    error += 1f
                }
                checked++
                i += window
            }
            if (error.toFloat() / checked < 0.1f) return offset
        }
        return -1
    }
}
