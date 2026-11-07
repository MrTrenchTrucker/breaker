package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayList

/** The two queue reads, run on both doubles with one fixed script.
 *
 * isQueued(clientId) answers whether a row with that client id is in the queue table; parked rows count.
 * queuedIds() lists the ids of every queue row, parked or not, oldest first (by seq);
 * no text read, nothing changed.
 * The Android adapter cannot run on a JVM, so the statements run on the desktop SQLite
 * twin and on the list-backed double, the way PullCursorStoreTest runs both. One fixed
 * script, expected answers listed as literals.
 */
class QueuedIdsTest {

    private fun describe(ids: List<String>): String = ids.joinToString(",")

    /** Runs one fixed script against [db] and returns what it answered at each step, as text. */
    private fun runScript(db: SyncQueueDatabase): List<String> {
        val answers = ArrayList<String>()

        fun isQueued(id: String) {
            answers.add("isQueued $id=${db.isQueued(id)}")
        }

        fun queuedIds() {
            answers.add("queuedIds=${describe(db.queuedIds())}")
        }

        fun insert(id: String, createdAt: Long) {
            answers.add("insert $id=" + db.insertIfAbsent(id, "text for $id", "local", "small.en", 1_200L, createdAt))
        }

        fun park(id: String, reason: String) {
            answers.add("park $id=${db.parkRow(id, reason)}")
        }

        fun resave(id: String, createdAt: Long) {
            answers.add("resave $id=" + db.resaveRow(id, "text for $id", "local", "small.en", 1_200L, createdAt))
        }

        fun remove(id: String) {
            answers.add("remove $id=${db.remove(id)}")
        }

        // Step 1: empty queue
        isQueued("never")
        queuedIds()
        // Step 2: one live row
        insert("a", 100L)
        isQueued("a")
        queuedIds()
        // Step 3: two rows, oldest first by seq
        insert("b", 200L)
        isQueued("b")
        queuedIds()
        // Step 4: a parked row still counts as queued
        park("b", "refused")
        isQueued("b")
        queuedIds()
        // Step 5: a third row after the parked one
        insert("c", 300L)
        queuedIds()
        // Step 6: remove the parked row
        remove("b")
        isQueued("b")
        queuedIds()
        // Step 7: park then resave - still queued
        park("c", "refused")
        isQueued("c")
        resave("c", 300L)
        isQueued("c")
        queuedIds()
        // Step 8: never queued beside queued ones
        isQueued("z")
        queuedIds()
        // Step 9: odd id text (long id with space and quote)
        val odd = "long-id-with-spaces and \"quote"
        insert(odd, 400L)
        isQueued(odd)
        queuedIds()
        // Step 10: drain all
        answers.add("drain=" + describe(db.drainQueuedIds()))
        isQueued("a")
        queuedIds()
        return answers
    }

    private val expected = listOf(
        "isQueued never=false",
        "queuedIds=",
        "insert a=true",
        "isQueued a=true",
        "queuedIds=a",
        "insert b=true",
        "isQueued b=true",
        "queuedIds=a,b",
        "park b=true",
        "isQueued b=true",
        "queuedIds=a,b",
        "insert c=true",
        "queuedIds=a,b,c",
        "remove b=true",
        "isQueued b=false",
        "queuedIds=a,c",
        "park c=true",
        "isQueued c=true",
        "resave c=true",
        "isQueued c=true",
        "queuedIds=a,c",
        "isQueued z=false",
        "queuedIds=a,c",
        "insert long-id-with-spaces and \"quote=true",
        "isQueued long-id-with-spaces and \"quote=true",
        "queuedIds=a,c,long-id-with-spaces and \"quote",
        "drain=a,c,long-id-with-spaces and \"quote",
        "isQueued a=false",
        "queuedIds=",
    )

    /** A failure here means the two doubles answer the queue-reads script differently. */
    @Test
    fun `the in-memory double and the SQL twin agree on the queue-reads script`() {
        val memoryAnswers = runScript(InMemorySyncQueueDatabase())
        val sqlite = DesktopSqlite()
        val sqliteAnswers = try {
            runScript(JdbcSyncQueueDatabase(sqlite))
        } finally {
            sqlite.close()
        }

        assertEquals("android_sync: the in-memory double must answer ${expected.size} steps", expected.size,
            memoryAnswers.size)
        assertEquals("android_sync: the SQL twin must answer ${expected.size} steps", expected.size,
            sqliteAnswers.size)
        for (step in expected.indices) {
            assertEquals("android_sync: the in-memory double answered wrongly at step ${step + 1}",
                expected[step], memoryAnswers.getOrNull(step))
            assertEquals("android_sync: the SQL twin answered wrongly at step ${step + 1}",
                expected[step], sqliteAnswers.getOrNull(step))
        }
        assertEquals("android_sync: the in-memory double must give exactly the expected answers",
            expected, memoryAnswers)
        assertEquals("android_sync: the SQL twin must give exactly the expected answers",
            expected, sqliteAnswers)
    }
}
