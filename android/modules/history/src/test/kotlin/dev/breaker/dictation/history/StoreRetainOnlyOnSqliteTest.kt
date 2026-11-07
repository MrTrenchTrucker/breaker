package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.PullSink
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prune after a full list, run all the way down to real SQL. */
internal class StoreRetainOnlyOnSqliteTest {

    private val sqlite = DesktopSqlite()
    private val now = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()
    private val database = JdbcHistoryDatabase(sqlite)
    private val clock = Clock { now }
    private val store: HistoryStore = SqliteHistoryStore(database, clock)

    private val sink: PullSink = store as PullSink

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
    fun `five rows held keep two exactly and the answer is three`() {
        store.save(aTranscription("keep-1", now))
        store.save(aTranscription("keep-2", now))
        store.save(aTranscription("drop-1", now))
        store.save(aTranscription("drop-2", now))
        store.save(aTranscription("drop-3", now))

        val removed = sink.retainOnly(keep = setOf("keep-1", "keep-2"))

        assertEquals(3, removed)
        assertEquals(setOf("keep-1", "keep-2"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `no tombstone is written for a removed id and a held tombstone stays`() {
        store.save(aTranscription("drop-1", now))
        store.save(aTranscription("tomb-1", now))
        sink.applyRemoteDelete(id = "tomb-1", deletedAt = now)

        val removed = sink.retainOnly(keep = emptySet())

        assertEquals(1, removed)
        assertTrue(store.list(10).isEmpty())
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals("tomb-1", tombstones[0].id)
    }

    @Test
    fun `the empty set removes every row`() {
        store.save(aTranscription("t-1", now))
        store.save(aTranscription("t-2", now))

        val removed = sink.retainOnly(keep = emptySet())

        assertEquals(2, removed)
        assertTrue(store.list(10).isEmpty())
    }

    @Test
    fun `a keep set holding every id removes none`() {
        store.save(aTranscription("t-1", now))
        store.save(aTranscription("t-2", now))

        val removed = sink.retainOnly(keep = setOf("t-1", "t-2"))

        assertEquals(0, removed)
        assertEquals(setOf("t-1", "t-2"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `a keep set naming unknown ids removes nothing extra`() {
        store.save(aTranscription("t-1", now))

        val removed = sink.retainOnly(keep = setOf("t-1", "never-1", "never-2"))

        assertEquals(0, removed)
        assertEquals(setOf("t-1"), store.list(10).map { it.id }.toSet())
    }
}
