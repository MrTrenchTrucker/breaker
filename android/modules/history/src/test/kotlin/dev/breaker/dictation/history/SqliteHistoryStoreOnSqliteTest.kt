package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import java.time.Duration
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's retention rule, run all the way down to real SQL.
 *
 * [SqliteHistoryStoreTest] runs the store against an in-memory double, which
 * shows what the store *asks* for. Here the same store runs on
 * [JdbcHistoryDatabase], so the cutoff the rule computes goes through the real
 * statements, bound as text, and the rows that survive are the rows SQLite says
 * survive. The rule is a fixed 90 days (ADR-010 precise rule), so for a purge at
 * 2026-06-30T12:00:00Z the window closes at 2026-04-01T12:00:00Z; both are
 * written out here rather than taken from the policy under test.
 *
 * The limit is [DesktopSqlite]'s: the desktop SQLite in the test-only jar, not
 * the phone's, and the Android adapter itself is not run.
 */
class SqliteHistoryStoreOnSqliteTest {

    private val sqlite = DesktopSqlite()
    private val now = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()
    private val database = JdbcHistoryDatabase(sqlite)
    private val clock = Clock { now }
    private val store: HistoryStore = SqliteHistoryStore(database, clock)

    /** The instant the window closes for a purge at [now], in epoch milliseconds. */
    private val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()

    @After
    fun closeDatabase() = sqlite.close()

    private fun typed(): SqliteHistoryStore = store as SqliteHistoryStore

