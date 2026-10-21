package dev.breaker.dictation.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's SQL, run.
 *
 * The service tests use an in-memory double, so neither the `UNIQUE` nor the `AUTOINCREMENT` nor the
 * `ORDER BY seq` is ever executed there. These tests run every statement in [SyncQueueSql] on a real SQLite
 * engine ([DesktopSqlite]) through [JdbcSyncQueueDatabase]. It is the desktop SQLite in the test-only
 * `sqlite-jdbc` jar, not the phone's, and the Android adapter runs in no JVM test.
 */
class SyncQueueSqlTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    @After
    fun closeDatabase() = sqlite.close()

    /** Queues one row under [id] with the default source, model, duration and text for that id. */
    private fun addRow(id: String, createdAt: Long = 1_700_000_000_000L): Boolean =
        db.insertIfAbsent(id, "quartz marmalade for $id", "local", "small.en", 1_200L, createdAt)

    private fun headOrFail(what: String): QueueEntry =
        db.head() ?: throw AssertionError("android_sync: expected an entry at the head: $what")

    private fun headId(): String? = db.head()?.clientId

    /** A failure here means a second copy of an id replaces the first, sits beside it, or takes a new place in the queue. */
    @Test
    fun `the same client id inserted twice leaves one row and keeps the first copy`() {
        assertTrue(
            "android_sync: the first insert of an id must add a row",
            db.insertIfAbsent("dup-1", "first copy text", "local", "small.en", 1_000L, 111L),
        )
        val seqBefore = headOrFail("after the first insert").seq
        assertFalse(
            "android_sync: a second insert of a queued id must report that nothing was added",
            db.insertIfAbsent("dup-1", "second copy text", "server", "large-v3", 2_000L, 222L),
        )
        assertFalse(
            "android_sync: a third insert of a queued id must also add nothing",
            db.insertIfAbsent("dup-1", "second copy text", "server", "large-v3", 2_000L, 222L),
        )
        assertEquals("android_sync: the same id twice must leave one row", 1, db.count())
        val kept = headOrFail("after the duplicate inserts")
        assertEquals("android_sync: the seq of the first copy must not change", seqBefore, kept.seq)
        assertEquals("android_sync: id of the kept row", "dup-1", kept.clientId)
        assertEquals("android_sync: text of the first copy must stay", "first copy text", kept.text)
        assertEquals("android_sync: source of the first copy must stay", "local", kept.source)
        assertEquals("android_sync: model of the first copy must stay", "small.en", kept.model)
        assertEquals("android_sync: duration of the first copy must stay", 1_000L, kept.durationMs)
        assertEquals("android_sync: creation time of the first copy must stay", 111L, kept.createdAtMs)
    }

    /** A failure here means the queue drains by creation time, so a clock that went back sends entries out of order. */
    @Test
    fun `head is the row with the lowest seq and not the oldest createdAt`() {
        addRow("first-in", 300L)
        addRow("second-in", 100L)
        addRow("third-in", 200L)
        assertEquals("android_sync: head must be the first entry inserted", "first-in", headId())
        val seqs = ArrayList<Long>()
        for (step in 0 until 3) {
            seqs.add(headOrFail("step $step").seq)
            assertTrue(db.remove(headId() ?: ""))
        }
        assertTrue("android_sync: seq must rise in insert order, got $seqs", seqs[0] < seqs[1] && seqs[1] < seqs[2])
        addRow("first-in", 300L)
        addRow("second-in", 100L)
        addRow("third-in", 200L)
        assertEquals(
            "android_sync: drain order must follow insert order, not createdAt",
            listOf("first-in", "second-in", "third-in"),
            db.drainQueuedIds(),
        )
    }

    /** A failure here means a new entry can take the seq of a removed one, so it could jump the queue or be mistaken for it. */
    @Test
    fun `a seq is never reused after its row is removed`() {
        addRow("a")
        addRow("b")
        val seqA = headOrFail("a is first").seq
        assertTrue(db.remove("a"))
        val entryB = headOrFail("b is first once a is gone")
        assertEquals("android_sync: b must be the head once a is removed", "b", entryB.clientId)
        assertTrue(db.remove("b"))
        assertNull("android_sync: the queue must be empty once a and b are removed", db.head())
        addRow("c")
        val entryC = headOrFail("c is the only entry")
        assertEquals("android_sync: c must be the head", "c", entryC.clientId)
        assertTrue("android_sync: seq of b (${entryB.seq}) must be above seq of a ($seqA)", entryB.seq > seqA)
        assertTrue("android_sync: seq of c (${entryC.seq}) must be above seq of the removed b (${entryB.seq})", entryC.seq > entryB.seq)
    }

    /** A failure here means remove takes out more than the named id, for example every row or every row whose id starts with it. */
    @Test
    fun `remove deletes only the named client id`() {
        addRow("client-1")
        addRow("client-10")
        addRow("client-2")
        assertTrue("android_sync: remove of a queued id must say a row was removed", db.remove("client-1"))
        assertEquals("android_sync: only the named row may go, not the rows whose id starts with it", 2, db.count())
        assertEquals("android_sync: the other two rows must stay, in order", listOf("client-10", "client-2"), db.drainQueuedIds())
    }

    /** A failure here means remove reports a removal that did not happen, or removes something else. */
    @Test
    fun `remove of an unknown id removes nothing and says so`() {
        addRow("kept-1")
        addRow("kept-2")
        assertFalse("android_sync: remove of an id that is not queued must return false", db.remove("never-queued"))
        assertFalse("android_sync: remove of an empty id must return false", db.remove(""))
        assertEquals("android_sync: nothing may be removed", 2, db.count())
        assertTrue(db.remove("kept-1"))
        assertFalse("android_sync: a second remove of the same id must return false", db.remove("kept-1"))
        assertEquals("android_sync: the other row must stay", listOf("kept-2"), db.drainQueuedIds())
    }

    /** A failure here means count does not follow the rows: it ignores removals, counts duplicates or is off by one. */
    @Test
    fun `count counts the rows`() {
        assertEquals("android_sync: an empty queue counts 0", 0, db.count())
        addRow("n-1")
        assertEquals("android_sync: one row counts 1", 1, db.count())
        addRow("n-2")
        addRow("n-3")
        assertEquals("android_sync: three rows count 3", 3, db.count())
        addRow("n-2")
        assertEquals("android_sync: a duplicate id adds no row", 3, db.count())
        db.remove("n-1")
        assertEquals("android_sync: a removal lowers the count", 2, db.count())
        db.remove("n-1")
        assertEquals("android_sync: removing an absent id leaves the count", 2, db.count())
    }

    /** A failure here means a block that threw leaves its writes behind, or the connection stays in a transaction. */
    @Test
    fun `a transaction that throws rolls the insert back`() {
        val planned = IllegalStateException("android_sync: planned failure inside the block")
        val thrown = thrownBy {
            db.transaction<Unit> {
                addRow("rolled-back")
                throw planned
            }
        }
        assertSame("android_sync: the transaction must rethrow the very exception the block threw", planned, thrown)
        assertEquals("android_sync: the insert inside the failed block must be gone", 0, db.count())
        assertNull(db.head())
        assertTrue("android_sync: autocommit must be on again after a failed transaction", sqlite.connection.autoCommit)
        addRow("present-before")
        val failure = thrownBy {
            db.transaction<Unit> {
                addRow("added-inside")
                db.remove("present-before")
                throw IllegalStateException("android_sync: second planned failure")
            }
        }
        assertTrue("android_sync: the second failure must reach the caller, got $failure", failure is IllegalStateException)
        assertEquals("android_sync: the removal inside the failed block must be undone", listOf("present-before"), db.drainQueuedIds())
        val result = db.transaction {
            addRow("committed")
            "block result"
        }
        assertEquals("android_sync: the transaction must return the value of the block", "block result", result)
        assertEquals("android_sync: a block that returned must keep its insert", listOf("committed"), db.drainQueuedIds())
    }

    /** A failure here means a character, a long text or a large number is changed on the way into SQLite or out of it. */
    @Test
    fun `every field comes back exactly as stored`() {
        val texts = listOf(
            "quotes" to "He said \"hello\" and 'goodbye' and \"\"\" and ''",
            "newline" to "line one\nline two\r\nline three",
            "tab" to "left\tright",
            "backslash" to "C:\\temp\\new and a trailing backslash \\",
            "non-BMP" to "face \uD83D\uDE00 and \uD83C\uDF3E end",
            "empty" to "",
            "long" to buildString { for (index in 0 until 20_000) append("abcdefghij"[index % 10]) },
            "sql-looking" to "x'); DROP TABLE sync_queue; --",
        )
        for ((index, case) in texts.withIndex()) {
            val (label, text) = case
            val id = "id-$index-\"quoted\"-'single'"
            assertTrue(
                "android_sync: insert must add the $label row",
                db.insertIfAbsent(id, text, "server", "large \"v3\"", 4_294_967_297L, Long.MAX_VALUE),
            )
            val entry = headOrFail(label)
            assertTrue("android_sync: seq of the $label row must be positive", entry.seq > 0L)
            assertEquals("android_sync: id of the $label row", id, entry.clientId)
            assertTrue(
                "android_sync: text of the $label row changed: wrote ${text.length} chars, read ${entry.text.length} chars",
                text == entry.text,
            )
            assertEquals("android_sync: source of the $label row", "server", entry.source)
            assertEquals("android_sync: model of the $label row", "large \"v3\"", entry.model)
            assertEquals("android_sync: duration of the $label row", 4_294_967_297L, entry.durationMs)
            assertEquals("android_sync: creation time of the $label row", Long.MAX_VALUE, entry.createdAtMs)
            assertTrue(db.remove(id))
        }
        assertEquals("android_sync: the long text must have kept its length", 20_000, texts[6].second.length)
        assertEquals("android_sync: the queue must be empty at the end", 0, db.count())
    }

    /** A failure here means opening a database that already has the table fails, or wipes what was queued. */
    @Test
    fun `creating the schema twice is harmless`() {
        addRow("before-schema")
        sqlite.createSchema()
        sqlite.createSchema()
        assertEquals("android_sync: the row must survive a second schema creation", 1, db.count())
        assertEquals("android_sync: the row must be unchanged", "before-schema", headId())
        assertTrue("android_sync: the table must still accept rows", addRow("after-schema"))
        assertEquals("android_sync: both rows must be in order", listOf("before-schema", "after-schema"), db.drainQueuedIds())
    }

    /** A failure here means the table lost its unique client id or its autoincrement seq, or the text and the real table disagree. */
    @Test
    fun `the table has a unique client id and an autoincrement seq`() {
        val create = SyncQueueSql.CREATE_QUEUE
        assertTrue("android_sync: CREATE_QUEUE must declare client_id TEXT NOT NULL UNIQUE", create.contains("client_id TEXT NOT NULL UNIQUE"))
        assertTrue("android_sync: CREATE_QUEUE must declare seq INTEGER PRIMARY KEY AUTOINCREMENT", create.contains("seq INTEGER PRIMARY KEY AUTOINCREMENT"))
        val master = sqlite.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", SyncQueueSql.TABLE_QUEUE)
        assertEquals("android_sync: sqlite_master must hold exactly one sync_queue table", 1, master.size)
        val stored = master[0][0] as String
        assertTrue("android_sync: the stored table must be UNIQUE on client_id: $stored", stored.contains("client_id TEXT NOT NULL UNIQUE"))
        assertTrue("android_sync: the stored table must be AUTOINCREMENT on seq: $stored", stored.contains("seq INTEGER PRIMARY KEY AUTOINCREMENT"))
        val columns = sqlite.query("PRAGMA table_info(${SyncQueueSql.TABLE_QUEUE})")
        assertEquals(
            "android_sync: column names in order",
            listOf("seq", "client_id", "text", "source", "model", "duration_ms", "created_at"),
            columns.map { it[1] },
        )
        assertEquals(
            "android_sync: column types in order",
            listOf("INTEGER", "TEXT", "TEXT", "TEXT", "TEXT", "INTEGER", "INTEGER"),
            columns.map { it[2] },
        )
        assertEquals("android_sync: only seq is the primary key", listOf(1L, 0L, 0L, 0L, 0L, 0L, 0L), columns.map { it[5] })
        assertEquals("android_sync: every column after seq is NOT NULL", List(6) { 1L }, columns.drop(1).map { it[3] })
        val indexes = sqlite.query("PRAGMA index_list(${SyncQueueSql.TABLE_QUEUE})")
        assertEquals("android_sync: the table must have exactly one index, the unique one", 1, indexes.size)
        assertEquals("android_sync: that index must be unique", 1L, indexes[0][2])
        assertEquals("android_sync: that index must come from a UNIQUE constraint", "u", indexes[0][3])
        val indexColumns = sqlite.query("PRAGMA index_info(\"${indexes[0][1]}\")")
        assertEquals("android_sync: the unique index must cover client_id only", listOf<Any?>("client_id"), indexColumns.map { it[2] })
        addRow("sequence-row")
        val sequenceTable = sqlite.query("SELECT name FROM sqlite_master WHERE name = 'sqlite_sequence'")
        assertEquals("android_sync: an AUTOINCREMENT table needs the sqlite_sequence table", 1, sequenceTable.size)
        val counters = sqlite.query("SELECT name, seq FROM sqlite_sequence")
        assertEquals(
            "android_sync: sqlite_sequence must hold the counter of sync_queue after an insert",
            listOf(listOf<Any?>("sync_queue", 1L)),
            counters,
        )
    }
}
