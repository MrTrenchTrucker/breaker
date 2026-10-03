package dev.breaker.dictation.history

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdapterStatements] on the real adapter, and on the [HistorySql] texts the adapter's own source
 * cannot show.
 *
 * The first block reads `SqliteHistoryDatabase` itself and pins what the JVM twin will run: the table,
 * the `WHERE` and the argument text of each delete, and the queries by name. It then reads every text
 * [HistorySql] holds, so a `DELETE` or `DROP` kept there and run by name is found even though the
 * adapter's source does not show it, and pins the checks that make that reading non-vacuous. The rest
 * feeds the same reader sources that are in shape, so that the refusals exercised elsewhere are seen to
 * be refusals of the planted mistake and not of the reader refusing everything.
 */

internal class AdapterStatementsTest {

    // ── the real adapter ─────────────────────────────────────────────────

    @Test
    fun `the three deletes the adapter runs are read with their table, where and arguments`() {
        val deletes = AdapterStatements.deletesIn(adapter)

        assertEquals(
            listOf("deleteById", "deleteCreatedBefore", "deleteTombstonesRecordedBefore"),
            deletes.keys.toList(),
        )
        val byId = deletes.getValue("deleteById")
        assertEquals(HistorySql.TABLE_TRANSCRIPTIONS, byId.table)
        assertEquals("HistorySql.TABLE_TRANSCRIPTIONS", byId.tableSource)
        assertEquals("id = ?", byId.where)
        assertEquals("\"id = ?\"", byId.whereSource)
        assertEquals("arrayOf<String?>(id)", byId.args)

        val created = deletes.getValue("deleteCreatedBefore")
        assertEquals(HistorySql.TABLE_TRANSCRIPTIONS, created.table)
        assertEquals("HistorySql.TABLE_TRANSCRIPTIONS", created.tableSource)
        assertEquals(HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE, created.where)
        assertEquals("HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE", created.whereSource)
        assertEquals("arrayOf<String?>(cutoff.toString())", created.args)

        val tombstones = deletes.getValue("deleteTombstonesRecordedBefore")
        assertEquals(HistorySql.TABLE_TOMBSTONES, tombstones.table)
        assertEquals("HistorySql.TABLE_TOMBSTONES", tombstones.tableSource)
        assertEquals(HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE, tombstones.where)
        assertEquals("HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE", tombstones.whereSource)
        assertEquals("arrayOf<String?>(cutoff.toString())", tombstones.args)
    }

    @Test
    fun `the delete by id says a row was removed only when a row was affected`() {
        val body = AdapterStatements.bodyOf(adapter, "deleteById")

        assertTrue("the body of deleteById is '$body'", body.endsWith("return affected > 0"))
    }

    @Test
    fun `the query for the rows a purge will tombstone passes its statement and the cutoff as text`() {
        val arguments = AdapterStatements.callArguments(adapter, "idsCreatedBefore", "rawQuery")

        assertEquals(listOf("HistorySql.SELECT_IDS_CREATED_BEFORE", "arrayOf(cutoff.toString())"), arguments)
    }

    @Test
    fun `every other query the adapter runs passes its own statement and binds its argument as text`() {
        assertEquals(
            listOf("HistorySql.SELECT_NEWEST", "arrayOf(limit.toString())"),
            AdapterStatements.callArguments(adapter, "newest", "rawQuery"),
        )
        assertEquals(
            listOf("HistorySql.SELECT_NEWEST_TOMBSTONES", "arrayOf(limit.toString())"),
            AdapterStatements.callArguments(adapter, "newestTombstones", "rawQuery"),
        )
        assertEquals(
            listOf("HistorySql.COUNT_TRANSCRIPTIONS", "emptyArray()"),
            AdapterStatements.callArguments(adapter, "countTranscriptions", "rawQuery"),
        )
    }

    @Test
    fun `the adapter holds no SQL text of its own except the predicate of the delete by id`() {
        val literals = AdapterStatements.stringLiterals(adapter)
        val sqlWords = Regex("""(?i)\b(select|insert|update|delete|create|drop|alter|pragma|where|from)\b""")

        assertEquals("the only bind placeholder in a literal", listOf("id = ?"), literals.filter { '?' in it })
        assertEquals(
            "a literal that reads as SQL",
            emptyList<String>(),
            literals.filter { sqlWords.containsMatchIn(it) },
        )
    }