    private fun aTranscription(id: String, createdAt: Long, text: String = "load the hay") = Transcription(
        id = id,
        text = text,
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    @Test
    fun `the purge keeps a row at the cutoff and removes the millisecond before it`() {
        store.save(aTranscription("after", cutoff + 1))
        store.save(aTranscription("at", cutoff))
        store.save(aTranscription("before", cutoff - 1))

        val report = typed().purgeExpired()

        assertEquals(cutoff, report.cutoff)
        assertEquals(1, report.purged)
        assertEquals(1, report.tombstoned)
        assertEquals(listOf("after", "at"), store.list(10).map { it.id })
    }

    @Test
    fun `the purge leaves a retention tombstone for what it removed, and only that`() {
        store.save(aTranscription("at", cutoff))
        store.save(aTranscription("before", cutoff - 1))

        typed().purgeExpired()

        val tombstone = typed().pendingTombstones(10).single()
        assertEquals("before", tombstone.id)
        assertEquals(Tombstone.Reason.RETENTION, tombstone.reason)
        assertEquals(now, tombstone.deletedAt)
    }

    @Test
    fun `the tombstone sweep keeps a tombstone at the cutoff and drops the millisecond before it`() {
        typed().applyRemoteDelete("after", deletedAt = cutoff + 1)
        typed().applyRemoteDelete("at", deletedAt = cutoff)
        typed().applyRemoteDelete("before", deletedAt = cutoff - 1)

        val report = typed().purgeExpiredTombstones()

        assertEquals(cutoff, report.cutoff)
        assertEquals(1, report.dropped)
        assertEquals(listOf("after", "at"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a tombstone sweep leaves a live transcription alone, however old it is`() {
        // The live row is 500 days old, so a sweep that reached the transcriptions
        // by age would expire it. The tombstone is past the window on its own.
        store.save(aTranscription("live", now - Duration.ofDays(500).toMillis()))
        typed().applyRemoteDelete("gone", deletedAt = now - Duration.ofDays(120).toMillis())
        assertEquals(1, typed().count())
        assertEquals(listOf("gone"), typed().pendingTombstones(10).map { it.id })

        val report = typed().purgeExpiredTombstones()

        assertEquals(1, report.dropped)
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
        assertEquals(listOf("live"), store.list(10).map { it.id })
        assertEquals(1, typed().count())
    }

    @Test
    fun `a tombstone sweep drops the tombstone of a row saved again, and only the tombstone`() {
        // Deleted, then dictated again under the same id: a tombstone past the
        // window and a live row now share one id. The live row is recent, so only
        // a sweep that matched rows to tombstones by id could take it.
        store.save(aTranscription("again", now - Duration.ofDays(200).toMillis(), text = "first time"))
        assertTrue(typed().applyRemoteDelete("again", deletedAt = now - Duration.ofDays(120).toMillis()))
        store.save(aTranscription("again", now, text = "second time"))
        assertEquals(listOf("again"), typed().pendingTombstones(10).map { it.id })
        assertEquals(listOf("second time"), store.list(10).map { it.text })

        val report = typed().purgeExpiredTombstones()

        assertEquals(1, report.dropped)
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
        assertEquals(listOf("second time"), store.list(10).map { it.text })
        assertEquals(1, typed().count())
    }

    @Test
    fun `the purge leaves a tombstone that is past the window for the sweep to drop`() {
        // A tombstone recorded before the cutoff is already sweepable. The purge
        // uses that same cutoff for the rows, and must not use it for tombstones:
        // the sweep is the separate call, and only it drops this one.
        typed().applyRemoteDelete("aged", deletedAt = cutoff - 1)
        store.save(aTranscription("expired", cutoff - 1))
        assertEquals(listOf("aged"), typed().pendingTombstones(10).map { it.id })

        val report = typed().purgeExpired()

        assertEquals(1, report.purged)
        assertEquals(setOf("aged", "expired"), typed().pendingTombstones(10).map { it.id }.toSet())
        assertEquals("the sweep still finds it", 1, typed().purgeExpiredTombstones().dropped)
        assertEquals(listOf("expired"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a purge whose tombstone write fails part way is rolled back on real SQL`() {
        // The purge deletes the rows and tombstones them in one transaction. If it
        // stops at the second tombstone, the ROLLBACK must bring every deleted row
        // back and undo the first tombstone.
        store.save(aTranscription("old-1", cutoff - 1))
        store.save(aTranscription("old-2", cutoff - 2))
        store.save(aTranscription("old-3", cutoff - 3))
        store.save(aTranscription("in-date", cutoff))
        var tombstoneWrites = 0
        val exploding = object : HistoryDatabase by database {
            override fun putTombstone(tombstone: Tombstone) {
                tombstoneWrites++
                if (tombstoneWrites == 2) throw IllegalStateException("disk full")
                database.putTombstone(tombstone)
            }
        }
        val fragile = SqliteHistoryStore(exploding, clock)

        val failure = runCatching { fragile.purgeExpired() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure?.message)
        assertEquals("the purge reached its second tombstone", 2, tombstoneWrites)
        assertEquals(4, typed().count())
        assertEquals(setOf("old-1", "old-2", "old-3", "in-date"), store.list(10).map { it.id }.toSet())
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `a remote delete whose tombstone write fails is rolled back on real SQL`() {
        store.save(aTranscription("t-1", now))
        val exploding = object : HistoryDatabase by database {
            override fun putTombstone(tombstone: Tombstone): Unit =
                throw IllegalStateException("disk full")
        }
        val fragile = SqliteHistoryStore(exploding, clock)

        val failure = runCatching { fragile.applyRemoteDelete("t-1", deletedAt = now) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure?.message)
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `a negative limit is refused, because on real SQL it would return every row`() {
        // `LIMIT -1` is no limit at all, so it returns every row. The store's own
        // check is the only thing between a negative limit and the whole history.
        store.save(aTranscription("a", 1_000))
        store.save(aTranscription("b", 2_000))

        val outcome = runCatching { store.list(-1) }

        assertFalse("list(-1) returned ${outcome.getOrNull()?.size} rows", outcome.isSuccess)
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        assertEquals("limit cannot be negative: -1", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `a negative limit on the tombstone list is refused, because on real SQL it would return every one`() {
        typed().applyRemoteDelete("a", deletedAt = 1_000)
        typed().applyRemoteDelete("b", deletedAt = 2_000)

        val outcome = runCatching { typed().pendingTombstones(-1) }

        assertFalse("pendingTombstones(-1) returned ${outcome.getOrNull()?.size} tombstones", outcome.isSuccess)
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        assertEquals("limit cannot be negative: -1", outcome.exceptionOrNull()?.message)
    }

    @Test
    fun `an empty transcription lives its whole lifecycle on real SQL like any other`() {
        store.save(aTranscription("silent", cutoff, text = ""))
        assertEquals(listOf(""), store.list(10).map { it.text })

        store.save(aTranscription("silent", cutoff, text = "not silent after all"))
        assertEquals(listOf("not silent after all"), store.list(10).map { it.text })

        store.save(aTranscription("silent", cutoff, text = ""))
        assertEquals(1, typed().count())

        store.save(aTranscription("silent-old", cutoff - 1, text = ""))
        val report = typed().purgeExpired()
        assertEquals("the empty row created before the cutoff is purged", 1, report.purged)
        assertEquals("the empty row created at the cutoff is kept", listOf("silent"), store.list(10).map { it.id })

        assertTrue(store.delete("silent"))
        assertEquals(0, typed().count())
        assertEquals(setOf("silent", "silent-old"), typed().pendingTombstones(10).map { it.id }.toSet())
    }

    @Test
    fun `history is listed newest first on real SQL, ties broken by id`() {
        // Two tied pairs saved in opposite id order, so a missing tiebreak is wrong
        // whichever way SQLite orders ties.
        store.save(aTranscription("a", 1_000))
        store.save(aTranscription("r", 2_000))
        store.save(aTranscription("q", 2_000))
        store.save(aTranscription("m", 3_000))
        store.save(aTranscription("n", 3_000))

        assertEquals(listOf("n", "m", "r", "q", "a"), store.list(10).map { it.id })
        assertEquals(listOf("n", "m"), store.list(2).map { it.id })
    }
}
