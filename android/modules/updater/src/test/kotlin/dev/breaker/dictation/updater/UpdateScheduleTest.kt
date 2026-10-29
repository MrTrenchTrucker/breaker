package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the schedule decision for an update call: when an automatic call is due, when the gap
 * after an attempt holds it, and that an admin's forced call always runs. Times are passed in,
 * so these tests read no clock and wait on nothing.
 */
internal class UpdateScheduleTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val gap = 3_600_000L
    private val earliest = Long.MIN_VALUE
    private val latest = Long.MAX_VALUE

    private fun decide(
        at: Long = now,
        lastCheck: Long? = null,
        lastAttempt: Long? = null,
        forced: Boolean = false,
        admin: Boolean = false,
    ): ScheduleDecision = decideUpdateCheck(at, lastCheck, lastAttempt, forced, admin)

    @Test
    fun `an automatic call with no earlier check is due`() {
        assertEquals("android_updater: a never-checked module must be due", ScheduleDecision.Check, decide())
    }

    @Test
    fun `an automatic call exactly one day after the last check is due`() {
        assertEquals(
            "android_updater: a check exactly one day old must be due",
            ScheduleDecision.Check,
            decide(lastCheck = now - day),
        )
    }

    @Test
    fun `an automatic call one millisecond short of a day is not due`() {
        assertEquals(
            "android_updater: a check one millisecond short of a day must not be due",
            ScheduleDecision.NotDue,
            decide(lastCheck = now - day + 1),
        )
    }

    @Test
    fun `an automatic call at the moment of the last check is not due`() {
        assertEquals(
            "android_updater: a check made at this instant must not be due",
            ScheduleDecision.NotDue,
            decide(lastCheck = now),
        )
    }

    @Test
    fun `a last check one millisecond in the future is due`() {
        assertEquals(
            "android_updater: a last check in the future must make the call due",
            ScheduleDecision.Check,
            decide(lastCheck = now + 1),
        )
    }

    @Test
    fun `a last check at the far end of the clock range is due`() {
        assertEquals(
            "android_updater: a far-future last check must make the call due, not wrap to a small gap",
            ScheduleDecision.Check,
            decide(at = earliest, lastCheck = latest),
        )
    }

    @Test
    fun `a day gap that overflows the arithmetic is due`() {
        assertEquals(
            "android_updater: a gap that overflows the subtraction must count as more than a day",
            ScheduleDecision.Check,
            decide(at = latest, lastCheck = earliest),
        )
    }

    @Test
    fun `an automatic call one millisecond inside the gap is not due`() {
        assertEquals(
            "android_updater: an attempt one millisecond inside the gap must hold the call",
            ScheduleDecision.NotDue,
            decide(lastAttempt = now - gap + 1),
        )
    }

    @Test
    fun `an automatic call exactly at the gap limit is due`() {
        assertEquals(
            "android_updater: an attempt exactly the gap ago must not hold the call",
            ScheduleDecision.Check,
            decide(lastAttempt = now - gap),
        )
    }

    @Test
    fun `an attempt made at this moment holds the automatic call`() {
        assertEquals(
            "android_updater: an attempt made at this instant must hold the call",
            ScheduleDecision.NotDue,
            decide(lastAttempt = now),
        )
    }

    @Test
    fun `an attempt one millisecond in the future does not hold the call`() {
        assertEquals(
            "android_updater: an attempt in the future must not hold the call",
            ScheduleDecision.Check,
            decide(lastAttempt = now + 1),
        )
    }

    @Test
    fun `a gap that overflows the arithmetic does not hold the call`() {
        assertEquals(
            "android_updater: a very old attempt whose gap overflows must not hold the call",
            ScheduleDecision.Check,
            decide(at = latest, lastAttempt = earliest),
        )
    }

    @Test
    fun `a due automatic call inside the gap is not due`() {
        assertEquals(
            "android_updater: a due call inside the gap must be held",
            ScheduleDecision.NotDue,
            decide(lastCheck = now - day, lastAttempt = now - gap + 1),
        )
    }

    @Test
    fun `a due automatic call after the gap is due`() {
        assertEquals(
            "android_updater: a due call after the gap must run",
            ScheduleDecision.Check,
            decide(lastCheck = now - day, lastAttempt = now - gap),
        )
    }

    @Test
    fun `an admin forced call is answered CheckForced when the day has not passed`() {
        assertEquals(
            "android_updater: an admin forced call must be CheckForced",
            ScheduleDecision.CheckForced,
            decide(lastCheck = now, forced = true, admin = true),
        )
    }

    @Test
    fun `an admin forced call is not held by the gap`() {
        assertEquals(
            "android_updater: an admin forced call must not be held by the gap",
            ScheduleDecision.CheckForced,
            decide(lastAttempt = now, forced = true, admin = true),
        )
    }

    @Test
    fun `a non-admin forced call that is not due is not due`() {
        assertEquals(
            "android_updater: a non-admin force is treated as an automatic call",
            ScheduleDecision.NotDue,
            decide(lastCheck = now, forced = true, admin = false),
        )
    }

    @Test
    fun `a non-admin forced call that is due is an automatic check`() {
        assertEquals(
            "android_updater: a due non-admin force is an automatic check, not CheckForced",
            ScheduleDecision.Check,
            decide(forced = true, admin = false),
        )
    }

    @Test
    fun `a non-admin forced call inside the gap is not due`() {
        assertEquals(
            "android_updater: a non-admin force must be held by the gap like an automatic call",
            ScheduleDecision.NotDue,
            decide(lastAttempt = now - 1, forced = true, admin = false),
        )
    }

    @Test
    fun `an admin call without force is automatic and not due`() {
        assertEquals(
            "android_updater: an admin without force is an automatic call",
            ScheduleDecision.NotDue,
            decide(lastCheck = now, forced = false, admin = true),
        )
    }

    @Test
    fun `an admin call without force that is due is an automatic check`() {
        assertEquals(
            "android_updater: an admin without force and due is an automatic check",
            ScheduleDecision.Check,
            decide(forced = false, admin = true),
        )
    }

    @Test
    fun `the decision never throws at the limits of the clock range`() {
        val values = listOf<Long?>(null, earliest, -1L, 0L, 1L, now, latest)
        var calls = 0
        for (at in listOf(earliest, now, latest)) {
            for (lastCheck in values) {
                for (lastAttempt in values) {
                    for (forced in listOf(false, true)) {
                        for (admin in listOf(false, true)) {
                            decide(at, lastCheck, lastAttempt, forced, admin)
                            calls++
                        }
                    }
                }
            }
        }
        assertEquals("android_updater: the loop must visit every combination", 3 * 7 * 7 * 2 * 2, calls)
    }

    @Test
    fun `an admin forced call is CheckForced for every ledger combination`() {
        val values = listOf<Long?>(null, earliest, -1L, 0L, 1L, now, latest)
        for (at in listOf(earliest, now, latest)) {
            for (lastCheck in values) {
                for (lastAttempt in values) {
                    assertEquals(
                        "android_updater: an admin forced call must be CheckForced in every case",
                        ScheduleDecision.CheckForced,
                        decide(at, lastCheck, lastAttempt, forced = true, admin = true),
                    )
                }
            }
        }
    }
}
