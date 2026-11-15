package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The sample rate of a transcription request must be positive: one is the lowest legal rate. */
class SttRequestSampleRateTest {
    private fun request(rate: Int) = SttRequest(
        pcm = floatArrayOf(0.1f),
        wavBytes = byteArrayOf(1),
        model = "small",
        language = "en",
        sampleRateHz = rate,
    )

    @Test
    fun `a rate of one hertz is accepted`() {
        assertEquals(1, request(1).sampleRateHz)
    }

    @Test
    fun `a rate of zero or below is refused`() {
        assertThrows(IllegalArgumentException::class.java) { request(0) }
        assertThrows(IllegalArgumentException::class.java) { request(-1) }
    }
}
