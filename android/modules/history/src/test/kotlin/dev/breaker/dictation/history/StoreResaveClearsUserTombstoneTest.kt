package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What a save of an id does to the tombstone the user's own delete left for it.
 *
 * The rule, built: a save that stores a row under an id which holds a tombstone
 * whose reason is `Tombstone.Reason.USER` removes that tombstone in the same
 * transaction as the row. The user's new dictation is the undo of the user's
 * own delete, and a delete that is undone leaves nothing pending for sync.
 * A tombstone whose reason is REMOTE or RETENTION is not a local delete: the
 * server made it, or the retention sweep made it, and a save does not undo it.
 *
 * The eight tests each run twice: on a fresh in-memory store and on a fresh
 * JVM twin on a fresh real-SQL engine, so a test that saves and deletes never
 * sees another test's rows.
 */
class StoreResaveClearsUserTombstoneTest {

    private val now = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()
    private var nowMillis = now
    private val clock = Clock { nowMillis }

    /** The instant the retention window closes for a purge at [now]. */
    private val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()

    private fun aTranscription(id: String, createdAt: Long = now) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    /** Runs [body] once on a fresh in-memory store and once on a fresh JVM twin on a fresh engine. */
    private fun onBothDatabases(body: (SqliteHistoryStore) -> Unit) {
        body(SqliteHistoryStore(InMemoryHistoryDatabase(), clock))
        DesktopSqlite().use { engine ->
            body(SqliteHistoryStore(JdbcHistoryDatabase(engine), clock))
        }
    }

    // -- the rule, row by row --------------------------------------------------

    @Test
    fun `a save after a delete brings the row back live and clears the pending tombstone`() {
        onBothDatabases { store ->
            store.save(aTranscription("x"))
            assertTrue(store.delete("x"))
            assertEquals(
                "the delete left its tombstone",
                listOf("x" to Tombstone.Reason.USER),
                store.pendingTombstones(10).map { it.id to it.reason },
            )

            store.save(aTranscription("x"))

            assertEquals(listOf("x"), store.list(10).map { it.id })
            assertEquals(
                "the user's new dictation undid the user's own delete",
                emptyList<Tombstone>(),
                store.pendingTombstones(10),
            )
            assertEquals(1, store.count())
        }
    }

    @Test
    fun `a save with no tombstone invents none and removes nothing`() {
        onBothDatabases { store ->
            store.save(aTranscription("x"))

            assertEquals(listOf("x"), store.list(10).map { it.id })
            assertEquals(
                "a save with no tombstone for the id invents none",
                emptyList<Tombstone>(),
                store.pendingTombstones(10),
            )
            assertEquals(1, store.count())
        }
    }

    @Test
    fun `a save of one id leaves the other ids tombstones in place`() {
        onBothDatabases { store ->
            store.save(aTranscription("x"))
            store.save(aTranscription("y"))
            assertTrue(store.delete("x"))
            assertTrue(store.delete("y"))
            assertEquals(setOf("x", "y"), store.pendingTombstones(10).map { it.id }.toSet())

            store.save(aTranscription("x"))

            assertEquals(
                "the other id's tombstone stays, so the clear is per-id, not per-reason",
                listOf("y" to Tombstone.Reason.USER),
                store.pendingTombstones(10).map { it.id to it.reason },
            )
            assertEquals(listOf("x"), store.list(10).map { it.id })
        }
    }

    @Test
    fun `a remote tombstone is held across a local re-save and the row state stays as today`() {
        onBothDatabases { store ->
            store.save(aTranscription("x"))
            assertTrue(store.applyRemoteDelete("x", now + 1))
            // Today's state, stated and kept: the remote delete took the live row away
            // and left its tombstone pending; the part that stays decided is not broken here.
            assertEquals(emptyList<String>(), store.list(10).map { it.id })
            assertEquals(
                listOf("x" to Tombstone.Reason.REMOTE),
                store.pendingTombstones(10).map { it.id to it.reason },
            )
            assertEquals(0, store.count())

            store.save(aTranscription("x"))

            assertEquals(listOf("x"), store.list(10).map { it.id })
            assertEquals(
                "the server wins for a delete made on another device: the remote tombstone is held",
                listOf("x" to Tombstone.Reason.REMOTE),
                store.pendingTombstones(10).map { it.id to it.reason },
            )
            assertEquals(1, store.count())
        }
    }

