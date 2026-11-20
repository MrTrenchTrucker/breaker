package dev.breaker.dictation.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pin for the shared sign-out clear list (the gate owner's unification): the PRODUCTION
 * adapter SqliteSyncQueueDatabase.clearAll must iterate SyncQueueSql.CLEAR_ALL_STATEMENTS and hold
 * no clear SQL of its own; the JVM twin JdbcSyncQueueDatabase.clearAll must iterate the same list.
 * Checked on source text the way SyncQueueMigratorSourceTest checks the dispatch: comments blanked
 * (AdapterSource.code), each check a pure function from source to violations, control samples first
 * (a wrong one must be reported, a correct one must not), then the real files. A missing file or
 * function is a failure, never a pass. This kills the "clearAll drops pending_save" mutant by name:
 * an adapter that holds its own DELETE_ALL_QUEUE-only SQL is reported.
 */
class ClearAllSourceTest {

    private fun code(source: String): String = AdapterSource.code(source)

    private fun adapterSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/sync/SqliteSyncQueueDatabase.kt")
        check(file.isFile) { "android_sync: expected SqliteSyncQueueDatabase.kt under src/main/kotlin/dev/breaker/dictation/sync" }
        return code(file.readText())
    }

    private fun twinSource(): String {
        val file = File("src/test/kotlin/dev/breaker/dictation/sync/JdbcSyncQueueDatabase.kt")
        check(file.isFile) { "android_sync: expected JdbcSyncQueueDatabase.kt under src/test/kotlin/dev/breaker/dictation/sync" }
        return code(file.readText())
    }

    /** The body of `override fun clearAll()` — from its opening brace to the MATCHING closing brace,
     * brace-balanced via [AdapterSource.closeOf] so it works at any indentation: the real file is
     * indented four spaces, the trimIndent control samples sit at column zero. (The old "\n    }"
     * search found no end in the column-zero samples and read every control as "no body found".) */
    private fun clearAllBody(text: String): String? {
        val at = text.indexOf("override fun clearAll()")
        if (at < 0) return null
        val start = text.indexOf('{', at)
        if (start < 0) return null
        val end = AdapterSource.closeOf(text, start)
        if (end < 0) return null
        return text.substring(start, end + 1)
    }

    private fun clearAllProblems(source: String): List<String> {
        val text = code(source)
        val body = clearAllBody(text) ?: return listOf("no clearAll function body found")
        val problems = mutableListOf<String>()
        if (!body.contains("SyncQueueSql.CLEAR_ALL_STATEMENTS")) {
            problems += "clearAll does not iterate SyncQueueSql.CLEAR_ALL_STATEMENTS"
        }
        if (body.contains("DELETE_ALL_QUEUE") || body.contains("DELETE_ALL_PENDING_SAVE") || body.contains("DELETE FROM")) {
            problems += "clearAll holds its own clear SQL instead of the shared list"
        }
        return problems
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `control a correct shared-list clearAll is quiet`() {
        assertQuiet(
            "shared-list clearAll",
            clearAllProblems(
                """
                override fun clearAll() {
                    SyncQueueSql.CLEAR_ALL_STATEMENTS.forEach { helper.writableDatabase.execSQL(it) }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `control a clearAll holding its own queue-only SQL is reported`() {
        assertFires(
            "own-SQL clearAll",
            clearAllProblems(
                """
                override fun clearAll() {
                    helper.writableDatabase.execSQL(SyncQueueSql.DELETE_ALL_QUEUE)
                }
                """.trimIndent(),
            ),
            "holds its own clear SQL",
        )
    }

    @Test
    fun `control a clearAll iterating a different list is reported`() {
        assertFires(
            "wrong-list clearAll",
            clearAllProblems(
                """
                override fun clearAll() {
                    SyncQueueSql.CREATE_ALL.forEach { helper.writableDatabase.execSQL(it) }
                }
                """.trimIndent(),
            ),
            "does not iterate SyncQueueSql.CLEAR_ALL_STATEMENTS",
        )
    }

    @Test
    fun `control a missing clearAll is reported`() {
        assertFires("missing clearAll", clearAllProblems("override fun count(): Int = 0"), "no clearAll")
    }

    @Test
    fun `the production adapter clearAll iterates the shared list and holds no own SQL`() {
        assertQuiet("production SqliteSyncQueueDatabase.clearAll", clearAllProblems(adapterSource()))
    }

    @Test
    fun `the JVM twin clearAll iterates the same shared list`() {
        assertQuiet("twin JdbcSyncQueueDatabase.clearAll", clearAllProblems(twinSource()))
    }
}
