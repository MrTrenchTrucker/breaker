package dev.breaker.dictation.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The due decision of the daily check, pinned with literal numbers (no constant of the code is reused). */
class UpdateScheduleTest {

    @Test
    fun `never checked is due`() {
        assertTrue("android_updater: a never checked app is due", UpdateSchedule.isDue(null, 1_000L))
    }

    @Test
    fun `exactly 24 hours is not due`() {
        assertFalse(
            "android_updater: exactly 24 hours is not due",
            UpdateSchedule.isDue(1_000L, 1_000L + 86_400_000L),
        )
    }

    @Test
    fun `24 hours and 1 ms is due`() {
        assertTrue(
            "android_updater: 24 hours and 1 ms is due",
            UpdateSchedule.isDue(1_000L, 1_000L + 86_400_001L),
        )
    }

    @Test
    fun `1 ms after the check is not due`() {
        assertFalse("android_updater: 1 ms after the check is not due", UpdateSchedule.isDue(1_000L, 1_001L))
    }

    @Test
    fun `the clock moved back is due`() {
        assertTrue("android_updater: a clock that moved back is due", UpdateSchedule.isDue(5_000L, 4_999L))
    }

    @Test
    fun `a gap larger than a Long counts as due`() {
        assertTrue(
            "android_updater: a gap too large for a Long is due",
            UpdateSchedule.isDue(Long.MIN_VALUE, Long.MAX_VALUE),
        )
    }
}
