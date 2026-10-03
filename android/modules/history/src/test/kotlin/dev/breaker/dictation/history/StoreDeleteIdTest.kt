package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tombstone a delete writes carries the id exactly as it was passed: surrounding blanks, mixed case and
 * non-ASCII characters intact. A tombstone under a tidied-up id names a row the server does not hold, and the
 * delete never reaches it.
 */
class StoreDeleteIdTest {

    private val ids = listOf("  t-1 ", "Mixed-Case-ID", "déjà-vu-日本")
    private val clock = Clock { 1_700_000_000_000 }
    private val sqlite = DesktopSqlite()

    @After
    fun closeDatabase() = sqlite.close()

    private fun aTranscription(id: String) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = 1_700_000_000_000,
    )

    private fun assertEveryIdIsTombstonedAsPassed(store: SqliteHistoryStore) {
        ids.forEach { store.save(aTranscription(it)) }

        ids.forEach { assertTrue("delete of '$it'", store.delete(it)) }

        assertEquals(ids.toSet(), store.pendingTombstones(10).map { it.id }.toSet())
        assertEquals(ids.size, store.pendingTombstones(10).size)
    }

    @Test
    fun `a delete tombstones the id exactly as passed, blanks, case and non-ASCII kept`() {
        assertEveryIdIsTombstonedAsPassed(SqliteHistoryStore(InMemoryHistoryDatabase(), clock))
    }

    @Test
    fun `on real SQL a delete tombstones the id exactly as passed, blanks, case and non-ASCII kept`() {
        assertEveryIdIsTombstonedAsPassed(SqliteHistoryStore(JdbcHistoryDatabase(sqlite), clock))
    }
}
