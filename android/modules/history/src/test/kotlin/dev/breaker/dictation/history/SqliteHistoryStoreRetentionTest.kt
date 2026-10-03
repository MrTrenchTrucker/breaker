package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module's invariants, exercised through the store.
 *
 * Each test here corresponds to a line on the module card, and each is written
 * so that the behaviour it protects is the *only* thing that could make it
 * fail. Where a boundary exists, the row is placed on the correct side of it by
 * an explicit millisecond.
 */

internal class SqliteHistoryStoreRetentionTest : SqliteHistoryStoreTestBase() {

    // ── F28: the retention window, through the store ─────────────────────

    @Test
    fun `the purge removes only what is outside the window`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "inside", createdAt = cutoff + 1))
        store.save(aTranscription(id = "at-the-boundary", createdAt = cutoff))
        store.save(aTranscription(id = "outside", createdAt = cutoff - 1))

        val report = typed().purgeExpired(nowMillis)

        assertEquals(1, report.purged)
        assertEquals(listOf("at-the-boundary", "inside"), store.list(10).map { it.id }.sorted())
    }

    @Test
    fun `a transcription created exactly at the cutoff survives the purge`() {
        // The single most dangerous millisecond in this module.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "on-the-line", text = "still in date", createdAt = cutoff))

        typed().purgeExpired(nowMillis)

        assertEquals("A row created at the cutoff is in date and must not be purged", 1, typed().count())
        assertEquals("still in date", store.list(10).single().text)
    }

    @Test
    fun `a transcription one millisecond before the cutoff is purged`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "just-out", text = "out of date", createdAt = cutoff - 1))

        val report = typed().purgeExpired(nowMillis)

        assertEquals(1, report.purged)
        assertEquals(emptyList<Transcription>(), store.list(10))
    }

    @Test
    fun `the purge reports the cutoff it used`() {
        // The boundary is worth being able to look at after the fact.
        val report = typed().purgeExpired(nowMillis)
        assertEquals(policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli(), report.cutoff)
    }

    @Test
    fun `the purge removes rows from every source, and keeps the in-date ones`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-local", source = TranscriptionSource.LOCAL, createdAt = cutoff - 1))
        store.save(aTranscription(id = "old-server", source = TranscriptionSource.SERVER, createdAt = cutoff - 1))
        store.save(aTranscription(id = "new-local", source = TranscriptionSource.LOCAL, createdAt = cutoff))
        store.save(aTranscription(id = "new-server", source = TranscriptionSource.SERVER, createdAt = cutoff))

        typed().purgeExpired(nowMillis)

        assertEquals(setOf("new-local", "new-server"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `every purged row is tombstoned so the server's copy goes too`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))
        store.save(aTranscription(id = "old-2", createdAt = cutoff - 2))
        store.save(aTranscription(id = "in-date", createdAt = cutoff))

        val report = typed().purgeExpired(nowMillis)

        assertTrue("A purge that deletes without tombstoning lets the server hand the rows back", report.isConsistent)
        assertEquals(2, report.purged)
        assertEquals(
            setOf("old-1", "old-2"),
            typed().pendingTombstones(10).map { it.id }.toSet(),
        )
    }

    @Test
    fun `a retention tombstone says why it was written`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))
        typed().purgeExpired(nowMillis)
        assertEquals(Tombstone.Reason.RETENTION, typed().pendingTombstones(10).single().reason)
    }

    @Test
    fun `the purge does not tombstone a row that is still in date`() {
        // The inverse of the failure above: a tombstone for a live row would
        // delete it on the next sync, from a device that still has it.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "in-date", createdAt = cutoff))

        val report = typed().purgeExpired(nowMillis)

        assertEquals(0, report.purged)
        assertEquals(0, report.tombstoned)
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `the purge never sweeps the tombstones it just wrote`() {
        // A purge that tombstoned a row and then expired the tombstone in the
        // same call would leave the server holding a copy forever, and the
        // tombstone table would look empty and healthy.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))

        typed().purgeExpired(nowMillis)

        assertEquals(1, typed().pendingTombstones(10).size)
    }

    @Test
    fun `the purge leaves a tombstone that is past the window for the sweep to drop`() {
        // A tombstone recorded before the cutoff is already sweepable. The purge
        // uses that same cutoff for the rows, and must not use it for tombstones:
        // the sweep is the separate call, and only it drops this one.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        typed().applyRemoteDelete("aged", deletedAt = cutoff - 1)
        store.save(aTranscription(id = "expired", createdAt = cutoff - 1))
        assertEquals(listOf("aged"), typed().pendingTombstones(10).map { it.id })

        val report = typed().purgeExpired(nowMillis)

        assertEquals(1, report.purged)
        assertEquals(setOf("aged", "expired"), typed().pendingTombstones(10).map { it.id }.toSet())
        assertEquals("the sweep still finds it", 1, typed().purgeExpiredTombstones(nowMillis).dropped)
        assertEquals(listOf("expired"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `purging twice removes nothing the second time`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))

        assertEquals(1, typed().purgeExpired(nowMillis).purged)
        assertEquals(0, typed().purgeExpired(nowMillis).purged)
    }

    @Test
    fun `purging an empty history is a no-op, not a failure`() {
        val report = typed().purgeExpired(nowMillis)
        assertEquals(0, report.purged)
        assertTrue(report.isConsistent)
    }

    @Test
    fun `a transcription saved after a purge is not caught by that same purge`() {
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))
        store.save(aTranscription(id = "new-1", createdAt = nowMillis))

        typed().purgeExpired(nowMillis)

        assertEquals(listOf("new-1"), store.list(10).map { it.id })
    }

    @Test
    fun `the purge runs on the clock when no instant is given`() {
        // The no-argument form is the one a cleanup job calls, so it has to read
        // the clock rather than quietly using some other notion of now.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))
        store.save(aTranscription(id = "new-1", createdAt = nowMillis))

        assertEquals(1, typed().purgeExpired().purged)
        assertEquals(listOf("new-1"), store.list(10).map { it.id })
    }

    @Test
    fun `the clock is what a delete is stamped with`() {
        nowMillis = 1_234_567_890_000
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")
        assertEquals(1_234_567_890_000, typed().pendingTombstones(10).single().deletedAt)
    }

    @Test
    fun `a save that fails part way through leaves nothing half-done`() {
        // The delete and its tombstone are one unit: a delete that removed the
        // row and then failed to record the tombstone would let the server
        // hand the transcription straight back.
        val exploding = object : HistoryDatabase by database {
            override fun putTombstone(tombstone: Tombstone): Unit =
                throw IllegalStateException("disk full")
        }
        val fragile = SqliteHistoryStore(exploding, clock)
        store.save(aTranscription(id = "t-1"))

        val failure = runCatching { fragile.delete("t-1") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "The row must survive a delete whose tombstone could not be written",
            1,
            typed().count(),
        )
    }

    @Test
    fun `a purge whose tombstone write fails part way leaves every row in place and no tombstone`() {
        // The purge deletes the rows and tombstones them in one unit. If it stops
        // at the second tombstone, no row may be gone and none may be tombstoned:
        // a row deleted without its tombstone is a row the server hands back.
        val cutoff = policy.cutoff(Instant.ofEpochMilli(nowMillis)).toEpochMilli()
        store.save(aTranscription(id = "old-1", createdAt = cutoff - 1))
        store.save(aTranscription(id = "old-2", createdAt = cutoff - 2))
        store.save(aTranscription(id = "old-3", createdAt = cutoff - 3))
        store.save(aTranscription(id = "in-date", createdAt = cutoff))
        var tombstoneWrites = 0
        val exploding = object : HistoryDatabase by database {
            override fun putTombstone(tombstone: Tombstone) {
                tombstoneWrites++
                if (tombstoneWrites == 2) throw IllegalStateException("disk full")
                database.putTombstone(tombstone)
            }
        }
        val fragile = SqliteHistoryStore(exploding, clock)

        val failure = runCatching { fragile.purgeExpired(nowMillis) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure?.message)
        assertEquals("the purge reached its second tombstone", 2, tombstoneWrites)
        assertEquals(4, typed().count())
        assertEquals(setOf("old-1", "old-2", "old-3", "in-date"), store.list(10).map { it.id }.toSet())
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `a remote delete whose tombstone write fails leaves the row it would have deleted`() {
        store.save(aTranscription(id = "t-1"))
        val exploding = object : HistoryDatabase by database {
            override fun putTombstone(tombstone: Tombstone): Unit =
                throw IllegalStateException("disk full")
        }
        val fragile = SqliteHistoryStore(exploding, clock)

        val failure = runCatching { fragile.applyRemoteDelete("t-1", deletedAt = nowMillis) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure?.message)
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `closing the store closes the database`() {
        store.save(aTranscription())
        typed().close()
        assertFalse(database.isOpen)
    }

    // ── the retention rule is a fixed 90 days, written out as instants ────

    @Test
    fun `the purge uses a fixed 90 days, on absolute instants`() {
        // Literal instants, not taken from the policy under test. The clock reads
        // 2026-06-30T12:00:00Z, so the window closes at 2026-04-01T12:00:00Z.
        val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()
        store.save(aTranscription(id = "after", createdAt = cutoff + 1))
        store.save(aTranscription(id = "at", createdAt = cutoff))
        store.save(aTranscription(id = "before", createdAt = cutoff - 1))

        val report = typed().purgeExpired()

        assertEquals("the cutoff the purge reports", cutoff, report.cutoff)
        assertEquals(listOf("after", "at"), store.list(10).map { it.id })
        val tombstone = typed().pendingTombstones(10).single()
        assertEquals("before", tombstone.id)
        assertEquals(Tombstone.Reason.RETENTION, tombstone.reason)
        assertEquals("the purge stamps its tombstones with the clock", nowMillis, tombstone.deletedAt)
    }

    @Test
    fun `the tombstone sweep uses the same 90 days, measured from the delete`() {
        val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()
        typed().applyRemoteDelete("before", deletedAt = cutoff - 1)
        typed().applyRemoteDelete("at", deletedAt = cutoff)
        typed().applyRemoteDelete("after", deletedAt = cutoff + 1)

        val report = typed().purgeExpiredTombstones()

        assertEquals("the cutoff the sweep reports", cutoff, report.cutoff)
        assertEquals(1, report.dropped)
        assertEquals(listOf("after", "at"), typed().pendingTombstones(10).map { it.id })
    }

    // ── empty text is a normal value everywhere, and nothing special-cases it ──

    @Test
    fun `an empty transcription is saved, listed, replaced and deleted like any other`() {
        val port: HistoryStore = store

        port.save(aTranscription(id = "silent", text = ""))
        assertEquals(listOf(""), port.list(10).map { it.text })

        port.save(aTranscription(id = "silent", text = "not silent after all"))
        assertEquals(
            "a same-id save replaces the empty row",
            listOf("not silent after all"),
            port.list(10).map { it.text },
        )

        port.save(aTranscription(id = "silent", text = ""))
        assertEquals("and an empty save replaces a row that had text", listOf(""), port.list(10).map { it.text })
        assertEquals(1, typed().count())

        assertTrue("an empty row is deleted like any other", port.delete("silent"))
        assertEquals(0, typed().count())
        val tombstone = typed().pendingTombstones(10).single()
        assertEquals("silent", tombstone.id)
        assertEquals(Tombstone.Reason.USER, tombstone.reason)
    }

    @Test
    fun `an empty transcription is purged and tombstoned like any other, and kept at the cutoff`() {
        val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()
        store.save(aTranscription(id = "silent-old", text = "", createdAt = cutoff - 1))
        store.save(aTranscription(id = "silent-edge", text = "", createdAt = cutoff))

        val report = typed().purgeExpired()

        assertEquals(1, report.purged)
        assertEquals(1, report.tombstoned)
        assertEquals(listOf("silent-edge"), store.list(10).map { it.id })
        assertEquals(listOf("silent-old"), typed().pendingTombstones(10).map { it.id })
    }
}
