package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The default limit for one decode, as a pure function of the clip length. Nothing here waits. */
class DecodeLimitTest {

    @Test
    fun `the default limit is the floor for no audio and grows by three times the clip length`() {
        assertEquals("no audio must get the 30 second floor", 30_000L, decodeLimitMs(0L))
        assertEquals("one second of audio must add three seconds", 33_000L, decodeLimitMs(1_000L))
        assertEquals("one minute of audio must add three minutes", 210_000L, decodeLimitMs(60_000L))
    }
}
