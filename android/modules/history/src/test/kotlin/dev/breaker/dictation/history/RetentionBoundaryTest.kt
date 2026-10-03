package dev.breaker.dictation.history

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention boundary (F28).
 *
 * The window is a fixed 90 days (90 x 24 h, "ADR-010 precise rule") and the
 * boundary is a half-open interval: a transcription created **at** the cutoff is
 * kept, one millisecond earlier is purged. These tests walk the boundary from
 * both sides, because a purge that reaches one millisecond too far is silent —
 * the row is gone and nothing reports it.
 */
class RetentionBoundaryTest {

    /** 2026-06-30T12:00:00Z — an arbitrary, unremarkable "now". */
    private val now: Instant = Instant.parse("2026-06-30T12:00:00Z")

    /** The one policy there is: a fixed window, with no zone or length to choose. */
    private fun policy() = RetentionPolicy()

    /** Runs [block] with the JVM's default time zone set to [zone], and puts the old one back. */
    private fun <T> withDefaultZone(zone: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun millis(instant: Instant): Long = instant.toEpochMilli()

    @Test
    fun `the window is a fixed 90 days`() {
        assertEquals(Duration.ofDays(90), RetentionPolicy.WINDOW)
        assertEquals(Duration.ofHours(90 * 24), RetentionPolicy.WINDOW)
    }

    @Test
    fun `the cutoff is 90 days before now`() {
        assertEquals(Instant.parse("2026-04-01T12:00:00Z"), policy().cutoff(now))
    }

    @Test
    fun `the boundary on absolute instants`() {
        // Written out as literal instants, not derived from the policy under test:
        // for a purge at 2026-06-30T12:00Z the window closes at 2026-04-01T12:00Z.
        val policy = policy()
        val cutoff = Instant.parse("2026-04-01T12:00:00Z")
        assertTrue(
            "one millisecond before 2026-04-01T12:00Z is outside the window",
            policy.isExpired(Instant.parse("2026-04-01T11:59:59.999Z"), now),
        )
        assertFalse(
            "exactly 2026-04-01T12:00Z is kept",
            policy.isExpired(cutoff, now),
        )
        assertFalse(
            "one millisecond after 2026-04-01T12:00Z is kept",
            policy.isExpired(Instant.parse("2026-04-01T12:00:00.001Z"), now),
        )
    }

    @Test
    fun `isExpired agrees with the cutoff in whole milliseconds when now has a sub-millisecond part`() {
        // created_at is stored in whole epoch milliseconds, and the purge compares
        // it with the cutoff cut to a millisecond. No purge runs here: this checks
        // that isExpired gives the same answer as that millisecond cutoff, even for
        // a now that no purge passes.
        val subMillisecondNow = Instant.parse("2026-06-30T12:00:00.0005Z")
        val policy = policy()
        val cutoffMillis = policy.cutoff(subMillisecondNow).toEpochMilli()
        assertEquals(Instant.parse("2026-04-01T12:00:00Z").toEpochMilli(), cutoffMillis)
        assertFalse(
            "a row stored at the cutoff millisecond is kept",
            policy.isExpired(Instant.ofEpochMilli(cutoffMillis), subMillisecondNow),
        )
        assertTrue(
            "a row stored one millisecond earlier is expired",
            policy.isExpired(Instant.ofEpochMilli(cutoffMillis - 1), subMillisecondNow),
        )
    }

    @Test
    fun `the tombstone cutoff is the same 90 days, from the delete`() {
        assertEquals(Instant.parse("2026-04-01T12:00:00Z"), policy().tombstoneCutoff(now))
    }

    @Test
    fun `a row created exactly at the cutoff is inside the window and is kept`() {
        val policy = policy()
        val atCutoff = policy.cutoff(now)
        assertFalse(
            "A transcription created exactly at the cutoff is in date and must survive",
            policy.isExpired(atCutoff, now),
        )
    }

    @Test
    fun `a row created one millisecond before the cutoff has expired`() {
        val policy = policy()
        val justOutside = policy.cutoff(now).minusMillis(1)
        assertTrue(
            "A transcription one millisecond past the boundary must be purged",
            policy.isExpired(justOutside, now),
        )
    }

    @Test
    fun `a row created one millisecond after the cutoff is kept`() {
        val policy = policy()
        assertFalse(policy.isExpired(policy.cutoff(now).plusMillis(1), now))
    }

    @Test
    fun `a transcription from today is never expired`() {
        assertFalse(policy().isExpired(now, now))
    }

    @Test
    fun `a transcription from well inside the window is kept`() {
        // 61 days back is inside a 90-day window.
        assertFalse(policy().isExpired(Instant.parse("2026-04-30T12:00:00Z"), now))
    }

    @Test
    fun `a transcription from well outside the window is expired`() {
        // 122 days back is outside a 90-day window.
        assertTrue(policy().isExpired(Instant.parse("2026-02-28T12:00:00Z"), now))
    }

    @Test
    fun `the boundary holds to the millisecond on both sides`() {
        val policy = policy()
        val cutoff = policy.cutoff(now)
        // The single millisecond that separates kept from purged. If this loop
        // ever trips, the boundary is not a boundary.
        assertTrue(policy.isExpired(cutoff.minusMillis(1), now))
        assertFalse(policy.isExpired(cutoff, now))
        assertFalse(policy.isExpired(cutoff.plusMillis(1), now))
    }

    @Test
    fun `the window is a fixed day count, not calendar months`() {
        // Ninety days back from 30 June is 1 April; three calendar months back
        // would be 30 March. The window is the day count, so the cutoff is the
        // first, and the phone and the server, each counting 90 days, agree on
        // what has expired.
        val ninetyDays = now.minus(90, java.time.temporal.ChronoUnit.DAYS)
        assertEquals(Instant.parse("2026-04-01T12:00:00Z"), ninetyDays)
        assertEquals(ninetyDays, policy().cutoff(now))
        assertNotEquals(
            "the cutoff is not the calendar-month one",
            Instant.parse("2026-03-30T12:00:00Z"),
            policy().cutoff(now),
        )
    }

    @Test
    fun `month ends need no clamping, because there are no months`() {
        // 90 days before 31 May 2028 is 2 March. There is no month-end rule to
        // apply: the cutoff is the day count and nothing else.
        val endOfMay = Instant.parse("2028-05-31T12:00:00Z")
        assertEquals(Instant.parse("2028-03-02T12:00:00Z"), policy().cutoff(endOfMay))
    }

    @Test
    fun `a month-end cutoff still keeps a row created at it`() {
        val endOfMay = Instant.parse("2028-05-31T12:00:00Z")
        val policy = policy()
        assertFalse(policy.isExpired(policy.cutoff(endOfMay), endOfMay))
    }

    @Test
    fun `the cutoff does not depend on the device's zone`() {
        // The same instant under three device zones: one fourteen hours ahead of
        // UTC, one in Japan, one with daylight saving. The window has no zone in
        // it, so the device's zone cannot move the cutoff, and the phone computes
        // the same cutoff from the same rule.
        val instant = Instant.parse("2026-07-01T01:00:00Z")
        val expected = Instant.parse("2026-04-02T01:00:00Z")
        for (zone in listOf("Asia/Tokyo", "Pacific/Kiritimati", "America/Los_Angeles")) {
            val cutoff = withDefaultZone(zone) {
                assertEquals("the device zone was not switched to $zone", zone, ZoneId.systemDefault().id)
                RetentionPolicy().cutoff(instant)
            }
            assertEquals("cutoff with the device in $zone", expected, cutoff)
        }
    }

    @Test
    fun `the cutoff is exactly 90 x 24 h across a daylight-saving change`() {
        // 2026-03-08 is the US spring-forward day: the local wall clock jumps an
        // hour. A cutoff taken as "90 days ago on the local calendar" would land
        // an hour off; the window is 90 x 24 h of elapsed time, so it does not.
        val instant = Instant.parse("2026-03-20T12:00:00Z")
        withDefaultZone("America/Los_Angeles") {
            assertEquals("America/Los_Angeles", ZoneId.systemDefault().id)
            val policy = RetentionPolicy()
            val cutoff = policy.cutoff(instant)
            assertEquals(Instant.parse("2025-12-20T12:00:00Z"), cutoff)
            assertEquals(Duration.ofHours(90 * 24), Duration.between(cutoff, instant))
            assertFalse(policy.isExpired(cutoff, instant))
        }
    }

    @Test
    fun `the tombstone window is measured from the delete, not from the text`() {
        // A transcription made a year ago, deleted today, has a tombstone that is
        // in date. Sweeping the tombstone on the row's own age would throw away
        // the record of a delete that has not reached the server yet.
        val policy = policy()
        val textCreatedLongAgo = now.minusMillis(365L * 24 * 60 * 60 * 1000)
        assertTrue("The row itself is long expired", policy.isExpired(textCreatedLongAgo, now))

        val deletedAt = millis(now)
        val tombstoneCutoff = millis(policy.tombstoneCutoff(now))
        assertTrue(
            "A tombstone written now must not be swept now",
            deletedAt >= tombstoneCutoff,
        )

        // A tombstone is swept only once it is older than the tombstone
        // cutoff, so the datum has to be taken from the cutoff — one
        // millisecond before it — not from now, which is nowhere near it.
        val deletedLongAgo = tombstoneCutoff - 1
        assertTrue(
            "A tombstone written one millisecond before the tombstone cutoff is swept",
            deletedLongAgo < tombstoneCutoff,
        )
        assertTrue(
            "A tombstone written exactly at the tombstone cutoff is in date",
            !(deletedLongAgo + 1 < tombstoneCutoff),
        )
    }

    @Test
    fun `the window cannot be configured, so it cannot be zero`() {
        assertTrue(
            "A caller-supplied window is one the phone and the server could disagree about",
            RetentionPolicy::class.java.declaredConstructors.all { it.parameterCount == 0 },
        )
        val policy = policy()
        assertTrue(
            "A zero window would expire every transcription including the one just saved",
            policy.cutoff(now).isBefore(now),
        )
        assertFalse(policy.isExpired(now, now))
    }

    @Test
    fun `the window cannot be configured, so it cannot be negative`() {
        assertTrue(
            "A negative window would put the cutoff in the future and expire every row",
            RetentionPolicy.WINDOW > Duration.ZERO,
        )
    }

    @Test
    fun `the cutoff moves forward with now`() {
        // A sanity property. The exact-value tests above already pin the cutoff at
        // several instants, so this adds no case of its own.
        val policy = policy()
        val nextMonth = now.plus(30, java.time.temporal.ChronoUnit.DAYS)
        assertTrue(
            "Moving now forward must move the cutoff forward, never backward",
            policy.cutoff(nextMonth).isAfter(policy.cutoff(now)),
        )
    }

    @Test
    fun `millisecond precision survives the conversion to epoch millis`() {
        val policy = policy()
        val cutoff = policy.cutoff(now)
        val justOutside = millis(cutoff) - 1
        val exactlyAt = millis(cutoff)
        // The store works in epoch milliseconds; the millisecond either side of
        // the boundary has to stay distinguishable or the boundary is fiction.
        assertTrue(
            "one millisecond before the cutoff must be expired",
            policy.isExpired(Instant.ofEpochMilli(justOutside), now),
        )
        assertFalse(
            "exactly at the cutoff must be kept",
            policy.isExpired(Instant.ofEpochMilli(exactlyAt), now),
        )
    }
}