    @Test
    fun `the adapter uses the name delete only in its three calls`() {
        assertEquals(emptyList<String>(), AdapterStatements.deleteUsesNotCalled(adapter))
    }

    @Test
    fun `the adapter names execSQL once in save, once in putTombstone and once in onCreate, and is accepted`() {
        assertEquals(
            mapOf("save" to 1, "putTombstone" to 1, "onCreate" to 1),
            AdapterStatements.execSqlUses(adapter),
        )
        assertEquals(3, AdapterStatements.deletesIn(adapter).size)
    }

    // ── the SQL the adapter could run by name ────────────────────────────

    @Test
    fun `no text HistorySql holds contains the word DELETE or the word DROP`() {
        val holding = historySqlTexts().filterValues { DELETE_OR_DROP_WORD.containsMatchIn(it) }

        assertEquals("the HistorySql texts that hold the word DELETE or DROP", emptyMap<String, String>(), holding)
    }

    @Test
    fun `the reading of HistorySql reaches every constant it declares, so the DELETE and DROP check is not vacuous`() {
        val texts = historySqlTexts()
        val declared = declaredStringConstants()

        assertTrue("the scan of HistorySql.kt found $declared", "SELECT_NEWEST" in declared)
        assertTrue("the scan of HistorySql.kt found $declared", "TABLE_TRANSCRIPTIONS" in declared)
        val unread = declared.filter { name ->
            name !in texts.keys && "get${name.replaceFirstChar(Char::uppercase)}()" !in texts.keys
        }
        assertEquals("String constants declared in HistorySql.kt, not read", emptyList<String>(), unread)
        assertTrue("read ${texts.size} texts for ${declared.size} String constants", texts.size >= declared.size)
        assertEquals("a val, through its field", HistorySql.SELECT_NEWEST, texts["SELECT_NEWEST"])
        assertEquals("a val, through its getter", HistorySql.SELECT_NEWEST, texts["getSELECT_NEWEST()"])
        assertEquals("a const val", HistorySql.TABLE_TRANSCRIPTIONS, texts["TABLE_TRANSCRIPTIONS"])
        assertEquals(
            "the elements of CREATE_ALL",
            HistorySql.CREATE_ALL.indices.associate { "CREATE_ALL[$it]" to HistorySql.CREATE_ALL[it] },
            texts.filterKeys { it.startsWith("CREATE_ALL[") },
        )
    }

    @Test
    fun `HistorySql declares no function that takes a parameter, because the text it builds cannot be read here`() {
        val builders = HistorySql::class.java.declaredMethods.filter { it.parameterCount > 0 && !it.isSynthetic }

        assertEquals("the functions of HistorySql that take a parameter", emptyList<String>(), builders.map { it.name })
    }

    @Test
    fun `the word DELETE or DROP is found whole and in any case, and not inside a longer word`() {
        val deletes = listOf("DELETE FROM transcriptions", "delete from x", "x;\nDelete\nFROM y", "ON DELETE CASCADE")
        val drops = listOf("DROP TABLE transcriptions", "drop index x", "x;\nDrop\nTABLE y", "a;DROP TABLE b")
        for (text in deletes + drops) {
            assertTrue("'$text' holds the word", DELETE_OR_DROP_WORD.containsMatchIn(text))
        }
        val longer = listOf("deleted_at < ?", "idx_tombstones_deleted_at", "UNDELETE", "delete_all", "DELETED")
        val longerDrops = listOf("DROPPED", "ADROP", "raindrop", "drop_all", "idx_drop_at", "DROPS", "dropbox")
        for (text in longer + longerDrops) {
            assertFalse("'$text' holds only a longer word", DELETE_OR_DROP_WORD.containsMatchIn(text))
        }
    }

    // ── a source that is in shape ────────────────────────────────────────

    @Test
    fun `a well-formed source is read, with comments and commas between the arguments`() {
        val deletes = AdapterStatements.deletesIn(source())

        assertEquals(
            listOf(
                listOf(HistorySql.TABLE_TRANSCRIPTIONS, "id = ?", "arrayOf<String?>(id)"),
                listOf(
                    HistorySql.TABLE_TRANSCRIPTIONS,
                    HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
                    "arrayOf<String?>(cutoff.toString())",
                ),
                listOf(
                    HistorySql.TABLE_TOMBSTONES,
                    HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
                    "arrayOf<String?>(cutoff.toString())",
                ),
            ),
            deletes.values.map { listOf(it.table, it.where, it.args) },
        )
    }

