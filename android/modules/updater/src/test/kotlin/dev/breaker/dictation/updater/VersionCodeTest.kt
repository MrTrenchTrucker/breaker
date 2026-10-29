package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the canonical reading of the release code and the integer comparison that decides a newer release. */
internal class VersionCodeTest {
    @Test
    fun `a canonical version code parses to its integer`() {
        assertEquals(1, VersionCode.parse("1"))
        assertEquals(10, VersionCode.parse("10"))
        assertEquals(2_100_000_000, VersionCode.parse("2100000000"))
    }

    @Test
    fun `zero and a leading zero are refused`() {
        assertNull(VersionCode.parse("0"))
        assertNull(VersionCode.parse("00"))
        assertNull(VersionCode.parse("01"))
    }

    @Test
    fun `a sign, a space, a point or a letter is refused`() {
        for (bad in listOf("+1", "-1", " 1", "1 ", "1.0", "1e3", "x1")) {
            assertNull(bad, VersionCode.parse(bad))
        }
    }

    @Test
    fun `the largest code is 2100000000 and anything above it is refused`() {
        assertNull(VersionCode.parse("2100000001"))
        assertNull(VersionCode.parse("9999999999"))
        assertNull(VersionCode.parse("9".repeat(20)))
    }

    @Test
    fun `a non-ASCII digit is refused`() {
        assertNull(VersionCode.parse("\u0661"))
        assertNull(VersionCode.parse("\uFF11"))
    }

    @Test
    fun `a newer release is one with a greater integer code`() {
        assertTrue(isNewerRelease(1, 2))
        assertTrue(isNewerRelease(2_099_999_999, 2_100_000_000))
        assertFalse(isNewerRelease(2, 2))
        assertFalse(isNewerRelease(2, 1))
    }
}