    @Test
    fun `a retention tombstone is held across a local re-save`() {
        onBothDatabases { store ->
            store.save(aTranscription("x", createdAt = cutoff - 1))
            val report = store.purgeExpired(now)
            assertEquals(1, report.purged)
            assertEquals(
                listOf("x" to Tombstone.Reason.RETENTION),
                store.pendingTombstones(10).map { it.id to it.reason },
            )

            store.save(aTranscription("x"))

            assertEquals(listOf("x"), store.list(10).map { it.id })
            assertEquals(
                "the retention sweep's tombstone is not a local delete, so a save does not undo it",
                listOf("x" to Tombstone.Reason.RETENTION),
                store.pendingTombstones(10).map { it.id to it.reason },
            )
            assertEquals(1, store.count())
        }
    }

    @Test
    fun `a delete after a cleared save writes one tombstone with the later time`() {
        onBothDatabases { store ->
            store.save(aTranscription("x"))
            assertTrue(store.delete("x"))
            store.save(aTranscription("x"))
            assertEquals(
                "the clear removed the first delete's tombstone",
                emptyList<Tombstone>(),
                store.pendingTombstones(10),
            )

            nowMillis += 1_000
            assertTrue(store.delete("x"))

            val pending = store.pendingTombstones(10)
            assertEquals(
                "one tombstone per id, the later delete's",
                listOf("x" to Tombstone.Reason.USER),
                pending.map { it.id to it.reason },
            )
            assertEquals(nowMillis, pending.single().deletedAt)
        }
    }

    @Test
    fun `a failing clear leaves the save unapplied because they are one transaction`() {
        // What this shows that a failing save does not: the clear is part of the
        // save's one transaction, so a failure in the clear takes the row back.
        val base = InMemoryHistoryDatabase()
        val setup = SqliteHistoryStore(base, clock)
        setup.save(aTranscription("x"))
        assertTrue(setup.delete("x"))

        val cause = IllegalStateException("the clear failed")
        val clearInsideTransaction = mutableListOf<Boolean>()
        val failing = object : HistoryDatabase by base {
            override fun deleteTombstoneByIdAndReason(id: String, reason: Tombstone.Reason): Int {
                clearInsideTransaction += base.inTransaction
                throw cause
            }
        }
        val fragile = SqliteHistoryStore(failing, clock)

        try {
            fragile.save(aTranscription("x"))
            fail("the save must fail when the clear fails, they are one transaction")
        } catch (thrown: IllegalStateException) {
            assertSame(cause, thrown)
        }

        assertEquals("the clear ran inside the save's transaction", listOf(true), clearInsideTransaction)
        assertEquals("the row was not stored", 0, base.countTranscriptions())
        assertEquals(
            "the tombstone the delete wrote is still there",
            listOf("x" to "user"),
            base.newestTombstones(10).map { it.id to it.reason },
        )
    }

    @Test
    fun `a save that fails leaves the user tombstone in place`() {
        // What this shows that a failing clear does not: a save that fails before
        // it stores must not have cleared either; the tombstone outlives it.
        val database = InMemoryHistoryDatabase()
        val setup = SqliteHistoryStore(database, clock)
        setup.save(aTranscription("x"))
        assertTrue(setup.delete("x"))

        val cause = IllegalStateException("disk full")
        val failing = object : HistoryDatabase by database {
            override fun save(row: TranscriptionRow) {
                database.save(row)
                throw cause
            }
        }
        val fragile = SqliteHistoryStore(failing, clock)

        try {
            fragile.save(aTranscription("x"))
            fail("the save must fail when the database fails")
        } catch (thrown: IllegalStateException) {
            assertSame(cause, thrown)
        }

        assertEquals("the failed save stored nothing", 0, database.countTranscriptions())
        assertEquals(
            "the user tombstone is untouched by a save that failed",
            listOf("x" to "user"),
            database.newestTombstones(10).map { it.id to it.reason },
        )
    }
}
