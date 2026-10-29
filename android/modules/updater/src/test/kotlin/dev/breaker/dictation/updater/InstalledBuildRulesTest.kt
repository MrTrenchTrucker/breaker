package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rule that turns the platform's version facts into the installed reading. */
internal class InstalledBuildRulesTest {

    @Test
    fun `a code of 1 with a name gives the readable build of that code and name`() {
        assertEquals(InstalledReading.Known(InstalledBuild(1, "1.0")), installedReadingFrom(1L, "1.0"))
    }

    @Test
    fun `the largest readable code is 2100000000`() {
        assertEquals(
            InstalledReading.Known(InstalledBuild(2_100_000_000, "top")),
            installedReadingFrom(2_100_000_000L, "top"),
        )
    }

    @Test
    fun `a code one above the largest readable code is unreadable`() {
        assertEquals(InstalledReading.Unreadable, installedReadingFrom(2_100_000_001L, "over"))
    }

    @Test
    fun `a code of 0 is unreadable`() {
        assertEquals(InstalledReading.Unreadable, installedReadingFrom(0L, "zero"))
    }

    @Test
    fun `a code of 4294967301 is unreadable and is not cut down to 5`() {
        assertEquals(InstalledReading.Unreadable, installedReadingFrom(4_294_967_301L, "wide"))
    }

    @Test
    fun `a null or blank name gives the code text`() {
        assertEquals(InstalledReading.Known(InstalledBuild(7, "7")), installedReadingFrom(7L, null))
        assertEquals(InstalledReading.Known(InstalledBuild(7, "7")), installedReadingFrom(7L, "   "))
    }
}
