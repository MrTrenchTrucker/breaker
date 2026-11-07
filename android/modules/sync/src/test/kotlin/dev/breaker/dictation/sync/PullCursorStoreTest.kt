package dev.breaker.dictation.sync

import java.io.File
import java.util.ArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pull cursor store, run on both doubles with one fixed script.
 *
 * The cursor is opaque: the store keeps one text, replaces it on a second save, and clears it.
 * The queue and the cursor are independent: queue calls never change the cursor and cursor calls
 * never change the queue. The Android adapter cannot run here, so the statements are run on the
 * desktop SQLite twin and on the list-backed double, the way `DatabaseAgreementTest` runs the two
 * queue stores. A hand trace against [SyncQueueSql] is in AUDIT.md; a hand trace is not a build.
 */
class PullCursorStoreTest {

    private var file: File? = null
    private val opened = ArrayList<AutoCloseable>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-cursor", ".db")
    }

    @After
    fun closeAndDeleteFile() {
        try {
            opened.forEach { it.close() }
        } finally {
            val path = file?.path
            if (path != null) {
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
        }
    }

    /** The cursor as the script reports it: none when there is none, else the text after a marker. */
    private fun describe(cursor: String?): String =
        if (cursor == null) "none" else "cursor:$cursor"

    /**
     * Runs one fixed script against [db] and returns what it answered at each step, as text. The
     * steps cover the fresh store, a save, a replacing save, a clear, a clear on an empty store,
     * odd texts, the queue and the cursor beside each other, and a save inside a transaction.
     */
    private fun runScript(db: SyncQueueDatabase): List<String> {
        val answers = ArrayList<String>()

        fun cursor() {
            answers.add("cursor=" + describe(db.pullCursor()))
        }

        fun addRow(id: String, createdAt: Long) {
            answers.add("insert $id=" + db.insertIfAbsent(id, "text for $id", "local", "small.en", 1_200L, createdAt))
        }

        fun removeRow(id: String) {
            answers.add("remove $id=" + db.remove(id))
        }

        fun count() {
            answers.add("count=" + db.count())
        }

        fun head() {
            answers.add("head=" + (db.head()?.clientId ?: "none"))
        }

        fun drain() {
            answers.add("drain=" + db.drainQueuedIds().joinToString(","))
        }

        cursor()
        db.savePullCursor("alpha")
        cursor()
        db.savePullCursor("beta")
        cursor()
        db.clearPullCursor()
        cursor()
        db.clearPullCursor()
        cursor()
        db.savePullCursor("")
        cursor()
        db.savePullCursor("  ")
        cursor()
        db.savePullCursor("cafe\u00e9 \uD83D\uDE00")
        cursor()
        db.savePullCursor("x".repeat(4000))
        cursor()
        db.savePullCursor("gamma")
        cursor()
        count()
        addRow("q-1", 100L)
        addRow("q-2", 200L)
        answers.add("park q-2=" + db.parkRow("q-2", "refused"))
        count()
        head()
        answers.add("resave q-2=" + db.resaveRow("q-2", "text for q-2", "local", "small.en", 1_200L, 200L))
        count()
        drain()
        count()
        addRow("q-3", 300L)
        removeRow("q-3")
        count()
        cursor()
        addRow("q-4", 400L)
        val thrown = thrownBy {
            db.transaction<Unit> {
                db.savePullCursor("t2")
                throw IllegalStateException("android_sync: planned failure after the cursor save")
            }
        }
        assertTrue("android_sync: the planned failure must reach the caller", thrown is IllegalStateException)
        cursor()
        db.transaction {
            db.savePullCursor("t3")
        }
        cursor()
        db.clearPullCursor()
        cursor()
        count()
        return answers
    }

    private val expected = listOf(
        "cursor=none",
        "cursor=cursor:alpha",
        "cursor=cursor:beta",
        "cursor=none",
        "cursor=none",
        "cursor=cursor:",
        "cursor=cursor:  ",
        "cursor=cursor:cafe\u00e9 \uD83D\uDE00",
        "cursor=cursor:" + "x".repeat(4000),
        "cursor=cursor:gamma",
        "count=0",
        "insert q-1=true",
        "insert q-2=true",
        "park q-2=true",
        "count=1",
        "head=q-1",
        "resave q-2=true",
        "count=2",
        "drain=q-1,q-2",
        "count=0",
        "insert q-3=true",
        "remove q-3=true",
        "count=0",
        "cursor=cursor:gamma",
        "insert q-4=true",
        "cursor=cursor:gamma",
        "cursor=cursor:t3",
        "cursor=none",
        "count=1",
    )

    /** A failure here means the two stores answer the same cursor script differently. */
    @Test
    fun `the in-memory double and the SQL twin agree on the cursor script`() {
        val memoryAnswers = runScript(InMemorySyncQueueDatabase())
        val sqlite = DesktopSqlite()
        val sqliteAnswers = try {
            runScript(JdbcSyncQueueDatabase(sqlite))
        } finally {
            sqlite.close()
        }

        assertEquals("android_sync: the in-memory double must answer 29 steps", 29, memoryAnswers.size)
        assertEquals("android_sync: the SQL twin must answer 29 steps", 29, sqliteAnswers.size)
        for (step in expected.indices) {
            assertEquals(
                "android_sync: the in-memory double answered wrongly at step ${step + 1}",
                expected[step],
                memoryAnswers.getOrNull(step),
            )
            assertEquals(
                "android_sync: the SQL twin answered wrongly at step ${step + 1}",
                expected[step],
                sqliteAnswers.getOrNull(step),
            )
        }
        assertEquals(
            "android_sync: the in-memory double must give exactly the expected answers",
            expected,
            memoryAnswers,
        )
        assertEquals(
            "android_sync: the SQL twin must give exactly the expected answers",
            expected,
            sqliteAnswers,
        )
    }

    /** A failure here means a second save adds a second row, or the read keeps the first copy. */
    @Test
    fun `a second save replaces the row and the table holds exactly one row`() {
        val sqlite = DesktopSqlite()
        opened.add(sqlite)
        val db = JdbcSyncQueueDatabase(sqlite)
        db.savePullCursor("first")
        db.savePullCursor("second")
        assertEquals("android_sync: the read must return the second text", "second", db.pullCursor())
        val rows = sqlite.query("SELECT COUNT(*) FROM " + SyncQueueSql.TABLE_PULL_CURSOR)
        assertEquals("android_sync: the cursor table must hold exactly one row", 1L, rows.single()[0] as Long)
    }

    /** A failure here means the cursor is lost, changed or moved by a close and a reopen. */
    @Test
    fun `the cursor survives closing and reopening the database`() {
        val first = openJdbc()
        first.insertIfAbsent("kept", "text for kept", "local", "small.en", 1_200L, 100L)
        first.savePullCursor("the-cursor")
        first.close()

        val second = openJdbc()
        assertEquals("android_sync: the cursor must come back after the reopen", "the-cursor", second.pullCursor())
        assertEquals("android_sync: the queue row must come back after the reopen", 1, second.count())
        assertEquals("android_sync: the head must be the queued entry", "kept", second.head()?.clientId)
    }

    private fun openJdbc(): JdbcSyncQueueDatabase {
        val sqlite = DesktopSqlite(file)
        opened.add(sqlite)
        return JdbcSyncQueueDatabase(sqlite)
    }
}
