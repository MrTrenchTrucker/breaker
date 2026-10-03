package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the store does when its database misbehaves: a row it cannot read, and a write that fails.
 *
 * Both are failures to hear about. A list that quietly leaves out a row it cannot map, or a save
 * that reports success when nothing was stored, is how a dictation is lost without anyone knowing.
 */
class StoreFailureTest {

    private val database = InMemoryHistoryDatabase()
    private val clock = Clock { 1_700_000_000_000 }
    private val store = SqliteHistoryStore(database, clock)

    private fun aRow(id: String, source: String, createdAt: Long) = TranscriptionRow(
        id = id,
        text = "load the hay",
        source = source,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    private fun aTranscription(id: String) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `listing a history that holds a row with an unknown source fails, and does not return a shorter list`() {
        database.save(aRow("good-1", "local", createdAt = 3_000))
        database.save(aRow("bogus-1", "bogus", createdAt = 2_000))
        database.save(aRow("good-2", "server", createdAt = 1_000))

        val failure = runCatching { store.list(10) }

        assertTrue("list must throw, it returned ${failure.getOrNull()?.map { it.id }}", failure.isFailure)
        val thrown = failure.exceptionOrNull()
        assertTrue("expected a MappingFailure, got $thrown", thrown is MappingFailure)
        assertTrue("the failure names the row: ${thrown?.message}", thrown?.message.orEmpty().contains("bogus-1"))
    }

    @Test
    fun `a save whose write fails rethrows that failure and leaves nothing stored`() {
        val cause = IllegalStateException("disk full")
        // Writes the row, then fails: only the transaction around the save can take the row back.
        val failing = object : HistoryDatabase by database {
            override fun save(row: TranscriptionRow) {
                database.save(row)
                throw cause
            }
        }
        val fragile = SqliteHistoryStore(failing, clock)

        try {
            fragile.save(aTranscription("t-1"))
            fail("the save must fail when the database fails")
        } catch (thrown: IllegalStateException) {
            assertSame(cause, thrown)
        }

        assertEquals(0, store.count())
        assertEquals(emptyList<Transcription>(), store.list(10))
    }
}
