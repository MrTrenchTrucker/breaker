package dev.breaker.dictation.history

import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.PullSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The prune after a full list: keep exactly the given ids, remove the rest, no tombstone. */
internal class StoreRetainOnlyTest : SqliteHistoryStoreTestBase() {

    private val sink: PullSink = typed()

    @Test
    fun `retainOnly keeps exactly the ids in the set and removes the others`() {
        store.save(aTranscription(id = "keep-1"))
        store.save(aTranscription(id = "keep-2"))
        store.save(aTranscription(id = "drop-1"))
        store.save(aTranscription(id = "drop-2"))
        store.save(aTranscription(id = "drop-3"))

        val removed = sink.retainOnly(keep = setOf("keep-1", "keep-2"))

        assertEquals(3, removed)
        assertEquals(setOf("keep-1", "keep-2"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `retainOnly writes no tombstone for a removed id and leaves an existing tombstone`() {
        store.save(aTranscription(id = "drop-1"))
        store.save(aTranscription(id = "tomb-1"))
        sink.applyRemoteDelete(id = "tomb-1", deletedAt = nowMillis)

        val removed = sink.retainOnly(keep = emptySet())

        assertEquals(1, removed)
        assertTrue(store.list(10).isEmpty())
        val tombstones = typed().pendingTombstones(10)
        assertEquals(1, tombstones.size)
        assertEquals("tomb-1", tombstones[0].id)
    }

    @Test
    fun `retainOnly empty set removes every row`() {
        store.save(aTranscription(id = "t-1"))
        store.save(aTranscription(id = "t-2"))

        val removed = sink.retainOnly(keep = emptySet())

        assertEquals(2, removed)
        assertTrue(store.list(10).isEmpty())
    }

    @Test
    fun `retainOnly unknown ids remove nothing`() {
        store.save(aTranscription(id = "t-1"))

        val removed = sink.retainOnly(keep = setOf("t-1", "never-1", "never-2"))

        assertEquals(0, removed)
        assertEquals(setOf("t-1"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `retainOnly keep-all removes none`() {
        store.save(aTranscription(id = "t-1"))
        store.save(aTranscription(id = "t-2"))

        val removed = sink.retainOnly(keep = setOf("t-1", "t-2"))

        assertEquals(0, removed)
        assertEquals(setOf("t-1", "t-2"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `retainOnly keeping one id removes all others`() {
        store.save(aTranscription(id = "keep-1"))
        store.save(aTranscription(id = "drop-1"))
        store.save(aTranscription(id = "drop-2"))
        store.save(aTranscription(id = "drop-3"))

        val removed = sink.retainOnly(keep = setOf("keep-1"))

        assertEquals(3, removed)
        assertEquals(setOf("keep-1"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `a failing delete rolls the whole removal back`() {
        store.save(aTranscription(id = "keep-1"))
        store.save(aTranscription(id = "drop-1"))
        store.save(aTranscription(id = "drop-2"))

        val cause = IllegalStateException("disk full")
        val failing = object : HistoryDatabase by database {
            var calls = 0
            override fun deleteById(id: String): Boolean {
                calls++
                if (calls == 2) throw cause
                return database.deleteById(id)
            }
        }
        val fragile: HistoryStore = SqliteHistoryStore(failing, clock)

        try {
            (fragile as PullSink).retainOnly(keep = setOf("keep-1"))
            fail("the prune must fail when the delete fails")
        } catch (thrown: IllegalStateException) {
            assertSame(cause, thrown)
        }

        assertEquals(3, typed().count())
        assertEquals(setOf("keep-1", "drop-1", "drop-2"), store.list(10).map { it.id }.toSet())
    }

    @Test
    fun `a removed row is gone from list and a re-saved row is live`() {
        store.save(aTranscription(id = "keep-1"))
        store.save(aTranscription(id = "drop-1"))

        sink.retainOnly(keep = setOf("keep-1"))

        assertEquals(setOf("keep-1"), store.list(10).map { it.id }.toSet())

        store.save(aTranscription(id = "drop-1"))
        assertEquals(setOf("keep-1", "drop-1"), store.list(10).map { it.id }.toSet())
    }
}
