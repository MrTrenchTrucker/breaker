package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The start-up purge clock-skip guard, end to end over the real bookkeeping store on a temp folder.
 *
 * The purge is a fake that counts its calls; the clock and the automatic-time
 * flag are passed in. Together the tests below pin the rule: purge strictly when the clock is
 * trustworthy, suspect a forward jump only when it is not and the last real purge is old enough, defer
 * a suspected jump for at most seven days, key the trigger on the persisted last purge and never on a
 * new record, and treat a first run with no baseline as no suspicion at all. The guard's own result is
 * the app's [PurgeOutcome]: the skip tests assert the [PurgeOutcome.Skipped] case, the GO tests the
 * [PurgeOutcome.Purged] case and read the wrapped history report.
 */
class PurgeGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val MS_PER_DAY = 24L * 60L * 60L * 1000L

    /** The automatic-time seam read to a boolean, so the fake fun interface is exercised. */
    private fun autoTime(on: Boolean): Boolean = AutomaticTimeOn { on }.isOn()

    private fun store(): PurgeBookkeepingStore = PurgeBookkeepingStore(File(tmp.root, PurgeBookkeepingStore.FILE_NAME))

    /** A purge fake that counts its calls and reports the given counts. */
    private class PurgeFake(private val purged: Int = 0, private val tombstoned: Int = 0) {
        var calls = 0
        fun run(now: Long): PurgeReport {
            calls++
            return PurgeReport(purged = purged, tombstoned = tombstoned, cutoff = now)
        }
    }

    /** Asserts the outcome is the purge case and returns the wrapped history report. */
    private fun assertPurged(outcome: PurgeOutcome, message: String): PurgeReport {
        assertTrue(message, outcome is PurgeOutcome.Purged)
        return (outcome as PurgeOutcome.Purged).report
    }

    // (i) auto OFF + jump -> purge NOT called, deferral persisted.
    @Test
    fun `auto time off and a forward jump skips the purge`() {
        val store = store()
        val now = 2_000_000_000_000L
        store.recordLastPurgeAt(now - 40 * MS_PER_DAY)
        val purge = PurgeFake(purged = 5, tombstoned = 5)

        val outcome = runGuardedStartPurge(
            now = now,
            autoTimeOn = autoTime(false),
            store = store,
            purge = { purge.run(now) },
        )

        assertEquals("a forward jump with auto time off must not purge", 0, purge.calls)
        assertTrue("the outcome must be the skip case", outcome is PurgeOutcome.Skipped)
        assertEquals("the deferral must be persisted", now, store.deferralStartedAt())
    }

    // (ii) then advance now with a new record stamped under the jump (lastPurgeAt unchanged) -> STILL no purge.
    @Test
    fun `a new record stamped under the jump does not end the deferral`() {
        val store = store()
        val start = 2_000_000_000_000L
        val lastPurgeAt = start - 40 * MS_PER_DAY
        store.recordLastPurgeAt(lastPurgeAt)

        // The first skipped start begins the deferral.
        val first = PurgeFake()
        runGuardedStartPurge(now = start, autoTimeOn = autoTime(false), store = store, purge = { first.run(start) })
        assertEquals("the first suspected jump must not purge", 0, first.calls)
        val deferral = store.deferralStartedAt()
        assertEquals("the deferral begins at the first skipped start", start, deferral)

        // Advance now; a new record would carry a timestamp near `later`, but lastPurgeAt is unchanged
        // because only a purge writes it. The trigger must follow lastPurgeAt, not the newest record.
        val later = deferral + 3 * MS_PER_DAY
        val second = PurgeFake()
        val outcome = runGuardedStartPurge(now = later, autoTimeOn = autoTime(false), store = store, purge = { second.run(later) })

        assertEquals("a new record under the jump must still not purge", 0, second.calls)
        assertTrue("the skip must be reported again", outcome is PurgeOutcome.Skipped)
        assertEquals("lastPurgeAt must be unchanged (only a purge writes it)", lastPurgeAt, store.lastPurgeAt())
        assertEquals("the deferral must not be restarted by a new record", deferral, store.deferralStartedAt())
    }

    // (iii) 7 days after deferralStartedAt -> purge runs (GO); after the cap purge, deferral is unset again.
    @Test
    fun `seven days after the deferral began the purge runs and the deferral is cleared`() {
        val store = store()
        val start = 2_000_000_000_000L
        store.recordLastPurgeAt(start - 40 * MS_PER_DAY)

        runGuardedStartPurge(now = start, autoTimeOn = autoTime(false), store = store, purge = { error("must not purge before the cap") })
        val deferral = store.deferralStartedAt()

        val capDay = deferral + 7 * MS_PER_DAY
        val purge = PurgeFake(purged = 6, tombstoned = 6)
        val outcome = runGuardedStartPurge(now = capDay, autoTimeOn = autoTime(false), store = store, purge = { purge.run(capDay) })

        assertEquals("after the 7-day cap the purge must run", 1, purge.calls)
        val report = assertPurged(outcome, "the cap purge is a GO, not a skip")
        assertEquals("the cap purge carries its own report", 6, report.purged)
        assertEquals("lastPurgeAt must be updated by the cap purge", capDay, store.lastPurgeAt())
        assertEquals("the deferral must be cleared after the purge runs", PurgeBookkeepingStore.UNSET, store.deferralStartedAt())
    }

    // (iv) auto ON + jump -> purge runs (ADR-010 strict).
    @Test
    fun `auto time on runs the purge even across a forward jump`() {
        val store = store()
        val now = 2_000_000_000_000L
        store.recordLastPurgeAt(now - 400 * MS_PER_DAY)
        val purge = PurgeFake(purged = 9, tombstoned = 9)

        val outcome = runGuardedStartPurge(now = now, autoTimeOn = autoTime(true), store = store, purge = { purge.run(now) })

        assertEquals("auto time on must purge strictly across a jump", 1, purge.calls)
        val report = assertPurged(outcome, "auto time on is a GO, not a skip")
        assertEquals("the auto-time-on purge carries its own report", 9, report.purged)
        assertEquals("lastPurgeAt must be updated", now, store.lastPurgeAt())
    }

    // (v) control: normal clock, auto ON, expired rows present -> purge deletes (guard does not neuter it).
    @Test
    fun `a normal auto-time-on clock purges its expired rows`() {
        val store = store()
        val now = 2_000_000_000_000L
        store.recordLastPurgeAt(now - 10 * MS_PER_DAY)
        val cutoff = now - 90 * MS_PER_DAY
        val purge = PurgeFake(purged = 4, tombstoned = 4)

        val outcome = runGuardedStartPurge(now = now, autoTimeOn = autoTime(true), store = store, purge = { purge.run(cutoff) })

        assertEquals("a normal clock must purge its expired rows", 1, purge.calls)
        val report = assertPurged(outcome, "a normal auto-time-on clock is a GO, not a skip")
        assertEquals(4, report.purged)
        assertTrue(report.isConsistent)
    }

    // (vi) auto OFF, lastPurgeAt unset -> purge runs, lastPurgeAt saved.
    @Test
    fun `auto time off with no baseline purges and records the instant`() {
        val store = store()
        val now = 2_000_000_000_000L
        // lastPurgeAt is unset (0): the first time this code runs, no baseline to suspect a jump from.
        val purge = PurgeFake(purged = 2, tombstoned = 2)

        val outcome = runGuardedStartPurge(now = now, autoTimeOn = autoTime(false), store = store, purge = { purge.run(now) })

        assertEquals("unset lastPurgeAt is no suspicion: the purge must run", 1, purge.calls)
        val report = assertPurged(outcome, "no baseline is a GO, not a skip")
        assertEquals("the no-baseline purge carries its own report", 2, report.purged)
        assertEquals("the instant must be recorded", now, store.lastPurgeAt())
        assertEquals("no deferral is started with no baseline", PurgeBookkeepingStore.UNSET, store.deferralStartedAt())
    }

    @Test
    fun `decide goes within the grace window when auto time is off`() {
        val now = 2_000_000_000_000L
        assertEquals(PurgeDecision.GO, PurgeGuard.decide(now, autoTimeOn = false, lastPurgeAt = now - 29 * MS_PER_DAY, deferralStartedAt = 0))
    }

    @Test
    fun `decide suspects a jump just past the grace window`() {
        val now = 2_000_000_000_000L
        // Exactly at the grace edge a row is kept (no jump); one millisecond past it, a jump is suspected.
        assertEquals(PurgeDecision.GO, PurgeGuard.decide(now, autoTimeOn = false, lastPurgeAt = now - 30 * MS_PER_DAY, deferralStartedAt = 0))
        assertEquals(PurgeDecision.DEFERRED_SKEW, PurgeGuard.decide(now, autoTimeOn = false, lastPurgeAt = now - 30 * MS_PER_DAY - 1, deferralStartedAt = 0))
    }

    @Test
    fun `decide caps the deferral exactly at seven days`() {
        val now = 2_000_000_000_000L
        val deferral = now - 7 * MS_PER_DAY
        // Exactly seven days after the deferral the purge runs; one millisecond earlier it is still deferred.
        assertEquals(PurgeDecision.GO, PurgeGuard.decide(now, autoTimeOn = false, lastPurgeAt = now - 40 * MS_PER_DAY, deferralStartedAt = deferral))
        assertEquals(PurgeDecision.DEFERRED_SKEW, PurgeGuard.decide(now - 1, autoTimeOn = false, lastPurgeAt = now - 40 * MS_PER_DAY, deferralStartedAt = deferral))
    }

    @Test
    fun `the bookkeeping store reads unset before any write`() {
        val store = store()
        assertEquals(PurgeBookkeepingStore.UNSET, store.lastPurgeAt())
        assertEquals(PurgeBookkeepingStore.UNSET, store.deferralStartedAt())
    }

    @Test
    fun `the bookkeeping store keeps both instants and clears the deferral`() {
        val store = store()
        val now = 2_000_000_000_000L
        store.recordLastPurgeAt(now)
        store.recordDeferralStartedAt(now + 1)
        assertEquals(now, store.lastPurgeAt())
        assertEquals(now + 1, store.deferralStartedAt())
        store.clearDeferralStartedAt()
        assertEquals("the last purge survives a deferral clear", now, store.lastPurgeAt())
        assertEquals("the deferral is cleared", PurgeBookkeepingStore.UNSET, store.deferralStartedAt())
    }
}
