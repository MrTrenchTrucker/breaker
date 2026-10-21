package dev.breaker.dictation.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statements that walk the queue, run on real SQLite: the next entry after a seq, and the highest seq.
 *
 * The drain reads the queue one entry at a time with `nextAfter` and reads its bound once with `tailSeq`.
 * Both are compared numerically: the seq is an integer, so 9 comes before 10 and 99 before 100. The
 * digit-boundary tests cross those lengths on the SQLite twin, where a text comparison would give the wrong
 * order.
 */
class SyncQueueNextAfterTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    @After
    fun closeDatabase() = sqlite.close()

    /** Queues one row under [id] with the default source, model, duration and text for that id. */
    private fun addRow(id: String, createdAt: Long = 1_700_000_000_000L): Boolean =
        db.insertIfAbsent(id, "quartz marmalade for $id", "local", "small.en", 1_200L, createdAt)

    private fun headOrFail(): QueueEntry = db.head() ?: throw AssertionError("android_sync: expected an entry at the head")

    /** A failure here means the walk does not reach the entry queued after the head, so a drain would stop early. */
    @Test
    fun `nextAfter returns the entry with the lowest seq above the given seq`() {
        addRow("a", 300L)
        addRow("b", 100L)
        addRow("c", 200L)
        val a = headOrFail()
        assertEquals("android_sync: the head must be a", "a", a.clientId)
        val b = requireNotNull(db.nextAfter(a.seq)) { "android_sync: nothing after a" }
        assertEquals("android_sync: the entry after a must be b", "b", b.clientId)
        val c = requireNotNull(db.nextAfter(b.seq)) { "android_sync: nothing after b" }
        assertEquals("android_sync: the entry after b must be c", "c", c.clientId)
        assertNull("android_sync: nothing may come after c", db.nextAfter(c.seq))
    }

    /** A failure here means the walk returns the row it was given, so a drain would take the same row again and again. */
    @Test
    fun `nextAfter never returns the row whose seq it was given`() {
        addRow("a")
        addRow("b")
        addRow("c")
        val a = headOrFail()
        val b = requireNotNull(db.nextAfter(a.seq)) { "android_sync: nothing after a" }
        val c = requireNotNull(db.nextAfter(b.seq)) { "android_sync: nothing after b" }
        for (given in listOf(a, b, c)) {
            val next = db.nextAfter(given.seq)
            assertTrue(
                "android_sync: nextAfter of ${given.clientId} must be above its seq, got ${next?.clientId}",
                next == null || (next.seq > given.seq && next.clientId != given.clientId),
            )
        }
    }

    /** A failure here means nextAfter invents an entry on an empty queue, or after the last one. */
    @Test
    fun `nextAfter returns null when no entry is above the given seq`() {
        assertNull("android_sync: nextAfter(0) on an empty queue must be null", db.nextAfter(0L))
        addRow("a")
        val only = headOrFail()
        assertNull("android_sync: nothing may come after the only entry", db.nextAfter(only.seq))
    }

    /** A failure here means the walk follows created_at, which can go backwards, instead of the insert order. */
    @Test
    fun `nextAfter keeps the order by seq when created_at goes backwards`() {
        addRow("a", 900L)
        addRow("b", 800L)
        addRow("c", 700L)
        assertEquals(
            "android_sync: the entry after a must be b, the next one inserted",
            "b",
            db.nextAfter(headOrFail().seq)?.clientId,
        )
    }

    /** A failure here means a removed row is still seen by the walk, or the walk skips the entry after it. */
    @Test
    fun `nextAfter after a removed middle entry skips to the entry after it`() {
        addRow("a")
        addRow("b")
        addRow("c")
        assertTrue(db.remove("b"))
        assertEquals("android_sync: the entry after a must be c once b is removed", "c", db.nextAfter(headOrFail().seq)?.clientId)
        assertEquals("android_sync: the head must still be a", "a", headOrFail().clientId)
    }

    /** A failure here means the walk started at a row other than the head when asked from zero. */
    @Test
    fun `nextAfter of zero is the head of the queue`() {
        addRow("a")
        addRow("b")
        assertEquals("android_sync: nextAfter(0) must be the first row", "a", db.nextAfter(0L)?.clientId)
    }

    /** A failure here means the next-entry statement text changed, so the walk no longer compares numerically. */
    @Test
    fun `the next entry statement text is pinned`() {
        assertEquals(
            "android_sync: the next entry statement must compare the bound as an integer through CAST",
            "SELECT seq, client_id, text, source, model, duration_ms, created_at FROM sync_queue WHERE seq > CAST(? AS INTEGER) ORDER BY seq ASC LIMIT 1",
            SyncQueueSql.SELECT_NEXT_AFTER,
        )
    }

    /** A failure here means the walk compares seq as text, so after nine it would go back to one. */
    @Test
    fun `nextAfter crosses the digit boundaries from nine to ten and from ninety nine to one hundred`() {
        for (n in 1..100) {
            addRow("r$n", 1_700_000_000_000L + n)
        }
        assertEquals("android_sync: nextAfter(9) must be the row with seq 10", 10L, db.nextAfter(9L)?.seq)
        assertEquals("android_sync: nextAfter(99) must be the row with seq 100", 100L, db.nextAfter(99L)?.seq)
        assertNull("android_sync: nextAfter(100) must be null", db.nextAfter(100L))
    }

    /** A failure here means a walk from the head orders the rows as text, so r10 would come before r2 instead of after r9. */
    @Test
    fun `a walk with nextAfter from the head visits twelve rows in numeric order`() {
        for (n in 1..12) {
            addRow("r$n")
        }
        val visited = ArrayList<String>()
        var entry: QueueEntry? = db.head()
        while (entry != null && visited.size <= 12) {
            visited.add(entry.clientId)
            entry = db.nextAfter(entry.seq)
        }
        assertEquals("android_sync: the walk from the head must visit exactly 12 rows, got $visited", 12, visited.size)
        assertEquals("android_sync: the walk must visit r1 to r12 in numeric order, got $visited", (1..12).map { "r$it" }, visited)
    }

    /** A failure here means tailSeq is not 0 on an empty queue, so a drain would take a bound that admits nothing. */
    @Test
    fun `tailSeq is zero when the queue is empty`() {
        assertEquals("android_sync: tailSeq of an empty SQLite twin must be 0", 0L, db.tailSeq())
        assertEquals("android_sync: tailSeq of an empty in-memory double must be 0", 0L, InMemorySyncQueueDatabase().tailSeq())
    }

    /** A failure here means tailSeq is not the highest seq queued, so a drain would stop at the wrong row. */
    @Test
    fun `tailSeq is the highest seq queued on both doubles`() {
        assertEquals("android_sync: tailSeq after three inserts on the in-memory double", listOf(3L, 2L), tailAfterInserts(InMemorySyncQueueDatabase()))
        assertEquals("android_sync: tailSeq after three inserts on the SQLite twin", listOf(3L, 2L), tailAfterInserts(db))
    }

    /** A failure here means tailSeq compares as text, so after nine rows it would report a lower highest seq. */
    @Test
    fun `tailSeq crosses the digit boundary from nine to ten`() {
        for (n in 1..9) {
            addRow("r$n")
        }
        assertEquals("android_sync: tailSeq after nine inserts must be 9", 9L, db.tailSeq())
        addRow("r10")
        assertEquals("android_sync: tailSeq after the tenth insert must be 10", 10L, db.tailSeq())
    }

    /** Inserts three rows, reads the tail, removes the highest and reads it again. */
    private fun tailAfterInserts(queue: SyncQueueDatabase): List<Long> {
        queue.insertIfAbsent("a", "quartz marmalade for a", "local", "small.en", 1_200L, 1_700_000_000_000L)
        queue.insertIfAbsent("b", "quartz marmalade for b", "local", "small.en", 1_200L, 1_700_000_000_000L)
        queue.insertIfAbsent("c", "quartz marmalade for c", "local", "small.en", 1_200L, 1_700_000_000_000L)
        val afterThree = queue.tailSeq()
        queue.remove("c")
        return listOf(afterThree, queue.tailSeq())
    }
}
