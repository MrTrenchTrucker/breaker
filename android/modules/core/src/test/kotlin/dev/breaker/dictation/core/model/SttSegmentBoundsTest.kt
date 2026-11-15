package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A transcript segment may be empty in time (end equals start) but never end before it starts. */
class SttSegmentBoundsTest {
    @Test
    fun `a zero length segment is accepted`() {
        assertEquals(5L, SttSegment(startMs = 5, endMs = 5, text = "x").endMs)
        assertEquals(0L, SttSegment(startMs = 0, endMs = 0, text = "").endMs)
    }

    @Test
    fun `a segment that ends one millisecond before it starts is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SttSegment(startMs = 5, endMs = 4, text = "x") }
    }
}