    @Test
    fun `a slash pair inside a string literal is not a comment`() {
        val slashes = delete(TRANSCRIPTIONS, quoted("id = ? // not a comment"))
        val deletes = AdapterStatements.deletesIn(source(byId = slashes))

        assertEquals("id = ? // not a comment", deletes.getValue("deleteById").where)
    }

    @Test
    fun `a slash pair inside a string template is not a comment either`() {
        val note = "val note = \"total \${count(\"a//b\")}\""
        val templated = "run { $note; " + delete(TRANSCRIPTIONS, quoted("id = ?")) + " }"
        val deletes = AdapterStatements.deletesIn(source(byId = templated))

        assertEquals("id = ?", deletes.getValue("deleteById").where)
    }

    @Test
    fun `a quote character literal does not open a string`() {
        val deletes = AdapterStatements.deletesIn(source(inSave = "val quote = '\"'"))

        assertEquals(3, deletes.size)
    }

    @Test
    fun `a comma and a parenthesis inside a string literal do not end the argument`() {
        val deletes = AdapterStatements.deletesIn(source(byId = delete(TRANSCRIPTIONS, quoted("a , ) b"))))

        assertEquals("a , ) b", deletes.getValue("deleteById").where)
        assertEquals("arrayOf<String?>(id)", deletes.getValue("deleteById").args)
    }

    @Test
    fun `the methods may come in any order`() {
        val reversed = source(order = listOf("tombstones", "created", "byId", "save"))

        assertEquals(
            listOf("deleteById", "deleteCreatedBefore", "deleteTombstonesRecordedBefore"),
            AdapterStatements.deletesIn(reversed).keys.toList(),
        )
        assertEquals(
            HistorySql.TABLE_TOMBSTONES,
            AdapterStatements.deletesIn(reversed).getValue("deleteTombstonesRecordedBefore").table,
        )
    }

    @Test
    fun `a delete call and a DELETE FROM in a comment are not counted`() {
        val commented = source(
            inSave = """
                // database.delete(HistorySql.TABLE_TRANSCRIPTIONS, "id = ?", arrayOf<String?>(id))
                /* helper.writableDatabase.execSQL("DELETE FROM transcriptions") */
                /* outer /* inner */ database.delete(HistorySql.TABLE_TRANSCRIPTIONS, "id = ?", arrayOf<String?>(id)) */
            """.trimIndent(),
        )

        assertEquals(3, AdapterStatements.deletesIn(commented).size)
    }

    @Test
    fun `a function named delete is not a use of the name, and a reference next to it is`() {
        val members = "private fun delete(id: String): Int = 0"
        val declared = source(members = members)
        val referenced = source(members = members, inSave = "val remove = database::delete")

        assertEquals(emptyList<String>(), AdapterStatements.deleteUsesNotCalled(declared))
        assertEquals(1, AdapterStatements.deleteUsesNotCalled(referenced).size)
    }

