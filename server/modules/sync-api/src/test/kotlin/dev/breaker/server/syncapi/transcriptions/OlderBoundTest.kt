package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabaseTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// parseOlderBound and render. Each refusal sits beside an accept control, so a parser that refuses
// everything fails the accepted cases and a parser that accepts everything fails the refused ones.
// Long digit runs are built with repeat, so no plain literal carries seven or more digits.
internal class OlderBoundTest : TempDatabaseTest() {

    // Fails when a plain time is refused, for example when the one-part branch returns null.
    @Test
    fun `a plain time and a time with a change number are accepted`() {
        assertEquals(OlderBound(0L, null), parseOlderBound("0"))
        assertEquals(OlderBound(1L, null), parseOlderBound("1"))
        assertEquals(OlderBound(5L, 3L), parseOlderBound("5-3"))
    }

    // Fails when the pattern refuses leading zeros, for example when the time part must start at 1 to 9.
    @Test
    fun `leading zeros are accepted and the value is what counts`() {
        assertEquals(OlderBound(5L, 3L), parseOlderBound("005-03"))
        assertEquals(OlderBound(5L, null), parseOlderBound("0005"))
    }

    // Fails when the time part is capped at fourteen digits, so fifteen digits are refused.
    @Test
    fun `fifteen digits of time and the largest change are accepted`() {
        assertEquals(OlderBound(999_999_999_999_999L, null), parseOlderBound("9".repeat(15)))
        assertEquals(OlderBound(5L, Long.MAX_VALUE), parseOlderBound("5-" + Long.MAX_VALUE.toString()))
    }

    // Fails when a missing bound is accepted as a value (a null text must return null).
    @Test
    fun `a missing or empty bound is refused`() {
        assertNull(parseOlderBound(null))
        assertNull(parseOlderBound(""))
        assertEquals(OlderBound(5L, null), parseOlderBound("5"))
    }

    // Fails when the pattern check is replaced by an emptiness check: Kotlin toLongOrNull reads a plus sign.
    @Test
    fun `letters and signs and spaces in the bound are refused`() {
        assertNull(parseOlderBound("abc"))
        assertNull(parseOlderBound("-1"))
        assertNull(parseOlderBound("-5"))
        assertNull(parseOlderBound("+5"))
        assertNull(parseOlderBound(" 5"))
        assertNull(parseOlderBound("5 "))
        assertNull(parseOlderBound("5-a"))
        assertEquals(OlderBound(5L, null), parseOlderBound("5"))
    }

    // Fails when the change part may repeat, so 5-3-1 is read as the change 3.
    @Test
    fun `a dash with no change number or with a second dash is refused`() {
        assertNull(parseOlderBound("5-"))
        assertNull(parseOlderBound("5--3"))
        assertNull(parseOlderBound("5-3-1"))
        assertEquals(OlderBound(5L, 3L), parseOlderBound("5-3"))
    }

    // Fails when the time part takes sixteen digits (the pattern allows up to fifteen).
    @Test
    fun `sixteen digits of time are refused`() {
        assertNull(parseOlderBound("9".repeat(16)))
        assertNull(parseOlderBound("1".repeat(20)))
        assertEquals(OlderBound(999_999_999_999_999L, null), parseOlderBound("9".repeat(15)))
    }

    // Fails when a change that does not fit a Long is read as zero instead of refused.
    @Test
    fun `a change above the largest long is refused`() {
        assertNull(parseOlderBound("5-" + "9".repeat(19)))
        assertEquals(OlderBound(5L, Long.MAX_VALUE), parseOlderBound("5-" + Long.MAX_VALUE.toString()))
    }

    // Fails when a change of twenty digits gets past the pattern and parses to a value.
    @Test
    fun `a change of twenty digits is refused`() {
        assertNull(parseOlderBound("5-" + "1".repeat(20)))
        assertEquals(OlderBound(5L, 1L), parseOlderBound("5-1"))
    }

    // Fails when render writes a dash and zero for a bound without a change.
    @Test
    fun `render gives the time alone or the time and the change and parses back`() {
        assertEquals("5", OlderBound(5L, null).render())
        assertEquals("5-3", OlderBound(5L, 3L).render())
        assertEquals(OlderBound(5L, 3L), parseOlderBound(OlderBound(5L, 3L).render()))
        assertEquals("5-3", parseOlderBound("005-03")!!.render())
    }
}
