package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The buffer between the capture thread and the consumer: what it refuses.
 */
class PcmRingBufferRefusalTest {

    @Test
    fun `a capacity of zero is refused`() {
        try {
            PcmRingBuffer(capacitySamples = 0)
            fail("expected a zero capacity to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capacitySamples"))
        }
    }

    @Test
    fun `a read past the end of the destination is refused rather than throwing obscurely`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        try {
            buffer.read(FloatArray(4), offset = 2, length = 4)
            fail("expected a read past the end of the destination to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "the message should say the read runs past the end, was: ${e.message}",
                e.message!!.contains("past the end"),
            )
        }
    }

    @Test
    fun `a write past the end of the source is refused`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        try {
            buffer.write(FloatArray(4), offset = 2, length = 4)
            fail("expected a write past the end of the source to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("past the end"))
        }
    }
}