    @Test
    fun `a longer name, a word in a string and a word in a comment are not a use of delete`() {
        val quiet = source(
            inSave = """
                val deleted = 1
                val undelete = deleteAll
                val delete_all = "delete"
                val raw = ${TRIPLE}delete$TRIPLE
                // database::delete
                /* the delete of the row */
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), AdapterStatements.deleteUsesNotCalled(quiet))
        assertEquals(3, AdapterStatements.deletesIn(quiet).size)
    }

    @Test
    fun `a delete call with whitespace before its parenthesis is a call, not a bare use of the name`() {
        val spaced = source(byId = "database.delete\n    ($TRANSCRIPTIONS, ${quoted("id = ?")}, arrayOf<String?>(id))")

        assertEquals(emptyList<String>(), AdapterStatements.deleteUsesNotCalled(spaced))
        assertEquals("id = ?", AdapterStatements.deletesIn(spaced).getValue("deleteById").where)
    }

    // ── comments and literals ────────────────────────────────────────────

    @Test
    fun `the source scan drops comments and keeps literals as code`() {
        val source = """
            val a = 1 // getExternalFilesDir
            /* getExternalCacheDir /* nested */ still a comment */
            /** KDoc: externalMediaDirs */
            val b = "http://example" // tail
            val c = '"' + "/* held in a string */"
        """.trimIndent()

        val code = AdapterStatements.withoutComments(source)

        val inComments = listOf(
            "getExternalFilesDir",
            "getExternalCacheDir",
            "nested",
            "still a comment",
            "externalMediaDirs",
            "tail",
        )
        for (text in inComments) {
            assertFalse("'$text' sits in a comment and must not survive the scan", code.contains(text))
        }
        for (text in listOf("val a = 1", "val b = \"http://example\"", "val c = '\"' + \"/* held in a string */\"")) {
            assertTrue("'$text' is code and must survive the scan", code.contains(text))
        }
    }

    @Test
    fun `an escaped quote character, an escaped quote in a string and a raw string are kept whole`() {
        val source = """
            val q = '\'' // quote note
            val s = "an \" inside // kept" // string note
            val r = ${TRIPLE}a "" quote // kept
            /* kept */ still raw$TRIPLE // raw note
        """.trimIndent()

        val code = AdapterStatements.withoutComments(source)

        assertEquals(
            listOf(
                "val q = '\\''",
                "val s = \"an \\\" inside // kept\"",
                "val r = ${TRIPLE}a \"\" quote // kept",
                "/* kept */ still raw$TRIPLE",
            ),
            code.lines().map { it.trimEnd() },
        )
    }

    @Test
    fun `a string with a template expression is read and kept as code, and this used to be refused`() {
        val code = listOf(
            "val a = \"total \${1 + 1}\"",
            "val b = \"x \${f(\"}\")} y\"",
            "val c = \"p \${h(\"a//b\")} q\"",
            "val d = ${TRIPLE}r \${g($TRIPLE//$TRIPLE)} s$TRIPLE",
        )
        val commented = code.mapIndexed { index, line -> "$line // note ${index + 1}" }

        val read = AdapterStatements.withoutComments(commented.joinToString("\n"))

        assertEquals(code, read.lines().map { it.trimEnd() })
    }

    @Test
    fun `a block comment that never closes is refused, and the line it starts on is named`() {
        val open = scanRefused("val a = 1\nval b = 2 /* never closed\nval c = 3")
        assertTrue(open, open.contains("the block comment that starts on line 2 never ends"))

        val nested = scanRefused("val a = 1 /* outer /* inner */ still open\nval b = 2")
        assertTrue(nested, nested.contains("the block comment that starts on line 1 never ends"))
    }

    @Test
    fun `a string that never closes is refused, and the line it starts on is named`() {
        val open = scanRefused("val a = 1\nval b = \"never closed")
        assertTrue(open, open.contains("the string literal that starts on line 2 never ends"))

        val template = scanRefused("val a = 1\nval b = \"x \${1 + 1")
        assertTrue(template, template.contains("the string literal that starts on line 2 never ends"))

        val endOfLine = scanRefused("val a = \"never closed\nval b = \"x\"")
        assertTrue(endOfLine, endOfLine.contains("the string literal that starts on line 1 never ends"))
    }

    @Test
    fun `a raw string that never closes is refused, and the line it starts on is named`() {
        val open = scanRefused("val a = 1\nval b = ${TRIPLE}never closed\nstill raw")

        assertTrue(open, open.contains("the raw string literal that starts on line 2 never ends"))
    }

    @Test
    fun `a character literal that never closes is refused, and the line it starts on is named`() {
        val open = scanRefused("val a = 1\nval b = 'x")
        assertTrue(open, open.contains("the character literal that starts on line 2 never ends"))

        val endOfLine = scanRefused("val a = 'x\nval b = 'y'")
        assertTrue(endOfLine, endOfLine.contains("the character literal that starts on line 1 never ends"))
    }

    @Test
    fun `every way of reading the adapter refuses a source that ends inside a comment`() {
        val broken = source() + "\n/* never closed"
        val refusals = listOf<() -> Any>(
            { AdapterStatements.deletesIn(broken) },
            { AdapterStatements.callArguments(broken, "deleteById", "delete") },
            { AdapterStatements.bodyOf(broken, "deleteById") },
            { AdapterStatements.stringLiterals(broken) },
        ).map { read -> assertThrows(AdapterShapeError::class.java) { read() } }

        for (refusal in refusals) {
            assertTrue(refusal.message, refusal.message.orEmpty().contains("the block comment that starts on line"))
        }
    }

    // ── a source that is not: each is refused, and the reason is named ───
}
