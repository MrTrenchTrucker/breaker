package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SqliteSyncQueueDatabase` uses android.database.sqlite, so no plain JVM test can run it. What it binds,
 * reads, opens, rolls back and returns is checked on its source text, with comments blanked and string
 * literals kept. Each check is a pure function from source text to violations; it runs on the real file
 * (a missing file or function is a violation, never a pass) and first on short samples, where a wrong one
 * must be reported and a correct one must not.
 */
class SqliteSyncQueueDatabaseSourceTest {

    private val sqlWord = Regex("\\b(INSERT|DELETE|SELECT|CREATE|UPDATE|DROP)\\b", RegexOption.IGNORE_CASE)
    private val sqlRefs = listOf(
        "INSERT_IF_ABSENT", "SELECT_HEAD", "SELECT_NEXT_AFTER", "SELECT_TAIL_SEQ", "DELETE_BY_CLIENT_ID",
        "COUNT_QUEUE", "CREATE_ALL", "DATABASE_NAME", "SCHEMA_VERSION",
    )
    private val bind = Regex("\\b(bindString|bindLong)\\s*\\(\\s*(\\d+)\\s*,\\s*([^()]*?)\\s*\\)")
    private val propertyOf = mapOf(
        "client_id" to "clientId", "text" to "text", "source" to "source", "model" to "model",
        "duration_ms" to "durationMs", "created_at" to "createdAtMs",
    )
    private val longProperties = setOf("durationMs", "createdAtMs")
    private val read = Regex("\\b(\\w+)\\s*=\\s*cursor\\s*\\.\\s*(getLong|getString)\\s*\\(\\s*(\\d+)\\s*\\)")
    private val readOf = mapOf(
        "seq" to "seq=getLong", "client_id" to "clientId=getString", "text" to "text=getString",
        "source" to "source=getString", "model" to "model=getString", "duration_ms" to "durationMs=getLong",
        "created_at" to "createdAtMs=getLong",
    )
    private val sixColumns = listOf("client_id", "text", "source", "model", "duration_ms", "created_at")
    private val sevenColumns = listOf("seq") + sixColumns
    private val nextCall = Regex("readOne\\s*\\(\\s*SyncQueueSql\\s*\\.\\s*SELECT_NEXT_AFTER\\s*,\\s*arrayOf\\s*\\(\\s*seq\\s*\\.\\s*toString\\s*\\(\\s*\\)\\s*\\)\\s*\\)")
    private val tailCall = Regex("rawQuery\\s*\\(\\s*SyncQueueSql\\s*\\.\\s*SELECT_TAIL_SEQ\\s*,\\s*emptyArray\\(\\)\\s*\\)[\\s\\S]*?getLong\\s*\\(\\s*0\\s*\\)")

    private fun code(source: String) = AdapterSource.code(source)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("android_sync: control: $what must be reported, got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("android_sync: control: $what must not be reported", emptyList<String>(), problems)

    // ---- checks ----

    private fun statementProblems(source: String): List<String> {
        val text = code(source)
        val literals = AdapterSource.literals(text).filter { sqlWord.containsMatchIn(it) }.map { "SQL text in a literal: $it" }
        val missing = sqlRefs.filterNot { Regex("\\bSyncQueueSql\\s*\\.\\s*$it\\b").containsMatchIn(text) }
        return literals + missing.map { "no reference to SyncQueueSql.$it" }
    }

    private fun bindsOf(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "insertIfAbsent") ?: return emptyList()
        val parameter = Regex("insertIfAbsent\\s*\\(\\s*(\\w+)\\s*:").find(body)?.groupValues?.get(1) ?: return emptyList()
        return bind.findAll(body).map { "${it.groupValues[1]}(${it.groupValues[2]}, ${it.groupValues[3].removePrefix("$parameter.")})" }.toList()
    }

    private fun insertColumns(sql: String): List<String> =
        Regex("\\(([^)]*)\\)\\s*VALUES", RegexOption.IGNORE_CASE).find(sql)?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: emptyList()

    private fun bindProblems(source: String, columns: List<String>): List<String> {
        val actual = bindsOf(source)
        if (actual.isEmpty()) return listOf("found no bind calls in insertIfAbsent")
        val expected = columns.mapIndexed { index, column ->
            val property = propertyOf[column] ?: "?$column"
            "${if (property in longProperties) "bindLong" else "bindString"}(${index + 1}, $property)"
        }
        return if (actual == expected) emptyList() else listOf("binds are $actual, the statement columns need $expected")
    }

    private fun storageProblems(source: String): List<String> {
        val text = code(source)
        val problems = mutableListOf<String>()
        val calls = Regex("\\bSQLiteOpenHelper\\s*\\(([^)]*)\\)").findAll(text).map { it.groupValues[1].filterNot(Char::isWhitespace) }.toList()
        if (calls.size != 1) {
            problems += "found ${calls.size} SQLiteOpenHelper( calls, expected 1"
        } else if (calls[0] != "context,SyncQueueSql.DATABASE_NAME,null,SyncQueueSql.SCHEMA_VERSION") {
            problems += "SQLiteOpenHelper arguments are ${calls[0]}"
        }
        listOf("\\bMODE_WORLD", "\\bopenOrCreateDatabase\\b", "\\bFile\\s*\\(").forEach {
            if (Regex(it).containsMatchIn(text)) problems += "forbidden storage construct $it"
        }
        AdapterSource.literals(text).filter { Regex("^\"(/|[A-Za-z]:[\\\\/])").containsMatchIn(it) }
            .forEach { problems += "absolute path literal $it" }
        return problems
    }

    private fun transactionProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "transaction") ?: return listOf("found no function transaction")
        val tryAt = Regex("\\btry\\s*\\{").find(body) ?: return listOf("transaction has no try block")
        val tryRange = tryAt.range.last..AdapterSource.closeOf(body, tryAt.range.last)
        if (tryRange.last < 0) return listOf("the try block is never closed")
        val finallyAt = Regex("^\\s*finally\\s*\\{").find(body.substring(tryRange.last + 1))
            ?: return listOf("the try is not followed by a finally")
        val finallyOpen = tryRange.last + 1 + finallyAt.range.last
        val finallyRange = finallyOpen..AdapterSource.closeOf(body, finallyOpen)
        fun at(pattern: String) = Regex(pattern).findAll(body).map { it.range.first }.toList()
        val begin = at("\\bbeginTransaction\\s*\\(")
        val success = at("\\bsetTransactionSuccessful\\s*\\(")
        val end = at("\\bendTransaction\\s*\\(")
        val block = at("(?<![\\w.])block\\s*\\(").filter { it in tryRange }
        val problems = mutableListOf<String>()
        if (begin.size != 1 || begin[0] > tryRange.first) problems += "beginTransaction() is not called once before the try"
        if (success.size != 1) problems += "setTransactionSuccessful() is called ${success.size} times, expected once"
        if (success.any { it in finallyRange }) problems += "setTransactionSuccessful() is in the finally"
        if (success.none { it in tryRange }) problems += "setTransactionSuccessful() is not inside the try"
        if (block.isEmpty() || success.isEmpty() || block.first() > success.first()) problems += "setTransactionSuccessful() does not come after the block call in the try"
        if (end.size != 1 || end.none { it in finallyRange }) problems += "endTransaction() is not called once, in the finally"
        return problems
    }

    private fun readsOf(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "readEntry") ?: return emptyList()
        return read.findAll(body).map { "${it.groupValues[1]}=${it.groupValues[2]}(${it.groupValues[3]})" }.toList()
    }

    private fun readProblems(source: String, columns: List<String>): List<String> {
        val actual = readsOf(source)
        if (actual.isEmpty()) return listOf("found no cursor reads in readEntry")
        val expected = columns.mapIndexed { index, column -> readOf[column]?.let { "$it($index)" } ?: "unknown column $column" }
        return if (actual == expected) emptyList() else listOf("readEntry reads $actual, the select returns $expected")
    }

    private fun storedSourceProblems(source: String): List<String> {
        val body = AdapterSource.segment(code(source), "readEntry") ?: return listOf("found no function readEntry")
        val problems = mutableListOf<String>()
        if (!body.contains("source = cursor.getString(3),")) problems += "readEntry does not pass source = cursor.getString(3) through"
        listOf("valueOf", "TranscriptionSource", "MappingFailure", "?:", "runCatching", "getOrNull")
            .filter { body.contains(it) }.forEach { problems += "readEntry contains $it" }
        return problems
    }

    private fun callProblems(source: String, function: String, call: Regex): List<String> {
        val body = AdapterSource.segment(code(source), function) ?: return listOf("found no function $function")
        return if (call.containsMatchIn(body)) emptyList() else listOf("$function does not make the required statement call")
    }

    // ---- samples ----

    private val goodBinds = listOf(
        "bindString(1, clientId)", "bindString(2, text)", "bindString(3, source)", "bindString(4, model)", "bindLong(5, durationMs)", "bindLong(6, createdAtMs)",
    )

    private fun insertSample(binds: List<String>) =
        "class A {\n    override fun insertIfAbsent(clientId: String): Boolean =\n        db.use { s ->\n" +
            binds.joinToString("") { "            s.$it\n" } + "            s.executeInsert() != -1L\n        }\n\n" +
            "    override fun head(): Int = 1\n}\n"

    private fun transactionSample(tryBody: String, finallyBody: String) =
        "class A {\n    override fun <T> transaction(block: () -> T): T {\n        val d = h.db\n        d.beginTransaction()\n" +
            "        try {\n$tryBody        } finally {\n$finallyBody        }\n    }\n\n    override fun close() = h.close()\n}\n"

    private val goodTry = "            val r = block()\n            d.setTransactionSuccessful()\n            return r\n"
    private val goodFinally = "            d.endTransaction()\n"

    private val goodReads = listOf(
        "seq = cursor.getLong(0)", "clientId = cursor.getString(1)", "text = cursor.getString(2)", "source = cursor.getString(3)",
        "model = cursor.getString(4)", "durationMs = cursor.getLong(5)", "createdAtMs = cursor.getLong(6)",
    )

    private fun withSource(expression: String) = goodReads.take(3) + listOf(expression) + goodReads.drop(4)

    private fun entrySample(args: List<String>) =
        "class A {\n    private fun readEntry(cursor: Cursor): QueueEntry =\n        QueueEntry(\n" +
            args.joinToString("") { "            $it,\n" } + "        )\n\n    private class H\n}\n"

    private fun callSample(function: String, expression: String) =
        "class A {\n    override fun $function(): Any =\n        $expression\n\n    override fun remove(clientId: String): Boolean = true\n}\n"

    private val tailGood = "helper.readableDatabase.rawQuery(SyncQueueSql.SELECT_TAIL_SEQ, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }"

    // ---- tests ----

    /** A failure here means the adapter spells SQL of its own, or no longer uses a SyncQueueSql constant, so the tested statements are not the ones run. */
    @Test
    fun `the adapter takes every statement from SyncQueueSql`() {
        val refs = sqlRefs.joinToString("\n") { "x(SyncQueueSql.$it)" }
        val bad = "val a = db.compileStatement(\"INSERT OR IGNORE INTO t VALUES (?)\")\nval b = \"select * from t\"\n" +
            "val c = \"Delete from t\"\nval d = \"drop table t\"\nval e = \"CREATE TABLE t(x)\"\nval f = \"UPDATE t SET x = 1\"\n"
        assertEquals("android_sync: control: six SQL literals must give six violations", 6, statementProblems(bad + refs).size)
        assertFires("a missing constant", statementProblems("val x = 1"), "no reference to SyncQueueSql.CREATE_ALL")
        assertEquals("android_sync: control: no reference at all must give one violation per constant", sqlRefs.size, statementProblems("val x = 1").size)
        val quiet = "/** INSERT OR IGNORE and DELETE */\n// SELECT here\n$refs\nval u = \"http://x\" // CREATE\n"
        assertQuiet("keywords in comments, and a // in a string", statementProblems(quiet))
        val blanked = code("val a = \"http://x\" // INSERT\n/* DELETE */ val b = 1\n")
        assertTrue("android_sync: control: a // in a string must survive blanking", blanked.contains("\"http://x\""))
        assertFalse("android_sync: control: comments must be blanked", blanked.contains("INSERT") || blanked.contains("DELETE"))
        assertEquals("android_sync: the adapter must hold no SQL literal and use every constant", emptyList<String>(), statementProblems(AdapterSource.load()))
    }

    /** A failure here means a bind in insertIfAbsent moved, changed kind or was dropped, so a value would be stored in the wrong column. */
    @Test
    fun `insert binds the six columns in the order of the statement`() {
        assertQuiet("a correct sample", bindProblems(insertSample(goodBinds), sixColumns))
        val swapped = goodBinds.take(4) + listOf("bindLong(5, createdAtMs)", "bindLong(6, durationMs)")
        assertFires("bind 5 and 6 swapped", bindProblems(insertSample(swapped), sixColumns), "binds are")
        val wrongKind = goodBinds.take(4) + listOf("bindString(5, durationMs)", goodBinds[5])
        assertFires("bindString on a long column", bindProblems(insertSample(wrongKind), sixColumns), "binds are")
        assertFires("a missing bind", bindProblems(insertSample(goodBinds.take(5)), sixColumns), "binds are")
        assertFires("a statement with reordered columns", bindProblems(insertSample(goodBinds), listOf("text", "client_id") + sixColumns.drop(2)), "binds are")
        assertFires("no function", bindProblems("class A {}", sixColumns), "found no bind calls")
        assertQuiet("a bind in a comment", bindProblems("// s.bindLong(9, x)\n" + insertSample(goodBinds), sixColumns))
        val adapter = AdapterSource.load()
        assertEquals("android_sync: the statement's column list changed", sixColumns, insertColumns(SyncQueueSql.INSERT_IF_ABSENT))
        assertEquals("android_sync: insertIfAbsent must bind in this order", goodBinds, bindsOf(adapter))
        assertEquals("android_sync: binds must agree with the statement", emptyList<String>(), bindProblems(adapter, insertColumns(SyncQueueSql.INSERT_IF_ABSENT)))
    }

    /** A failure here means the database might be opened under another name or version, or outside the app-private directory. */
    @Test
    fun `the adapter opens the fixed private database name with the schema version`() {
        val good = "class H(context: Context) :\n    SQLiteOpenHelper(context, SyncQueueSql.DATABASE_NAME, null, SyncQueueSql.SCHEMA_VERSION) {\n}\n"
        assertQuiet("a correct sample", storageProblems(good))
        assertQuiet("a comment naming MODE_WORLD", storageProblems("// MODE_WORLD_READABLE is never used\n$good"))
        assertFires("a literal name", storageProblems(good.replace("SyncQueueSql.DATABASE_NAME", "\"x.db\"")), "SQLiteOpenHelper arguments")
        assertFires("a literal version", storageProblems(good.replace("SyncQueueSql.SCHEMA_VERSION", "1")), "SQLiteOpenHelper arguments")
        assertFires("no helper", storageProblems("class H"), "found 0 SQLiteOpenHelper")
        assertFires("MODE_WORLD", storageProblems(good + "val m = Context.MODE_WORLD_READABLE\n"), "MODE_WORLD")
        assertFires("openOrCreateDatabase", storageProblems(good + "val d = context.openOrCreateDatabase(n, 0, null)\n"), "openOrCreateDatabase")
        assertFires("File(", storageProblems(good + "val f = File(dir, n)\n"), "File")
        assertFires("an absolute path", storageProblems(good + "val p = \"/sdcard/q.db\"\n"), "absolute path literal")
        assertEquals("android_sync: the database name is the identity of the queue file, so renaming it strands queued entries on the phone", "breaker_sync.db", SyncQueueSql.DATABASE_NAME)
        assertEquals("android_sync: a schema version change needs a migration in onUpgrade, and this pin must change with it", 1, SyncQueueSql.SCHEMA_VERSION)
        assertEquals("android_sync: the adapter must open the private fixed name", emptyList<String>(), storageProblems(AdapterSource.load()))
    }

    /** A failure here means the adapter can commit a block that threw, or never commit one that finished, or leave a transaction open. */
    @Test
    fun `the adapter rolls back unless the block finished`() {
        assertQuiet("a correct sample", transactionProblems(transactionSample(goodTry, goodFinally)))
        assertQuiet("a comment in the finally", transactionProblems(transactionSample(goodTry, "            // setTransactionSuccessful() is not called here\n$goodFinally")))
        val inFinally = transactionSample("            val r = block()\n            return r\n", "            d.setTransactionSuccessful()\n$goodFinally")
        assertFires("success in the finally", transactionProblems(inFinally), "is in the finally")
        val before = "            d.setTransactionSuccessful()\n            val r = block()\n            return r\n"
        assertFires("success before the block", transactionProblems(transactionSample(before, goodFinally)), "does not come after the block")
        assertFires("success twice", transactionProblems(transactionSample(goodTry + "            d.setTransactionSuccessful()\n", goodFinally)), "2 times")
        assertFires("no success", transactionProblems(transactionSample("            return block()\n", goodFinally)), "0 times")
        assertFires("no end", transactionProblems(transactionSample(goodTry, "")), "endTransaction()")
        val noTry = "class A {\n    override fun <T> transaction(block: () -> T): T {\n        d.beginTransaction()\n        return block()\n    }\n\n    override fun close() = 1\n}\n"
        assertFires("no try", transactionProblems(noTry), "no try block")
        assertFires("no function", transactionProblems("class A {}"), "found no function transaction")
        assertEquals("android_sync: transaction must commit only a finished block", emptyList<String>(), transactionProblems(AdapterSource.load()))
    }

    /** A failure here means readEntry reads a cursor column at the wrong position, so a field of the entry would hold another column's value. */
    @Test
    fun `the adapter reads the columns in the order of the select`() {
        assertQuiet("a correct sample", readProblems(entrySample(goodReads), sevenColumns))
        val swapped = goodReads.take(1) + listOf(goodReads[2], goodReads[1]) + goodReads.drop(3)
        assertFires("positions 1 and 2 swapped", readProblems(entrySample(swapped), sevenColumns), "readEntry reads")
        val wrongKind = goodReads.take(5) + listOf("durationMs = cursor.getString(5)", goodReads[6])
        assertFires("getString on a long column", readProblems(entrySample(wrongKind), sevenColumns), "readEntry reads")
        assertFires("a reordered select", readProblems(entrySample(goodReads), listOf("seq", "text", "client_id") + sixColumns.drop(2)), "readEntry reads")
        val unnamed = goodReads.take(1) + listOf("cursor.getString(1)") + goodReads.drop(2)
        assertFires("an unnamed argument", readProblems(entrySample(unnamed), sevenColumns), "readEntry reads")
        assertFires("no function", readProblems("class A {}", sevenColumns), "found no cursor reads")
        val columns = SyncQueueSql.QUEUE_COLUMNS.split(',').map { it.trim() }
        assertEquals("android_sync: the select's column list changed", sevenColumns, columns)
        assertEquals(
            "android_sync: readEntry must read these positions",
            listOf("seq=getLong(0)", "clientId=getString(1)", "text=getString(2)", "source=getString(3)", "model=getString(4)", "durationMs=getLong(5)", "createdAtMs=getLong(6)"),
            readsOf(AdapterSource.load()),
        )
        assertEquals("android_sync: reads must agree with the select", emptyList<String>(), readProblems(AdapterSource.load(), columns))
    }

    /** A failure here means the stored source is converted or defaulted instead of being returned as the text it was stored as. */
    @Test
    fun `the adapter returns the stored source as text and converts nothing`() {
        assertQuiet("a correct sample", storedSourceProblems(entrySample(goodReads)))
        assertFires("a valueOf conversion", storedSourceProblems(entrySample(withSource("source = TranscriptionSource.valueOf(cursor.getString(3))"))), "valueOf")
        assertFires("a runCatching fallback", storedSourceProblems(entrySample(withSource("source = runCatching { cursor.getString(3) }.getOrNull()"))), "runCatching")
        assertFires("an elvis fallback", storedSourceProblems(entrySample(withSource("source = cursor.getString(3) ?: fallback"))), "?:")
        assertFires("no function", storedSourceProblems("class A {}"), "found no function readEntry")
        assertEquals("android_sync: readEntry must pass the stored source through as text", emptyList<String>(), storedSourceProblems(AdapterSource.load()))
    }

    /** A failure here means nextAfter reads a different row than the one after the given seq, or passes the bound some other way. */
    @Test
    fun `the adapter reads the next entry through the next statement with the seq bound`() {
        assertQuiet("a correct sample", callProblems(callSample("nextAfter", "readOne(SyncQueueSql.SELECT_NEXT_AFTER, arrayOf(seq.toString()))"), "nextAfter", nextCall))
        assertFires("the head statement with no bound", callProblems(callSample("nextAfter", "readOne(SyncQueueSql.SELECT_HEAD, emptyArray())"), "nextAfter", nextCall), "does not make")
        assertEquals("android_sync: nextAfter must pass the seq as its one argument", emptyList<String>(), callProblems(AdapterSource.load(), "nextAfter", nextCall))
    }

    /** A failure here means the drain bound is read from another statement, or from a column other than the highest seq. */
    @Test
    fun `the adapter reads the highest seq through the tail statement`() {
        assertQuiet("a correct sample", callProblems(callSample("tailSeq", tailGood), "tailSeq", tailCall))
        val count = tailGood.replace("SELECT_TAIL_SEQ", "COUNT_QUEUE")
        assertFires("the count statement", callProblems(callSample("tailSeq", count), "tailSeq", tailCall), "does not make")
        assertEquals("android_sync: tailSeq must read the tail statement with no argument", emptyList<String>(), callProblems(AdapterSource.load(), "tailSeq", tailCall))
    }
}
