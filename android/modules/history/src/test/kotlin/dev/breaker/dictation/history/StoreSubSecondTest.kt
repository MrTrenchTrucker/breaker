package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The purge and the tombstone sweep at a clock that is not on a whole second.
 *
 * Every other purge test runs at a whole-second now, so a store that truncated now to seconds
 * before taking the cutoff would pass them all. Here the clock reads 12:00:00.500, the cutoff is
 * 2026-04-01T12:00:00.500, and a row 1 ms or 100 ms before it is on the purged side of the
 * boundary only if the half second is kept.
 */
class StoreSubSecondTest {

    private val database = InMemoryHistoryDatabase()
    private val now = Instant.parse("2026-06-30T12:00:00.500Z").toEpochMilli()

    /** Written out, not taken from the policy under test. */
    private val cutoff = Instant.parse("2026-04-01T12:00:00.500Z").toEpochMilli()
    private val store = SqliteHistoryStore(database, Clock { now })

    private fun aTranscription(id: String, createdAt: Long) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    @Test
    fun `the purge at half past a second keeps the cutoff and removes the rows 1 ms and 100 ms before it`() {
        store.save(aTranscription("at", cutoff))
        store.save(aTranscription("before-1", cutoff - 1))
        store.save(aTranscription("before-100", cutoff - 100))
        store.save(aTranscription("after-1", cutoff + 1))

        val report = store.purgeExpired()

        assertEquals(cutoff, report.cutoff)
        assertEquals(2, report.purged)
        assertEquals(2, report.tombstoned)
        assertEquals(setOf("at", "after-1"), store.list(10).map { it.id }.toSet())
        assertEquals(setOf("before-1", "before-100"), store.pendingTombstones(10).map { it.id }.toSet())
    }

    @Test
    fun `the sweep at half past a second keeps the cutoff and drops the tombstones 1 ms and 100 ms before it`() {
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
