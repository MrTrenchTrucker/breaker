package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's limits, its unmappable-row failure, sub-second clock and the audio column, run down to real SQL.
 *
 * The in-memory tests ([StoreLimitsTest], [StoreSubSecondTest], [StoreFailureTest]) show what the store asks for;
 * these run the same asks through [JdbcHistoryDatabase] on [DesktopSqlite], where the limit is a bound `LIMIT ?`
 * and the cutoff is bound as text. The limit is [DesktopSqlite]'s: the desktop SQLite, and the Android adapter
 * itself is not run.
 */
class StoreOnSqliteTest {

    private val sqlite = DesktopSqlite()
    private val database = JdbcHistoryDatabase(sqlite)
    private val base = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
    private var nowMillis = base
    private val store = SqliteHistoryStore(database, Clock { nowMillis })

    @After
    fun closeDatabase() = sqlite.close()

    private fun aTranscription(id: String, createdAt: Long) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    private fun aRow(id: String, audioPath: String?, createdAt: Long = base) = TranscriptionRow(
        id = id,
        text = "load the hay",
        source = "local",
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
        audioPath = audioPath,
    )

    // ── limits ───────────────────────────────────────────────────────────

    @Test
    fun `on real SQL a limit larger than the history returns every row, newest first, past a thousand`() {
        repeat(1_001) { store.save(aTranscription("r-" + it.toString().padStart(4, '0'), base + it)) }

        val listed = store.list(Int.MAX_VALUE).map { it.id }

        assertEquals(1_001, listed.size)
        assertEquals("r-1000", listed.first())
        assertEquals("r-0000", listed.last())
    }

    @Test
    fun `on real SQL a limit of a thousand returns the newest thousand of a thousand and one`() {
        repeat(1_001) { store.save(aTranscription("r-" + it.toString().padStart(4, '0'), base + it)) }

        val listed = store.list(1_000).map { it.id }

        assertEquals(1_000, listed.size)
        assertEquals("r-1000", listed.first())
        assertEquals("r-0001", listed.last())
    }

    @Test
    fun `on real SQL pending tombstones with a limit below the count return that many of the newest`() {
        listOf("old" to 1_000L, "mid" to 2_000L, "new" to 3_000L).forEach { (id, at) ->
            store.applyRemoteDelete(id, deletedAt = at)
        }

        assertEquals(listOf("new", "mid"), store.pendingTombstones(2).map { it.id })
        assertEquals(listOf("new"), store.pendingTombstones(1).map { it.id })
        assertEquals(listOf("new", "mid", "old"), store.pendingTombstones(3).map { it.id })
    }

    @Test
    fun `on real SQL pending tombstones with a limit larger than the count return every one, past a thousand`() {
        repeat(1_001) { store.applyRemoteDelete("d-" + it.toString().padStart(4, '0'), deletedAt = base + it) }

        val pending = store.pendingTombstones(Int.MAX_VALUE).map { it.id }

        assertEquals(1_001, pending.size)
        assertEquals("d-1000", pending.first())
        assertEquals("d-0000", pending.last())
    }

    // ── a row the store cannot map ───────────────────────────────────────

    @Test
    fun `on real SQL listing a history that holds a row with an unknown source fails, not a shorter list`() {
        database.save(aRow("good-1", audioPath = null, createdAt = base + 2))
        database.save(aRow("bogus-1", audioPath = null, createdAt = base + 1).copy(source = "bogus"))

        val failure = runCatching { store.list(10) }

        assertTrue("list must throw, it returned ${failure.getOrNull()?.map { it.id }}", failure.isFailure)
        val thrown = failure.exceptionOrNull()
        assertTrue("expected a MappingFailure, got $thrown", thrown is MappingFailure)
    }

    // ── delete of an id that is not there ────────────────────────────────

    @Test
    fun `on real SQL deleting an unknown id removes nothing and leaves no tombstone`() {
        store.save(aTranscription("t-1", base))

        assertFalse(store.delete("unknown"))

        assertEquals(1, store.count())
        assertEquals(emptyList<Tombstone>(), store.pendingTombstones(10))
    }

    @Test
    fun `on real SQL deleting a known id removes that row and leaves one user tombstone`() {
        store.save(aTranscription("t-1", base))
        store.save(aTranscription("t-2", base + 1))

        assertTrue(store.delete("t-1"))

        assertEquals(listOf("t-2"), store.list(10).map { it.id })
        assertEquals(listOf("t-1" to Tombstone.Reason.USER), store.pendingTombstones(10).map { it.id to it.reason })
    }

    // ── the audio column ─────────────────────────────────────────────────

    @Test
    fun `the twin writes a row's audio path to the audio_path column and a missing one as NULL`() {
        database.save(aRow("with-audio", audioPath = "a/b.wav"))
        database.save(aRow("no-audio", audioPath = null))

        val stored = sqlite.query("SELECT id, audio_path FROM transcriptions ORDER BY id")

        assertEquals(listOf(listOf<Any?>("no-audio", null), listOf<Any?>("with-audio", "a/b.wav")), stored)
    }

    @Test
    fun `the twin reads the audio path back for a row that has one and null for a row that has none`() {
        database.save(aRow("with-audio", audioPath = "a/b.wav", createdAt = base + 1))
        database.save(aRow("no-audio", audioPath = null, createdAt = base))

        val read = database.newest(10).associate { it.id to it.audioPath }

        assertEquals(mapOf("with-audio" to "a/b.wav", "no-audio" to null), read)
    }

    // ── a clock that is not on a whole second ────────────────────────────

    @Test
    fun `on real SQL the purge at half past a second keeps the cutoff and removes 1 ms and 100 ms before it`() {
        nowMillis = Instant.parse("2026-06-30T12:00:00.500Z").toEpochMilli()
        val cutoff = Instant.parse("2026-04-01T12:00:00.500Z").toEpochMilli()
        store.save(aTranscription("at", cutoff))
        store.save(aTranscription("before-1", cutoff - 1))
        store.save(aTranscription("before-100", cutoff - 100))
        store.save(aTranscription("after-1", cutoff + 1))

        val report = store.purgeExpired()

        assertEquals(cutoff, report.cutoff)
        assertEquals(2, report.purged)
        assertEquals(setOf("at", "after-1"), store.list(10).map { it.id }.toSet())
        assertEquals(setOf("before-1", "before-100"), store.pendingTombstones(10).map { it.id }.toSet())
    }

    @Test
    fun `on real SQL the sweep at half past a second keeps the cutoff and drops 1 ms and 100 ms before it`() {
        nowMillis = Instant.parse("2026-06-30T12:00:00.500Z").toEpochMilli()
        val cutoff = Instant.parse("2026-04-01T12:00:00.500Z").toEpochMilli()
        store.applyRemoteDelete("at", deletedAt = cutoff)
        store.applyRemoteDelete("before-1", deletedAt = cutoff - 1)
        store.applyRemoteDelete("before-100", deletedAt = cutoff - 100)
        store.applyRemoteDelete("after-1", deletedAt = cutoff + 1)

        val report = store.purgeExpiredTombstones()

        assertEquals(cutoff, report.cutoff)
        assertEquals(2, report.dropped)
        assertEquals(setOf("at", "after-1"), store.pendingTombstones(10).map { it.id }.toSet())
    }
}
