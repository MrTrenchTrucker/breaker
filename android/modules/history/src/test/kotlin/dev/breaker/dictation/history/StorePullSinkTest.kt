package dev.breaker.dictation.history

import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.PullSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class StorePullSinkTest : SqliteHistoryStoreTestBase() {

    private val sink: PullSink = typed()

    @Test
    fun `a held row is removed and a REMOTE tombstone keeps the delete`() {
        store.save(aTranscription(id = "t-1"))
        assertTrue(sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis))
        assertEquals(0, typed().count())
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals("t-1", tombstones[0].id)
        assertEquals(nowMillis, tombstones[0].deletedAt)
        assertEquals(Tombstone.Reason.REMOTE, tombstones[0].reason)
    }

    @Test
    fun `an id this device never had still answers false and is tombstoned`() {
        store.save(aTranscription(id = "t-1"))
        assertFalse(sink.applyRemoteDelete(id = "never-here", deletedAt = nowMillis))
        assertEquals(1, typed().count())
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals("never-here", tombstones[0].id)
        assertEquals(nowMillis, tombstones[0].deletedAt)
        assertEquals(Tombstone.Reason.REMOTE, tombstones[0].reason)
    }

    @Test
    fun `the time stored is the time passed`() {
        store.save(aTranscription(id = "t-1"))
        sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis - 5)
        assertEquals(nowMillis - 5, typed().pendingTombstones(10)[0].deletedAt)

        store.save(aTranscription(id = "t-2"))
        sink.applyRemoteDelete(id = "t-2", deletedAt = nowMillis + 123)
        assertEquals(nowMillis + 123, typed().pendingTombstones(10).single { it.id == "t-2" }.deletedAt)
    }

    @Test
    fun `a second row with another id is untouched by the delete of the first`() {
        store.save(aTranscription(id = "keep-1"))
        store.save(aTranscription(id = "drop-1"))
        assertTrue(sink.applyRemoteDelete(id = "drop-1", deletedAt = nowMillis))
        assertEquals(listOf("keep-1"), store.list(10).map { it.id })
        assertEquals(1, typed().count())
    }

    @Test
    fun `the store is a PullSink as well as a HistoryStore`() {
        val port: HistoryStore = store
        assertTrue(port is PullSink)
    }

    @Test
    fun `a second delete of the same id replaces the tombstone instead of adding one`() {
        store.save(aTranscription(id = "t-1"))
        assertTrue(sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis))
        assertFalse(sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis + 999))
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals(nowMillis + 999, tombstones[0].deletedAt)
        assertEquals(Tombstone.Reason.REMOTE, tombstones[0].reason)
    }
}
