package dev.breaker.dictation.training.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limits are checked against the SHAPE of a real WAV, 44 header bytes plus 2 bytes per 16 bit sample,
 * which is what the shipped WAV encoder is claimed to write. The counting fake used elsewhere in these
 * tests is NOT the real encoder, so no test here encodes anything. Not compiled or run yet.
 */
class TrainingConstantsTest {
    private val longestWavBytes: Int = 44 + 2 * RecorderBounds.MAX_SAMPLES

    @Test
    fun `the upload limit holds the longest allowed recording as a wav`() {
        assertTrue(
            "limit ${TrainingLimits.MAX_UPLOAD_BYTES} is below $longestWavBytes",
            TrainingLimits.MAX_UPLOAD_BYTES >= longestWavBytes,
        )
    }

    @Test
    fun `the longest allowed recording as a wav is 96044 bytes`() {
        assertEquals(96_044, longestWavBytes)
    }

    @Test
    fun `the sample counts are ordered minimum then default then maximum`() {
        assertTrue(SampleCounts.MIN_PER_PHRASE <= SampleCounts.DEFAULT_PER_PHRASE)
        assertTrue(SampleCounts.DEFAULT_PER_PHRASE <= SampleCounts.MAX_PER_PHRASE)
    }

    @Test
    fun `the model cap is 32 MiB`() {
        assertEquals(33_554_432L, TrainingLimits.MAX_MODEL_BYTES)
        assertEquals(32L * 1024L * 1024L, TrainingLimits.MAX_MODEL_BYTES)
    }

    @Test
    fun `the minimum is ten and the maximum is twenty`() {
        assertEquals(10, SampleCounts.MIN_PER_PHRASE)
        assertEquals(20, SampleCounts.MAX_PER_PHRASE)
    }
}
