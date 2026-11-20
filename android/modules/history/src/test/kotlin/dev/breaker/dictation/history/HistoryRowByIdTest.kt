package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SqliteHistoryStore.rowById: returns the row when present, null when absent, logs nothing. */
class HistoryRowByIdTest {

    private fun transcription(id: String, createdAt: Long): Transcription =
        Transcription(id = id, text = "t", source = TranscriptionSource.LOCAL, model = "m", durationMs = 0, createdAt = createdAt)

    private class FixedClock : Clock {
        override fun nowEpochMillis(): Long = 1_700_000_000_000L
    }

    /** A minimal HistoryDatabase that holds rows in a map. */
    private class FakeHistoryDatabase : HistoryDatabase {
        val rows = mutableMapOf<String, TranscriptionRow>()
        override fun save(row: TranscriptionRow) { rows[row.id] = row }
        override fun newest(limit: Int): List<TranscriptionRow> = emptyList()
        override fun deleteById(id: String): Boolean = rows.remove(id) != null
        override fun idsCreatedBefore(cutoff: Long): List<String> = emptyList()
        override fun deleteCreatedBefore(cutoff: Long): Int = 0
        override fun allIds(): List<String> = rows.keys.toList()
        override fun putTombstone(tombstone: Tombstone) {}
        override fun newestTombstones(limit: Int): List<Tombstone.StoredRow> = emptyList()
        override fun newestUserTombstones(limit: Int): List<Tombstone.StoredRow> = emptyList()
        override fun newestUserTombstonesAfter(lastId: String, limit: Int): List<Tombstone.StoredRow> = emptyList()
        override fun deleteTombstonesRecordedBefore(cutoff: Long): Int = 0
        override fun deleteAllTombstones(): Int = 0
        override fun deleteTombstoneByIdAndReason(id: String, reason: Tombstone.Reason): Int = 0
        override fun hasTombstone(id: String): Boolean = false
        override fun rowById(clientId: String): TranscriptionRow? = rows[clientId]
        override fun countTranscriptions(): Int = rows.size
        override fun <T> transaction(block: () -> T): T = block()
        override fun close() {}
    }

    @Test
    fun `rowById returns the stored row when present`() {
        val db = FakeHistoryDatabase()
        val row = TranscriptionRow.of(transcription("r-1", 100L))
        db.save(row)
        val store = SqliteHistoryStore(database = db, clock = FixedClock())
        val result = store.rowById("r-1")
        assertEquals("the id matches", "r-1", result?.id)
        assertEquals("the text matches", "t", result?.text)
    }

    @Test
    fun `rowById returns null when the row is absent`() {
        val db = FakeHistoryDatabase()
        val store = SqliteHistoryStore(database = db, clock = FixedClock())
        assertNull("no row for an unknown id", store.rowById("missing"))
    }

    @Test
    fun `rowById returns null for an id whose row was deleted`() {
        val db = FakeHistoryDatabase()
        db.save(TranscriptionRow.of(transcription("r-2", 200L)))
        db.deleteById("r-2")
        val store = SqliteHistoryStore(database = db, clock = FixedClock())
        assertNull("a deleted id returns null", store.rowById("r-2"))
    }
}
