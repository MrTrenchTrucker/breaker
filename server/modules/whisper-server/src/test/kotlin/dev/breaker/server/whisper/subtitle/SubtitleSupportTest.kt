package dev.breaker.server.whisper.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure shaping helpers: text collapsing, time clamping, millisecond rounding and time rendering.

internal class SubtitleSupportTest {

    @Test
    fun `whitespace runs and line breaks collapse to one space`() {
        assertEquals("subtitle: a run of spaces becomes one space", "a b", collapseToOneLine("a   b"))
        assertEquals("subtitle: a line feed becomes one space", "a b", collapseToOneLine("a\nb"))
        assertEquals("subtitle: a tab becomes one space", "a b", collapseToOneLine("a\tb"))
        assertEquals("subtitle: mixed blanks and both ends trimmed", "a b", collapseToOneLine("  a \t \r\n b  "))
        assertEquals("subtitle: only blanks collapse to the empty string", "", collapseToOneLine(" \n\t\r "))
    }

    @Test
    fun `a negative start is clamped to zero`() {
        val (start, end) = clampTimes(-0.5, 1.0)
        assertEquals("subtitle: a negative start becomes zero", 0.0, start, 0.0)
        assertEquals("subtitle: the end is left alone", 1.0, end, 0.0)
    }

    @Test
    fun `an end before the start is clamped to the start`() {
        val (start, end) = clampTimes(2.0, 1.0)
        assertEquals("subtitle: the start is left alone", 2.0, start, 0.0)
        assertEquals("subtitle: an end before the start becomes the start", 2.0, end, 0.0)
    }

    @Test
    fun `half up rounding at the millisecond boundary`() {
        assertEquals("subtitle: 62.5 ms rounds up to 63", 63L, millisOf(0.0625))
        assertEquals("subtitle: 187.5 ms rounds up to 188", 188L, millisOf(0.1875))
        assertEquals("subtitle: just under the half stays at 62", 62L, millisOf(0.0624))
        assertEquals("subtitle: just over the half rounds to 63", 63L, millisOf(0.0626))
        assertEquals("subtitle: a whole second is exact", 1000L, millisOf(1.0))
    }

    @Test
    fun `hours above 99 are not wrapped`() {
        assertEquals("subtitle: one hundred hours renders in full", "100:00:00,000", renderTime(100L * 3600 * 1000, ','))
        assertEquals("subtitle: hours are not wrapped at 24", "25:00:00,000", renderTime(25L * 3600 * 1000, ','))
        assertEquals("subtitle: minutes and seconds are zero padded", "01:02:03.004", renderTime(3723004L, '.'))
        assertEquals("subtitle: a sub second time renders three millis", "00:00:00,063", renderTime(63L, ','))
    }
}
