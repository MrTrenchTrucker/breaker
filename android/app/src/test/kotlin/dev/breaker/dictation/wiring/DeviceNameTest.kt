package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour of [deviceNameOf], one named case per row of the table below, each computed
 * by hand against the order: usable parts trimmed, joined, walked by code point with every
 * ISO control character and every lone surrogate code point dropped, trimmed, clamped to
 * 64 code points, trimmed again, blank to null. Every non-null answer is also checked by
 * [passesValidator].
 */
class DeviceNameTest {

    /**
     * The test-side copy of the auth-client validator (HandoffFinish.kt, lines 139 to 150):
     * 1 to 64 code points, no ISO control character, no lone surrogate code point.
     */
    private fun passesValidator(name: String) {
        val count = name.codePointCount(0, name.length)
        assertTrue("the name is 1 to 64 code points", count in 1..64)
        var i = 0
        while (i < name.length) {
            val point = name.codePointAt(i)
            assertFalse("no ISO control character", Character.isISOControl(point))
            assertFalse("no lone surrogate code point", point in 0xD800..0xDFFF)
            i += Character.charCount(point)
        }
    }

    private fun named(out: String?, want: String?) {
        if (want == null) {
            assertNull("the name is null", out)
        } else {
            assertNotNull("the name is not null", out)
            assertEquals("the name text", want, out)
            passesValidator(out!!)
        }
    }

    @Test
    fun `the join keeps the manufacturer first with one space`() {
        named(deviceNameOf("Google", "Pixel 8"), "Google Pixel 8")
    }

    @Test
    fun `a model that already starts with the manufacturer stays whole`() {
        named(deviceNameOf("google", "GOOGLE Pixel 9"), "GOOGLE Pixel 9")
    }

    @Test
    fun `a model that starts with the manufacturer only after trimming stays whole`() {
        named(deviceNameOf(" Pixel", "Pixel 8"), "Pixel 8")
    }

    @Test
    fun `blanks at both ends are trimmed around the join`() {
        named(deviceNameOf(" Google", " Pixel"), "Google Pixel")
    }

    @Test
    fun `control characters in both parts are dropped before the trim`() {
        named(deviceNameOf("Goo\u0001gle", "P\u0002x"), "Google Px")
    }

    @Test
    fun `a control dropped between two spaces leaves two spaces, not collapsed`() {
        named(deviceNameOf("a \u0001 b", null), "a  b")
    }

    @Test
    fun `a control dropped at the end leaves no blank behind`() {
        named(deviceNameOf("a \u0001", null), "a")
    }

    @Test
    fun `a 63 code point name is kept whole`() {
        named(deviceNameOf("A".repeat(63), null), "A".repeat(63))
    }

    @Test
    fun `a cut that lands on a blank trims the end`() {
        named(deviceNameOf("A".repeat(63) + " B", null), "A".repeat(63))
    }

    @Test
    fun `a join of 63 code points is kept whole`() {
        named(deviceNameOf("A".repeat(31), "B".repeat(31)), "A".repeat(31) + " " + "B".repeat(31))
    }

    @Test
    fun `a join of exactly 64 code points is kept whole`() {
        named(deviceNameOf("A".repeat(31), "B".repeat(32)), "A".repeat(31) + " " + "B".repeat(32))
    }

    @Test
    fun `the 65th code point is cut on the boundary`() {
        named(deviceNameOf("A".repeat(31), "B".repeat(33)), "A".repeat(31) + " " + "B".repeat(32))
    }

    @Test
    fun `a 64 code point name with a pair at the end is kept whole`() {
        named(deviceNameOf("A".repeat(62), "\uD83D\uDE00"), "A".repeat(62) + " " + "\uD83D\uDE00")
    }

    @Test
    fun `a 65 code point name with a pair at the end drops the pair whole`() {
        named(deviceNameOf("A".repeat(63), "\uD83D\uDE00"), "A".repeat(63))
    }

    @Test
    fun `a 63 code point name with a pair at the end is kept whole`() {
        val want = "A".repeat(60) + " " + "\uD83D\uDE00\uD83D\uDE00"
        named(deviceNameOf("A".repeat(60), "\uD83D\uDE00\uD83D\uDE00"), want)
    }

    @Test
    fun `a lone surrogate in the input is dropped with the walk by code point`() {
        named(deviceNameOf("A\uD800B", "C"), "AB C")
    }

    @Test
    fun `one usable part alone is the name`() {
        named(deviceNameOf(null, "Pixel"), "Pixel")
    }

    @Test
    fun `two null parts give no name`() {
        named(deviceNameOf(null, null), null)
    }

    @Test
    fun `a null manufacturer and a blank model give no name`() {
        named(deviceNameOf(null, "  "), null)
    }

    @Test
    fun `two blank parts give no name`() {
        named(deviceNameOf("  ", "  "), null)
    }

    @Test
    fun `a leading blank and control clear and the rest is clamped to 64`() {
        named(deviceNameOf(" \u0001" + "A".repeat(64), null), "A".repeat(64))
    }

    @Test
    fun `a name of only a control character is null`() {
        named(deviceNameOf("\u0001", null), null)
    }

    @Test
    fun `a blank left at the start by a drop is cut by the clamp and trimmed`() {
        named(deviceNameOf("\u0001 A" + "A".repeat(64), null), "A".repeat(64))
    }
}
