package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor calls of the phone adapter, checked on its source text, the way the existing source
 * test checks the queue calls. The adapter cannot run on a JVM, so what it binds, reads and clears
 * is read out of the file: comments blanked, string literals kept. Each check is a pure function
 * from source text to violations; it runs on short samples first, where a wrong one must be
 * reported and a correct one must not, and then on the real file. The queue statements are covered
 * by the queue source test; the migration dispatch is covered by SyncQueueMigratorSourceTest (it
 * moved out of this adapter when onUpgrade delegates to runOnUpgrade). This file covers the cursor
 * side only.
 */
class PullCursorSourceTest {

    private val cursorRefs = listOf(
        "SELECT_PULL_CURSOR", "SAVE_PULL_CURSOR", "CLEAR_PULL_CURSOR",
    )
    private val bind = Regex("\\b(bindString|bindLong)\\s*\\(\\s*(\\d+)\\s*,\\s*([^()]*?)\\s*\\)")

    private fun code(source: String) = AdapterSource.code(source)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    // ---- checks ----

    private fun cursorRefProblems(source: String): List<String> {
        val text = code(source)
        return cursorRefs.filterNot { Regex("\\bSyncQueueSql\\s*\\.\\s*$it\\b").containsMatchIn(text) }
            .map { "no reference to SyncQueueSql.$it" }
    }

    private fun saveBindProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "savePullCursor") ?: return listOf("found no function savePullCursor")
        val binds = bind.findAll(body).map { "bindString(${it.groupValues[2]}, ${it.groupValues[3]})" }.toList()
        if (binds == listOf("bindString(1, cursor)")) return emptyList()
        return listOf("savePullCursor's binds are $binds, the statement needs one bind at index 1")
    }

    private fun clearProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "clearPullCursor") ?: return listOf("found no function clearPullCursor")
        return if (body.contains("SyncQueueSql.CLEAR_PULL_CURSOR")) {
            emptyList()
        } else {
            listOf("clearPullCursor does not use the CLEAR_PULL_CURSOR statement")
        }
    }

    private fun cursorReadProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "pullCursor") ?: return listOf("found no function pullCursor")
        val index = Regex("\\bcursor\\s*\\.\\s*getString\\s*\\(\\s*(\\d+)\\s*\\)").find(body)?.groupValues?.get(1)
        return if (index == "0") emptyList() else listOf("pullCursor reads getString($index), the select returns the text at 0")
    }

    // ---- samples ----

    // ---- tests ----

    /** A failure here means the adapter dropped a cursor statement constant, so a call runs a different statement than the tested one. */
    @Test
    fun `the adapter references every cursor statement constant`() {
        assertFires("a missing constant", cursorRefProblems("val x = 1"), "no reference to SyncQueueSql.SELECT_PULL_CURSOR")
        val refs = cursorRefs.joinToString("\n") { "x(SyncQueueSql.$it)" }
        assertQuiet("every constant named", cursorRefProblems(refs))
        assertEquals(
            "android_sync: the adapter must use every cursor statement from SyncQueueSql",
            emptyList<String>(),
            cursorRefProblems(AdapterSource.load()),
        )
    }

    /** A failure here means the cursor is bound at the wrong index or the text is bound some other way, so a different value is stored. */
    @Test
    fun `the adapter saves the cursor with exactly one bind at index one`() {
        val good = "class A {\n    override fun savePullCursor(cursor: String) {\n        h.compileStatement(SyncQueueSql.SAVE_PULL_CURSOR).use { s ->\n            s.bindString(1, cursor)\n            s.executeInsert()\n        }\n    }\n\n    override fun pullCursor(): String? = null\n}\n"
        assertQuiet("a correct sample", saveBindProblems(good))
        val wrongIndex = good.replace("s.bindString(1, cursor)", "s.bindString(2, cursor)")
        assertFires("the bind at index 2", saveBindProblems(wrongIndex), "binds are")
        val extraBind = good.replace("s.bindString(1, cursor)", "s.bindString(1, cursor)\n            s.bindString(2, cursor)")
        assertFires("a second bind", saveBindProblems(extraBind), "binds are")
        val noBind = "class A {\n    override fun savePullCursor(cursor: String) {\n        h.compileStatement(SyncQueueSql.SAVE_PULL_CURSOR).use { s ->\n            s.executeInsert()\n        }\n    }\n\n    override fun pullCursor(): String? = null\n}\n"
        assertFires("no bind at all", saveBindProblems(noBind), "binds are")
        assertEquals(
            "android_sync: savePullCursor must bind the cursor text once, at index 1",
            emptyList<String>(),
            saveBindProblems(AdapterSource.load()),
        )
    }

    /** A failure here means the clear runs some other statement, possibly the queue's, so clearing the cursor can clear the queue. */
    @Test
    fun `the adapter clears the cursor through its own statement`() {
        val good = "class A {\n    override fun clearPullCursor() {\n        h.execSQL(SyncQueueSql.CLEAR_PULL_CURSOR)\n    }\n\n    override fun pullCursor(): String? = null\n}\n"
        assertQuiet("a correct sample", clearProblems(good))
        val wrong = "class A {\n    override fun clearPullCursor() {\n        h.execSQL(SyncQueueSql.DELETE_BY_CLIENT_ID)\n    }\n\n    override fun pullCursor(): String? = null\n}\n"
        assertFires("the queue delete statement", clearProblems(wrong), "does not use")
        assertFires("no function", clearProblems("class A {}"), "found no function")
        assertEquals(
            "android_sync: clearPullCursor must use the CLEAR_PULL_CURSOR statement",
            emptyList<String>(),
            clearProblems(AdapterSource.load()),
        )
    }

    /** A failure here means the read takes the column at the wrong position, so the cursor holds another value. */
    @Test
    fun `the adapter reads the cursor text at the first column`() {
        val good = "class A {\n    override fun pullCursor(): String? =\n        h.rawQuery(SyncQueueSql.SELECT_PULL_CURSOR, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }\n\n    override fun savePullCursor(cursor: String) {}\n}\n"
        assertQuiet("a correct sample", cursorReadProblems(good))
        val wrong = good.replace("cursor.getString(0)", "cursor.getString(1)")
        assertFires("the second column", cursorReadProblems(wrong), "getString")
        assertFires("no function", cursorReadProblems("class A {}"), "found no function")
        assertEquals(
            "android_sync: pullCursor must read the text at column 0",
            emptyList<String>(),
            cursorReadProblems(AdapterSource.load()),
        )
    }
}
