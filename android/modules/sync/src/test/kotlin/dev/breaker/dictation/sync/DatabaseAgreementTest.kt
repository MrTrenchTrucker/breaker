package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-memory double and the SQLite twin must answer alike.
 *
 * The service tests run on [InMemorySyncQueueDatabase] and the SQL tests run on [JdbcSyncQueueDatabase]. If
 * the two disagreed about what a duplicate id, a removal of an absent id, an out-of-order creation time, the
 * next entry or the highest seq does, the service tests would prove rules about a queue the phone does not
 * have. One fixed script runs on both, and every answer is compared.
 *
 * The expected answers are also written out below as literals, so two twins that were wrong in the same way
 * would still fail.
 */
class DatabaseAgreementTest {

    /** The text of one entry as the script reports it: id, text and creation time. */
    private fun describe(entry: QueueEntry?): String =
        if (entry == null) "none" else "${entry.clientId}:${entry.text}:${entry.createdAtMs}"

    /**
     * Runs one fixed script against [db] and returns what it answered at each step, as text. The text names
     * the step and carries the boolean, the head or next entry, or the count. It leaves out seq, which the two
     * twins may number differently.
     */
    private fun runScript(db: SyncQueueDatabase): List<String> {
        val answers = ArrayList<String>()

        fun count() {
            answers.add("count=${db.count()}")
        }

        fun head() {
            answers.add("head=${describe(db.head())}")
        }

        fun next() {
            val current = db.head()
            answers.add("next=${describe(current?.let { db.nextAfter(it.seq) })}")
        }

        fun insert(id: String, text: String, createdAt: Long) {
            answers.add("insert $id=${db.insertIfAbsent(id, text, "local", "small.en", 1_200L, createdAt)}")
        }

        fun remove(id: String) {
            answers.add("remove $id=${db.remove(id)}")
        }

        fun tail() {
            answers.add(if (db.tailSeq() > 0L) "tail>0" else "tail=0")
        }

        count()
        head()
        insert("a", "a first", 500L)
        insert("b", "b text", 100L)
        insert("a", "a second", 50L)
        count()
        head()
        next()
        remove("zzz")
        remove("a")
        head()
        next()
        insert("c", "c text", 300L)
        insert("a", "a second", 50L)
        count()

        val inserted = db.transaction { db.insertIfAbsent("d", "d text", "local", "small.en", 1_200L, 400L) }
        answers.add("tx insert d=$inserted")
        try {
            db.transaction<Unit> {
                db.insertIfAbsent("e", "e text", "local", "small.en", 1_200L, 10L)
                throw IllegalStateException("android_sync: planned failure in the script")
            }
            answers.add("tx insert e committed")
        } catch (failure: IllegalStateException) {
            answers.add("tx insert e rolled back")
        }
        count()

        remove("b")
        head()
        remove("b")
        next()
        remove("c")
        head()
        remove("a")
        remove("d")
        head()
        count()
        remove("a")
        insert("z", "z text", 1L)
        tail()
        remove("z")
        tail()
        return answers
    }

    private val expected = listOf(
        "count=0",
        "head=none",
        "insert a=true",
        "insert b=true",
        "insert a=false",
        "count=2",
        "head=a:a first:500",
        "next=b:b text:100",
        "remove zzz=false",
        "remove a=true",
        "head=b:b text:100",
        "next=none",
        "insert c=true",
        "insert a=true",
        "count=3",
        "tx insert d=true",
        "tx insert e rolled back",
        "count=4",
        "remove b=true",
        "head=c:c text:300",
        "remove b=false",
        "next=a:a second:50",
        "remove c=true",
        "head=a:a second:50",
        "remove a=true",
        "remove d=true",
        "head=none",
        "count=0",
        "remove a=false",
        "insert z=true",
        "tail>0",
        "remove z=true",
        "tail=0",
    )

    /** A failure here means the double and the SQLite twin disagree, or one of them answers wrongly at some step. */
    @Test
    fun `the in-memory double and the SQLite twin agree on the same operations`() {
        val memoryAnswers = runScript(InMemorySyncQueueDatabase())
        val sqlite = DesktopSqlite()
        val sqliteAnswers = try {
            runScript(JdbcSyncQueueDatabase(sqlite))
        } finally {
            sqlite.close()
        }

        assertEquals("android_sync: the in-memory double must answer 33 steps", 33, memoryAnswers.size)
        assertEquals("android_sync: the SQLite twin must answer 33 steps", 33, sqliteAnswers.size)
        for (step in expected.indices) {
            assertEquals(
                "android_sync: the in-memory double answered wrongly at step ${step + 1}",
                expected[step],
                memoryAnswers.getOrNull(step),
            )
            assertEquals(
                "android_sync: the SQLite twin answered wrongly at step ${step + 1}",
                expected[step],
                sqliteAnswers.getOrNull(step),
            )
        }
        assertEquals("android_sync: the in-memory double must give exactly the expected answers", expected, memoryAnswers)
        assertEquals("android_sync: the SQLite twin must give exactly the expected answers", expected, sqliteAnswers)
    }
}
