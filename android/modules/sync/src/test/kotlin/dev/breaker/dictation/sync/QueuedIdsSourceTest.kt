package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two queue reads of the phone adapter, checked on its source text.
 *
 * The adapter cannot run on a JVM, so what it binds and reads is read out of the file: comments blanked,
 * string literals kept. Each check is a pure function from source text to violations; it runs on short
 * samples first, where a wrong one must be reported and a correct one must not, and then on the real file.
 * The existing queue statements are covered by SqliteSyncQueueDatabaseSourceTest; this file covers the
 * two new reads only.
 */
class QueuedIdsSourceTest {

    private val sqlRefs = listOf("IS_QUEUED", "SELECT_QUEUED_IDS")

    private fun code(source: String) = AdapterSource.code(source)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    // ---- checks ----

    private fun sqlRefProblems(source: String): List<String> {
        val text = code(source)
        return sqlRefs.filterNot { Regex("\\bSyncQueueSql\\s*\\.\\s*$it\\b").containsMatchIn(text) }
            .map { "no reference to SyncQueueSql.$it" }
    }

    private fun isQueuedBindProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "isQueued") ?: return listOf("found no function isQueued")
        return if (body.contains("SyncQueueSql.IS_QUEUED") && body.contains("arrayOf(clientId)") &&
            body.contains("moveToFirst()")) {
            emptyList()
        } else {
            listOf("isQueued does not use IS_QUEUED with arrayOf(clientId) and moveToFirst")
        }
    }

    private fun queuedIdsReadProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "queuedIds") ?: return listOf("found no function queuedIds")
        val problems = mutableListOf<String>()
        if (!body.contains("SyncQueueSql.SELECT_QUEUED_IDS")) problems += "queuedIds does not use SELECT_QUEUED_IDS"
        if (!body.contains("getString(0)")) problems += "queuedIds does not read getString(0)"
        if (!body.contains("moveToNext()")) problems += "queuedIds does not read with moveToNext"
        return problems
    }

    // ---- samples ----

    // ---- tests ----

    /** A failure here means the adapter dropped one of the two new statement constants. */
    @Test
    fun `the adapter references both new queue-read statement constants`() {
        val refs = sqlRefs.joinToString("\n") { "x(SyncQueueSql.$it)" }
        assertFires("a missing constant", sqlRefProblems("val x = 1"), "no reference to SyncQueueSql.IS_QUEUED")
        assertQuiet("both constants named", sqlRefProblems(refs))
        assertEquals(
            "android_sync: the adapter must use both new queue-read statements from SyncQueueSql",
            emptyList<String>(),
            sqlRefProblems(AdapterSource.load()),
        )
    }

    /** A failure here means isQueued binds the id at the wrong index, so a different id is tested. */
    @Test
    fun `the adapter binds the id at index one for isQueued`() {
        val good = "class A {\n    override fun isQueued(clientId: String): Boolean =\n        db.rawQuery(SyncQueueSql.IS_QUEUED, arrayOf(clientId)).use { c -> c.moveToFirst() }\n}\n"
        assertQuiet("a correct sample", isQueuedBindProblems(good))
        val wrongIndex = good.replace("arrayOf(clientId)", "arrayOf(\"x\")")
        assertFires("not arrayOf(clientId)", isQueuedBindProblems(wrongIndex), "does not use")
        assertEquals(
            "android_sync: isQueued must bind the id through arrayOf(clientId)",
            emptyList<String>(),
            isQueuedBindProblems(AdapterSource.load()),
        )
    }

    /** A failure here means queuedIds reads another column or statement, so the list holds wrong values. */
    @Test
    fun `the adapter reads queuedIds through the select statement at column zero`() {
        val good = "class A {\n    override fun queuedIds(): List<String> =\n        db.rawQuery(SyncQueueSql.SELECT_QUEUED_IDS, emptyArray()).use { c ->\n            val ids = ArrayList<String>()\n            while (c.moveToNext()) ids.add(c.getString(0))\n            ids\n        }\n}\n"
        assertQuiet("a correct sample", queuedIdsReadProblems(good))
        val wrongColumn = good.replace("c.getString(0)", "c.getString(1)")
        assertFires("column one", queuedIdsReadProblems(wrongColumn), "getString")
        val wrongStatement = good.replace("SELECT_QUEUED_IDS", "COUNT_QUEUE")
        assertFires("wrong statement", queuedIdsReadProblems(wrongStatement), "does not use")
        assertEquals(
            "android_sync: queuedIds must read client_id at column 0 through SELECT_QUEUED_IDS",
            emptyList<String>(),
            queuedIdsReadProblems(AdapterSource.load()),
        )
    }
}
