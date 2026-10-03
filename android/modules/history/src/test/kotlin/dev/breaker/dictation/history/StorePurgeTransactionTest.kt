package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the purge does inside its transaction, and what its report counts.
 *
 * The purge reads the ids it will tombstone, deletes the rows and writes the tombstones. A row
 * saved between the read and the delete would be deleted with no tombstone, unless the read is
 * inside the same transaction. The report's two counts come from two different calls and must
 * not be copied from one another.
 */
class StorePurgeTransactionTest {

    private val database = InMemoryHistoryDatabase()
    private val now = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()

    /** 2026-04-01T12:00:00Z, written out, so the tests do not take the rule from the code they check. */
    private val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()
    private val clock = Clock { now }

    private fun aTranscription(id: String, createdAt: Long) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    private fun saveRows(store: SqliteHistoryStore) {
        store.save(aTranscription("old-1", cutoff - 1))
        store.save(aTranscription("old-2", cutoff - 2))
        store.save(aTranscription("old-3", cutoff - 3))
        store.save(aTranscription("in-date", cutoff))
    }

    @Test
    fun `the purge reads its ids, deletes its rows and writes every tombstone inside one transaction`() {
        val idsRead = mutableListOf<Boolean>()
        val deletes = mutableListOf<Boolean>()
        val tombstoneWrites = mutableListOf<Boolean>()
        val recording = object : HistoryDatabase by database {
            override fun idsCreatedBefore(cutoff: Long): List<String> {
                idsRead += database.inTransaction
                return database.idsCreatedBefore(cutoff)
            }

            override fun deleteCreatedBefore(cutoff: Long): Int {
                deletes += database.inTransaction
                return database.deleteCreatedBefore(cutoff)
            }

            override fun putTombstone(tombstone: Tombstone) {
                tombstoneWrites += database.inTransaction
                database.putTombstone(tombstone)
            }
        }
        val store = SqliteHistoryStore(recording, clock)
        saveRows(store)

        val report = store.purgeExpired(now)

        assertEquals(3, report.purged)
        assertEquals("the ids were read once, inside the transaction", listOf(true), idsRead)
        assertEquals("the rows were deleted once, inside the transaction", listOf(true), deletes)
        assertEquals("three tombstones, each written inside the transaction", listOf(true, true, true), tombstoneWrites)
        assertFalse("the transaction is over when the purge returns", database.inTransaction)
    }

    @Test
    fun `the report counts the rows the delete removed and the tombstones written, each from its own call`() {
        var tombstoneWrites = 0
        // The delete removes all three rows and reports two: the two numbers must stay apart in the report.
        val miscounting = object : HistoryDatabase by database {
            override fun deleteCreatedBefore(cutoff: Long): Int = database.deleteCreatedBefore(cutoff) - 1

            override fun putTombstone(tombstone: Tombstone) {
                tombstoneWrites++
                database.putTombstone(tombstone)
            }
        }
        val store = SqliteHistoryStore(miscounting, clock)
        saveRows(store)

        val report = store.purgeExpired(now)

        assertEquals("the purge counts what the delete said it removed", 2, report.purged)
        assertEquals("three rows were due, three tombstones were written", 3, tombstoneWrites)
        assertEquals("the purge counts the tombstones it wrote", 3, report.tombstoned)
        assertFalse("two purged against three tombstoned is not consistent", report.isConsistent)
        assertEquals(cutoff, report.cutoff)
    }

    @Test
    fun `the report counts a delete that removed more than the ids it read`() {
        // The other direction: the delete reports five, three ids were read. Five against three is not consistent.
        val miscounting = object : HistoryDatabase by database {
            override fun deleteCreatedBefore(cutoff: Long): Int = database.deleteCreatedBefore(cutoff) + 2
        }
        val store = SqliteHistoryStore(miscounting, clock)
        saveRows(store)

        val report = store.purgeExpired(now)

        assertEquals(5, report.purged)
        assertEquals(3, report.tombstoned)
        assertFalse(report.isConsistent)
    }
}
