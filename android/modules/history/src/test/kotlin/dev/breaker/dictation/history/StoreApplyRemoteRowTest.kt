package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class StoreApplyRemoteRowTest : SqliteHistoryStoreTestBase() {

    private val sink: PullSink = typed()

    private fun pulled(id: String, text: String = "pulled text") = aTranscription(
        id = id,
        text = text,
        source = TranscriptionSource.SERVER,
        createdAt = nowMillis,
    )

    @Test
    fun `a pulled row with no tombstone is stored and the answer is true`() {
        val row = pulled("t-1")
        assertTrue(sink.applyRemoteRow(row))
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        val stored = store.list(10).single { it.id == "t-1" }
        assertEquals("pulled text", stored.text)
        assertEquals(TranscriptionSource.SERVER, stored.source)
        assertEquals("small.en", stored.model)
        assertEquals(1_200L, stored.durationMs)
        assertEquals(nowMillis, stored.createdAt)
    }

    @Test
    fun `a pulled row is refused and not stored when the device holds a USER tombstone`() {
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")
        assertFalse(sink.applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
    }

    @Test
    fun `a pulled row is refused and not stored when the device holds a REMOTE tombstone`() {
        store.save(aTranscription(id = "t-1"))
        sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis)
        assertFalse(sink.applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
    }

    @Test
    fun `a pulled row is refused and not stored when the device holds a RETENTION tombstone`() {
        store.save(aTranscription(id = "t-1", createdAt = nowMillis - 200 * 86_400_000L))
        typed().purgeExpired()
        assertFalse(sink.applyRemoteRow(pulled("t-1")))
        assertEquals(0, typed().count())
    }

    @Test
    fun `the tombstone stays after a refusal and holds its reason`() {
        store.save(aTranscription(id = "t-1"))
        sink.applyRemoteDelete(id = "t-1", deletedAt = nowMillis)
        assertFalse(sink.applyRemoteRow(pulled("t-1")))
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals("t-1", tombstones[0].id)
        assertEquals(Tombstone.Reason.REMOTE, tombstones[0].reason)
    }

    @Test
    fun `a held row of the same id is replaced by the pulled row`() {
        store.save(aTranscription(id = "t-1", text = "first"))
        assertTrue(sink.applyRemoteRow(pulled("t-1", text = "second")))
        assertEquals(listOf("t-1"), store.list(10).map { it.id })
        assertEquals("second", store.list(10).single { it.id == "t-1" }.text)
        assertEquals(1, typed().count())
    }

    @Test
    fun `a row of another id is untouched`() {
        store.save(aTranscription(id = "keep-1"))
        assertTrue(sink.applyRemoteRow(pulled("new-1")))
        assertEquals(setOf("keep-1", "new-1"), store.list(10).map { it.id }.toSet())
        assertEquals(2, typed().count())
    }

    @Test
    fun `a refusal for one id does not refuse another id`() {
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")
        assertFalse(sink.applyRemoteRow(pulled("t-1")))
        assertTrue(sink.applyRemoteRow(pulled("t-2")))
        assertEquals(listOf("t-2"), store.list(10).map { it.id })
    }

    @Test
    fun `a pulled row keeps its source as given`() {
        val local = aTranscription(id = "local-1", source = TranscriptionSource.LOCAL)
        val server = aTranscription(id = "server-1", source = TranscriptionSource.SERVER)
        assertTrue(sink.applyRemoteRow(local))
        assertTrue(sink.applyRemoteRow(server))
        val listed = store.list(10).associate { it.id to it.source }
        assertEquals(TranscriptionSource.LOCAL, listed["local-1"])
        assertEquals(TranscriptionSource.SERVER, listed["server-1"])
    }
}
