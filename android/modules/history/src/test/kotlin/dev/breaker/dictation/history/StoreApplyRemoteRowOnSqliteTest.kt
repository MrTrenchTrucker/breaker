package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.PullSink
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class StoreApplyRemoteRowOnSqliteTest {

    private val sqlite = DesktopSqlite()
    private val database = JdbcHistoryDatabase(sqlite)
    private val now = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()
    private val clock = Clock { now }
    private val store: HistoryStore = SqliteHistoryStore(database, clock)
    private val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()

    @After
    fun closeDatabase() = sqlite.close()

    private fun typed(): SqliteHistoryStore = store as SqliteHistoryStore
    private fun sink(): PullSink = typed()

    private fun aTranscription(
        id: String,
        text: String = "load the hay",
        source: TranscriptionSource = TranscriptionSource.LOCAL,
        model: String = "small.en",
        durationMs: Long = 1_200L,
        createdAt: Long = now,
    ) = Transcription(
        id = id,
        text = text,
        source = source,
        model = model,
        durationMs = durationMs,
        createdAt = createdAt,
    )

    private fun pulled(id: String, text: String = "pulled text") = aTranscription(
        id = id,
        text = text,
        source = TranscriptionSource.SERVER,
        model = "small.en",
        durationMs = 1_200L,
        createdAt = now,
    )

    @Test
    fun `a pulled row with no tombstone is stored and listed on real SQL`() {
        val row = pulled("t-1")
        assertTrue(sink().applyRemoteRow(row))
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        val stored = store.list(10).single { it.id == "t-1" }
        assertEquals("pulled text", stored.text)
        assertEquals(TranscriptionSource.SERVER, stored.source)
        assertEquals(now, stored.createdAt)
    }

    @Test
    fun `a pulled row is refused and not stored for a USER tombstone on real SQL`() {
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")
        assertFalse(sink().applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
        assertEquals(listOf("t-1"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a pulled row is refused and not stored for a REMOTE tombstone on real SQL`() {
        store.save(aTranscription(id = "t-1"))
        sink().applyRemoteDelete(id = "t-1", deletedAt = now)
        assertFalse(sink().applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
        assertEquals(listOf("t-1"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a pulled row is refused and not stored for a RETENTION tombstone on real SQL`() {
        store.save(aTranscription(id = "t-1", createdAt = cutoff - 1))
        typed().purgeExpired()
        assertFalse(sink().applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
        assertEquals(listOf("t-1"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a held row of the same id is replaced on real SQL`() {
        store.save(aTranscription(id = "t-1", text = "first"))
        assertTrue(sink().applyRemoteRow(pulled("t-1", text = "second")))
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        assertEquals("second", store.list(10).single { it.id == "t-1" }.text)
        assertEquals(1, typed().count())
    }
}
