package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The limits on what the store lists: a limit asks for at most that many rows, and a
 * limit larger than the history gets the whole history, not a silent cap.
 *
 * The rows here are far more numerous than any other test in the module holds, because
 * a cap at a round number (5, 1,000) is invisible to a history of a handful of rows.
 */
class StoreLimitsTest {

    private val database = InMemoryHistoryDatabase()
    private val base = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
    private val store = SqliteHistoryStore(database, Clock { base })

    private fun aTranscription(index: Int) = Transcription(
        id = "r-" + index.toString().padStart(4, '0'),
        text = "dictation $index",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = base + index,
    )

    private fun saveRows(count: Int) = repeat(count) { store.save(aTranscription(it)) }

    @Test
    fun `a limit larger than the history returns every row, newest first, past a thousand`() {
        saveRows(1_001)

        val listed = store.list(Int.MAX_VALUE).map { it.id }

        assertEquals(1_001, listed.size)
        assertEquals("r-1000", listed.first())
        assertEquals("r-0000", listed.last())
    }

    @Test
    fun `a limit of a thousand returns the newest thousand of a thousand and one`() {
        saveRows(1_001)

        val listed = store.list(1_000).map { it.id }

        assertEquals(1_000, listed.size)
        assertEquals("r-1000", listed.first())
        assertEquals("r-0001", listed.last())
    }

    @Test
    fun `a limit below the history returns exactly that many of the newest rows`() {
        saveRows(12)

        assertEquals(listOf("r-0011", "r-0010", "r-0009"), store.list(3).map { it.id })
        assertEquals(12, store.list(12).size)
        assertEquals(12, store.list(13).size)
    }

    @Test
    fun `pending tombstones with a limit below the count return that many of the newest`() {
        listOf("old" to 1_000L, "mid" to 2_000L, "new" to 3_000L).forEach { (id, at) ->
            store.applyRemoteDelete(id, deletedAt = at)
        }

        assertEquals(listOf("new", "mid"), store.pendingTombstones(2).map { it.id })
        assertEquals(listOf("new"), store.pendingTombstones(1).map { it.id })
    }

    @Test
    fun `pending tombstones with a limit larger than the count return every one, past a thousand`() {
        repeat(1_001) { store.applyRemoteDelete("d-" + it.toString().padStart(4, '0'), deletedAt = base + it) }

        val pending = store.pendingTombstones(Int.MAX_VALUE).map { it.id }

        assertEquals(1_001, pending.size)
        assertEquals("d-1000", pending.first())
        assertEquals("d-0000", pending.last())
        assertEquals(1_000, store.pendingTombstones(1_000).size)
    }
}
