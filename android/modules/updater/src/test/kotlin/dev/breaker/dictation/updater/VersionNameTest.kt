package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The four-part name order: numeric parts, the exact shape, and null whenever either side is not comparable. */
class VersionNameTest {

    @Test
    fun `a lower last part compares as minus one`() {
        assertEquals("android_updater: lower last part", -1, VersionName.compare("1.2.3.4", "1.2.3.5"))
    }

    @Test
    fun `a higher last part compares as one`() {
        assertEquals("android_updater: higher last part", 1, VersionName.compare("1.2.3.5", "1.2.3.4"))
    }

    @Test
    fun `equal names compare as zero`() {
        assertEquals("android_updater: equal names", 0, VersionName.compare("1.2.3.4", "1.2.3.4"))
    }

    @Test
    fun `a part of ten is higher than a part of nine`() {
        assertEquals("android_updater: ten above nine", 1, VersionName.compare("1.2.10.0", "1.2.9.0"))
        assertEquals("android_updater: nine below ten", -1, VersionName.compare("1.2.9.0", "1.2.10.0"))
    }

    @Test
    fun `the first part decides before any later part`() {
        assertEquals("android_updater: first part higher", 1, VersionName.compare("2.0.0.0", "1.9.9.9"))
        assertEquals("android_updater: first part lower", -1, VersionName.compare("1.9.9.9", "2.0.0.0"))
    }

    @Test
    fun `a part of nine digits is comparable`() {
        val nine = "9".repeat(9)
        assertEquals("android_updater: nine digits", 1, VersionName.compare("1.2.3.$nine", "1.2.3.8"))
    }

    @Test
    fun `a name with three parts is not comparable`() {
        assertNull("android_updater: three parts", VersionName.compare("1.2.3", "1.2.3.4"))
    }

    @Test
    fun `a name with five parts is not comparable`() {
        assertNull("android_updater: five parts", VersionName.compare("1.2.3.4.5", "1.2.3.4"))
    }

    @Test
    fun `a letter in a part is not comparable`() {
        assertNull("android_updater: a letter", VersionName.compare("1.2.3.x", "1.2.3.4"))
    }

    @Test
    fun `an empty name is not comparable`() {
        assertNull("android_updater: empty name", VersionName.compare("", "1.2.3.4"))
    }

    @Test
    fun `a negative part is not comparable`() {
        assertNull("android_updater: minus sign", VersionName.compare("1.2.3.-1", "1.2.3.4"))
    }

    @Test
    fun `a part with a suffix is not comparable`() {
        assertNull("android_updater: suffix", VersionName.compare("1.2.3.4-debug", "1.2.3.4"))
    }

    @Test
    fun `a space before the name is not comparable`() {
        assertNull("android_updater: leading space", VersionName.compare(" 1.2.3.4", "1.2.3.4"))
    }

    @Test
    fun `a part of ten digits is not comparable`() {
        val ten = "1".repeat(10)
        assertNull("android_updater: ten digits", VersionName.compare("1.2.3.$ten", "1.2.3.4"))
    }

    @Test
    fun `an empty part is not comparable`() {
        assertNull("android_updater: empty part", VersionName.compare("1..3.4", "1.2.3.4"))
    }

    @Test
    fun `a trailing dot is not comparable`() {
        assertNull("android_updater: trailing dot", VersionName.compare("1.2.3.4.", "1.2.3.4"))
    }

    @Test
    fun `a non ASCII digit is not comparable`() {
        assertNull("android_updater: non ASCII digit", VersionName.compare("\u0661.2.3.4", "1.2.3.4"))
    }

    @Test
    fun `a bad name on either side makes the answer null`() {
        assertNull("android_updater: bad left side", VersionName.compare("1.2.3", "1.2.3.4"))
        assertNull("android_updater: bad right side", VersionName.compare("1.2.3.4", "1.2.3"))
    }
}
