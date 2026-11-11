package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delete rows of the phone's adapter, read as text.
 *
 * `SqliteSyncQueueDatabase` uses android.database.sqlite, so no plain JVM test can run it, and the
 * twin hard-codes the binds and the table names, so a change to the adapter's own body would leave
 * every twin test green. The delete rows — the kind a delete enqueues under, and the table clearAll
 * empties — are checked on the adapter's source text, with comments blanked and string literals kept.
 * Each check is a pure function from source text to violations; it runs on the real file (a missing
 * file or function is a violation, never a pass) and first on short samples, where a wrong one must
 * be reported and a correct one must not.
 */
class SqliteSyncQueueDatabaseDeleteRowsSourceTest {

    private fun code(source: String) = AdapterSource.code(source)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    // ---- checks ----

    /** The binds of insertDeleteIfAbsent, in statement order. */
    private fun deleteBinds(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "insertDeleteIfAbsent") ?: return emptyList()
        return Regex("""\b(bindString|bindLong)\s*\(\s*(\d+)\s*,\s*([^()]*?)\s*\)""")
            .findAll(body).map { "${it.groupValues[1]}(${it.groupValues[2]}, ${it.groupValues[3]})" }.toList()
    }

    private fun deleteBindProblems(source: String): List<String> {
        val actual = deleteBinds(source)
        if (actual.isEmpty()) return listOf("found no bind calls in insertDeleteIfAbsent")
        val expected = listOf(
            "bindString(1, clientId)",
            "bindString(2, \"\")",
            "bindString(3, \"local\")",
            "bindString(4, \"\")",
            "bindLong(5, 0L)",
            "bindLong(6, 0L)",
            "bindString(7, SyncQueueSql.KIND_DELETE)",
        )
        return if (actual == expected) emptyList() else listOf("delete binds are $actual, the statement needs $expected")
    }

    private fun clearAllProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "clearAll") ?: return listOf("found no function clearAll")
        val problems = mutableListOf<String>()
        if (!body.contains("SyncQueueSql.DELETE_ALL_QUEUE")) problems += "clearAll does not run SyncQueueSql.DELETE_ALL_QUEUE"
        if (body.contains("SyncQueueSql.CLEAR_PULL_CURSOR")) problems += "clearAll runs the pull-cursor clear, not the queue clear"
        return problems
    }

    // ---- samples ----

    private val goodDeleteBinds = listOf(
        "bindString(1, clientId)",
        "bindString(2, \"\")",
        "bindString(3, \"local\")",
        "bindString(4, \"\")",
        "bindLong(5, 0L)",
        "bindLong(6, 0L)",
        "bindString(7, SyncQueueSql.KIND_DELETE)",
    )

    private fun deleteSample(binds: List<String>) =
        "class A {\n" +
            "    override fun insertDeleteIfAbsent(clientId: String): Boolean =\n" +
            "        helper.writableDatabase.compileStatement(SyncQueueSql.INSERT_IF_ABSENT).use { statement ->\n" +
            binds.joinToString("") { "            statement.$it\n" } +
            "            statement.executeInsert() != -1L\n" +
            "        }\n" +
            "}\n"

    // ---- tests ----

    /** SYNC_13: a failure here means a delete enqueued through the adapter is stored under the wrong kind. */
    @Test
    fun `a delete enqueued through the adapter is stored under kind delete`() {
        assertQuiet("a correct sample", deleteBindProblems(deleteSample(goodDeleteBinds)))
        val saveKind = goodDeleteBinds.take(6) + listOf("bindString(7, SyncQueueSql.KIND_SAVE)")
        assertFires("kind save at bind 7", deleteBindProblems(deleteSample(saveKind)), "delete binds are")
        val literalKind = goodDeleteBinds.take(6) + listOf("bindString(7, \"delete\")")
        assertFires("a literal kind", deleteBindProblems(deleteSample(literalKind)), "delete binds are")
        assertFires("no function", deleteBindProblems("class A {}"), "found no bind calls")
        assertEquals("android_sync: the adapter must enqueue a delete under KIND_DELETE", emptyList<String>(), deleteBindProblems(AdapterSource.load()))
    }

    /** SYNC_R3: a failure here means clearAll empties the pull cursor instead of the queue. */
    @Test
    fun `clearAll empties the queue table, not the pull cursor`() {
        assertQuiet("a correct sample", clearAllProblems("class A {\n    override fun clearAll() =\n        helper.writableDatabase.execSQL(SyncQueueSql.DELETE_ALL_QUEUE)\n}\n"))
        val wrongTable = "class A {\n    override fun clearAll() =\n        helper.writableDatabase.execSQL(SyncQueueSql.CLEAR_PULL_CURSOR)\n}\n"
        assertFires("the pull-cursor clear", clearAllProblems(wrongTable), "pull-cursor clear")
        assertFires("no function", clearAllProblems("class A {}"), "found no function clearAll")
        assertEquals("android_sync: clearAll must empty the queue table", emptyList<String>(), clearAllProblems(AdapterSource.load()))
    }
}
