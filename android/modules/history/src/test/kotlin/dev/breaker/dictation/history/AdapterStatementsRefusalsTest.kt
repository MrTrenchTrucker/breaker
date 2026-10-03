package dev.breaker.dictation.history

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdapterStatements] on sources made up to be wrong, one planted mistake or one absent method each.
 *
 * Every test here is a refusal: a source the reader cannot vouch for must raise an [AdapterShapeError]
 * saying what it found and what it expected. A reader that refused nothing would pass all of them, so
 * the sources are as close to well formed as each mistake allows.
 */

internal class AdapterStatementsRefusalsTest {


    @Test
    fun `a source with no delete call at all is refused`() {
        val message = refused(source(byId = "0", created = "0", tombstones = "0"))

        assertTrue(message, message.contains("found 0 delete( calls"))
        assertTrue(message, message.contains("expected exactly 3"))
    }

    @Test
    fun `a source with a fourth delete call is refused`() {
        val message = refused(source(inSave = delete(quoted("scratch"), quoted("id = ?"))))

        assertTrue(message, message.contains("found 4 delete( calls"))
        assertTrue(message, message.contains("expected exactly 3"))
    }

    @Test
    fun `a delete call moved out of its method is refused, and the method it moved to is named`() {
        val message = refused(source(byId = "0", inSave = delete(TRANSCRIPTIONS, quoted("id = ?"))))

        assertTrue(message, message.contains("found a delete( call in 'save'"))
        assertTrue(message, message.contains("one in each of deleteById, deleteCreatedBefore"))
    }

    @Test
    fun `a method with two delete calls while another has none is refused`() {
        val twice = delete(TRANSCRIPTIONS, quoted("id = ?")) + " +\n" + delete(TRANSCRIPTIONS, quoted("id = ?"))
        val message = refused(source(byId = "0", created = twice))

        assertTrue(message, message.contains("deleteById holds 0 delete( calls"))
        assertTrue(message, message.contains("deleteCreatedBefore holds 2 delete( calls"))
        assertTrue(message, message.contains("expected exactly 1 in each of"))
    }

    @Test
    fun `a source without one of the three methods is refused`() {
        val message = refused(source().replace("override fun deleteById(", "override fun removeById("))

        assertTrue(message, message.contains("declares no method named deleteById"))
    }

    @Test
    fun `a delete call behind a helper function is refused, and the helper is named`() {
        val helper = "private fun removeWhere(cutoff: Long): Int = " +
            delete(TRANSCRIPTIONS, "HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE", "arrayOf<String?>(cutoff.toString())")
        val message = refused(source(created = "removeWhere(cutoff)", members = helper))

        assertTrue(message, message.contains("found a delete( call in 'removeWhere'"))
    }

    @Test
    fun `a where taken from a call is refused, and the message names the expression`() {
        val message = refused(source(byId = delete(TRANSCRIPTIONS, "whereFor(id)")))

        assertTrue(message, message.contains("cannot resolve the where expression `whereFor(id)`"))
    }

    @Test
    fun `a where built by concatenation or by a template is refused, and the message names it`() {
        val joined = refused(source(byId = delete(TRANSCRIPTIONS, "\"created_at < \" + cutoff")))
        assertTrue(joined, joined.contains("cannot resolve the where expression `\"created_at < \" + cutoff`"))

        val templated = refused(source(byId = delete(TRANSCRIPTIONS, "\"id = ?\$suffix\"")))
        assertTrue(templated, templated.contains("cannot resolve the where expression `\"id = ?\$suffix\"`"))
    }

    @Test
    fun `a null where is refused, because it would delete every row`() {
        val message = refused(source(byId = delete(TRANSCRIPTIONS, "null")))

        assertTrue(message, message.contains("the where of the delete in deleteById is null"))
        assertTrue(message, message.contains("deletes all rows"))
    }

    @Test
    fun `a blank where is refused`() {
        val message = refused(source(byId = delete(TRANSCRIPTIONS, quoted(" "))))

        assertTrue(message, message.contains("the where of the delete in deleteById is the blank literal"))
    }

    @Test
    fun `a table this reader does not know is refused, and the message names it`() {
        val message = refused(source(byId = delete("HistorySql.TABLE_OTHER", quoted("id = ?"))))

        assertTrue(message, message.contains("the table of the delete in deleteById is HistorySql.TABLE_OTHER"))
    }

    @Test
    fun `a delete call that does not take three arguments is refused`() {
        val message = refused(source(byId = "database.delete($TRANSCRIPTIONS, ${quoted("id = ?")})"))

        assertTrue(message, message.contains("the delete( call in deleteById has 2 arguments, expected 3"))
    }

    @Test
    fun `a delete made through execSQL with a DELETE FROM text is refused`() {
        val execSql = "helper.writableDatabase.execSQL(\"DELETE FROM transcriptions WHERE id = ?\", arrayOf<Any?>(id))"
        val message = refused(source(inSave = execSql))

        assertTrue(message, message.contains("deletes another way than SQLiteDatabase.delete"))
        assertTrue(message, message.contains("DELETE FROM"))
    }

    @Test
    fun `a delete made through a compiled statement is refused`() {
        val compiled = "helper.writableDatabase.compileStatement(HistorySql.SELECT_NEWEST).executeUpdateDelete()"
        val message = refused(source(inSave = compiled))

        assertTrue(message, message.contains("deletes another way than SQLiteDatabase.delete"))
        assertTrue(message, message.contains("compileStatement"))
    }

    @Test
    fun `a delete made through a HistorySql constant is refused`() {
        val message = refused(source(inSave = "helper.writableDatabase.execSQL(HistorySql.DELETE_ALL)"))

        assertTrue(message, message.contains("deletes another way than SQLiteDatabase.delete"))
        assertTrue(message, message.contains("HistorySql.DELETE_ALL"))
    }

    @Test
    fun `a method reference to delete is refused, and the code around it is shown`() {
        val bound = refused(source(inSave = "ids.forEach(database::delete)"))
        assertTrue(bound, bound.contains("the name delete used other than in a call"))
        assertTrue(bound, bound.contains("ids.forEach(database::delete)"))

        val unbound = refused(source(inSave = "val remove = SQLiteDatabase::delete"))
        assertTrue(unbound, unbound.contains("the name delete used other than in a call"))
        assertTrue(unbound, unbound.contains("val remove = SQLiteDatabase::delete"))
    }

    @Test
    fun `the bare name delete used as a value is refused, and the code around it is shown`() {
        val message = refused(source(inSave = "val remove = delete\nrun(remove)"))

        assertTrue(message, message.contains("the name delete used other than in a call"))
        assertTrue(message, message.contains("val remove = delete run(remove)"))
    }

    // ── code hidden in a string template is still code ───────────────────

    @Test
    fun `a delete call written inside a string template expression is found and the source is refused`() {
        val hole = "private fun hide() = \"\${$HIDDEN_DELETE}\""

        val message = refused(source(members = hole))

        assertTrue(message, message.contains("found 4 delete( calls"))
        assertTrue(message, message.contains("deleteTombstonesRecordedBefore, hide)"))
        assertTrue(message, message.contains("expected exactly 3"))
    }

    @Test
    fun `a delete call hidden in a template is found whatever the expression around it holds`() {
        val hidden = mapOf(
            "text before the template" to "private fun hide() = \"x \${$HIDDEN_DELETE}\"",
            "no receiver, the name right after the brace" to
                templated("delete(HistorySql.TABLE_TRANSCRIPTIONS, null, null)"),
            "braces around the call" to templated("run { $HIDDEN_DELETE }"),
            "a literal holding a brace before it" to templated("run { \"}\" + $HIDDEN_DELETE }"),
            "a literal holding an escaped dollar brace before it" to templated("run { \"\\\${\" + $HIDDEN_DELETE }"),
            "a literal with a template of its own before it" to templated("run { \"x \${\"}\"} y\" + $HIDDEN_DELETE }"),
            "a raw string with a backslash before the template" to
                "private fun hide() = ${TRIPLE}a \\\${$HIDDEN_DELETE} b$TRIPLE",
            "a raw string template" to "private fun hide() = ${TRIPLE}text \${$HIDDEN_DELETE} text$TRIPLE",
            "a raw string template over two lines" to
                "private fun hide() = ${TRIPLE}text\n\${\n    $HIDDEN_DELETE\n}\ntext$TRIPLE",
        )

        for ((shape, member) in hidden) {
            val message = refused(source(members = member))

            assertTrue("$shape: $message", message.contains("found 4 delete( calls"))
            assertTrue("$shape: $message", message.contains("deleteTombstonesRecordedBefore, hide)"))
        }
    }

    @Test
    fun `a name the reader refuses is found inside a string template expression too`() {
        val hidden = mapOf(
            "the bare name delete" to Pair("database::delete", "the name delete used other than in a call"),
            "compileStatement" to Pair("db.compileStatement(HistorySql.SELECT_NEWEST)", "a call to compileStatement"),
            "executeUpdateDelete" to Pair("statement.executeUpdateDelete()", "a call to executeUpdateDelete"),
            "deleteDatabase" to Pair("context.deleteDatabase(\"x\")", "the name deleteDatabase"),
            "deleteFile" to Pair("context.deleteFile(\"x\")", "the name deleteFile"),
            "deleteRecursively" to Pair("file.deleteRecursively()", "the name deleteRecursively"),
            "execSQL" to Pair("db.execSQL(HistorySql.INSERT_OR_REPLACE)", "hide (1)"),
            "execSQL right after the brace" to Pair("execSQL(HistorySql.INSERT_OR_REPLACE)", "hide (1)"),
            "a method reference to execSQL" to Pair("db::execSQL", "hide (1)"),
        )

        for ((shape, found) in hidden) {
            val (expression, expected) = found
            val message = refused(source(members = templated(expression)))

            assertTrue("$shape: $message", message.contains(expected))
        }
    }

    @Test
    fun `the text of a template and the literals inside its expression are still not code`() {
        val quiet = listOf(
            "private fun note() = \"delete( execSQL compileStatement deleteFile \${f(\"delete( execSQL\")} delete(\"",
            "private fun raw() = ${TRIPLE}delete( execSQL \${f(\"deleteDatabase\")} executeUpdateDelete$TRIPLE",
            "private fun brace() = \"execSQL \${run { \"}\" }} delete( \${'\$'}{ execSQL\"",
        )

        for (member in quiet) {
            assertEquals(member, 3, AdapterStatements.deletesIn(source(members = member)).size)
            assertEquals(member, emptyList<String>(), AdapterStatements.deleteUsesNotCalled(source(members = member)))
            assertEquals(
                member,
                mapOf("save" to 1, "putTombstone" to 1, "onCreate" to 1),
                AdapterStatements.execSqlUses(source(members = member)),
            )
        }
    }

    // ── execSQL is run from three places and no other ────────────────────

    @Test
    fun `a delete split over two constants and run through execSQL in a new method is refused`() {
        val wipe = "private fun wipe() =\n" +
            "    helper.writableDatabase.execSQL(HistorySql.FIRST_HALF + HistorySql.SECOND_HALF)"
        val message = refused(source(members = wipe))

        assertTrue(message, message.contains("found the name execSQL used in save (1), putTombstone (1), wipe (1)"))
        assertTrue(message, message.contains("onCreate (1)"))
        assertTrue(message, message.contains("expected exactly one use in each of save, putTombstone, onCreate"))
    }

    @Test
    fun `a second execSQL in save, or none in putTombstone, is refused with the method and the count`() {
        val twice = refused(source(inSave = "helper.writableDatabase.execSQL(HistorySql.INSERT_OR_REPLACE)"))
        assertTrue(twice, twice.contains("found the name execSQL used in save (2), putTombstone (1), onCreate (1)"))

        val none = refused(source(putTombstone = "helper.writableDatabase.insert(tombstone)"))
        assertTrue(none, none.contains("found the name execSQL used in save (1), onCreate (1), expected exactly one"))
    }

    @Test
    fun `a method reference to execSQL and a bare use of the name are counted like a call`() {
        val reference = "private fun wipe(sql: List<String>) = sql.forEach(helper.writableDatabase::execSQL)"
        val bare = "private fun alias() {\n    val run = execSQL\n}"

        assertEquals(1, AdapterStatements.execSqlUses(source(members = reference))["wipe"])
        assertEquals(1, AdapterStatements.execSqlUses(source(members = bare))["alias"])
        for (member in listOf(reference, bare)) {
            val message = refused(source(members = member))

            assertTrue(message, message.contains("found the name execSQL used in save (1), putTombstone (1)"))
        }
    }

    @Test
    fun `the uses of execSQL are counted by method, and a longer name, a string and a comment are not uses`() {
        val quiet = "private fun quiet() {\n    val execSQLs = 1\n    val myexecSQL = 2\n" +
            "    val s = \"execSQL\" // execSQL\n}"
        val accepted = mapOf("save" to 1, "putTombstone" to 1, "onCreate" to 1)

        assertEquals(mapOf("(outside any method)" to 1), AdapterStatements.execSqlUses("val stray = db::execSQL"))
        assertEquals(accepted, AdapterStatements.execSqlUses(source(members = quiet)))
        assertEquals(3, AdapterStatements.deletesIn(source(members = quiet)).size)
    }

    @Test
    fun `an onCreate that runs a list other than CREATE_ALL through execSQL is refused`() {
        val wipe = refused(source(onCreate = "HistorySql.WIPE_ALL.forEach(db::execSQL)"))
        assertTrue(wipe, wipe.contains(ON_CREATE_REFUSAL))
        assertTrue(wipe, wipe.contains("found [WIPE_ALL]"))

        val both = refused(source(onCreate = "(HistorySql.CREATE_ALL + HistorySql.WIPE_ALL).forEach(db::execSQL)"))
        assertTrue(both, both.contains(ON_CREATE_REFUSAL))
        assertTrue(both, both.contains("found [CREATE_ALL, WIPE_ALL]"))

        val named = refused(source(onCreate = "HistorySql.CREATE_ALL_AND_MORE.forEach(db::execSQL)"))
        assertTrue(named, named.contains("found [CREATE_ALL_AND_MORE]"))
    }

    @Test
    fun `an onCreate whose own body does not run execSQL is refused even when the name follows it`() {
        val message = refused(source(onCreate = "HistorySql.CREATE_ALL.size", afterOnCreate = "val run = db::execSQL"))

        assertTrue(message, message.contains(ON_CREATE_REFUSAL))
        assertTrue(message, message.contains("found [CREATE_ALL] in `HistorySql.CREATE_ALL.size`"))
    }

    // ── other ways to destroy data ───────────────────────────────────────

    @Test
    fun `a call to deleteDatabase, deleteFile or deleteRecursively is refused, and the word is named`() {
        for (word in listOf("deleteDatabase", "deleteFile", "deleteRecursively")) {
            val message = refused(source(inSave = "context.$word(\"history.db\")"))

            assertTrue(message, message.contains("deletes another way than SQLiteDatabase.delete: the name $word;"))
        }
    }

    @Test
    fun `those names inside a string, a comment or a longer name are not refused`() {
        val quiet = source(
            inSave = """
                val deleteFileCount = 1
                val undeleteDatabase = deleteRecursivelyLater
                val note = "deleteDatabase deleteFile deleteRecursively" // deleteDatabase
                /* context.deleteFile("x") */
            """.trimIndent(),
        )

        assertEquals(3, AdapterStatements.deletesIn(quiet).size)
    }

    // ── each refusal, tripped on its own ─────────────────────────────────

    @Test
    fun `an execSQL statement that holds the word delete without a FROM is refused by the execSQL check alone`() {
        val message = refused(source(saveStatement = quoted("delete")))

        assertEquals(
            "the adapter deletes another way than SQLiteDatabase.delete: an execSQL call that mentions delete; " +
                "expected only the three delete( calls, since nothing else is read here",
            message,
        )
    }

    @Test
    fun `executeUpdateDelete without compileStatement is refused, and only it is named`() {
        val message = refused(source(inSave = "statement.executeUpdateDelete()"))

        assertEquals(
            "the adapter deletes another way than SQLiteDatabase.delete: a call to executeUpdateDelete; " +
                "expected only the three delete( calls, since nothing else is read here",
            message,
        )
    }

    @Test
    fun `compileStatement without executeUpdateDelete is refused, and only it is named`() {
        val message = refused(source(inSave = "val statement = db.compileStatement(HistorySql.SELECT_NEWEST)"))

        assertEquals(
            "the adapter deletes another way than SQLiteDatabase.delete: a call to compileStatement; " +
                "expected only the three delete( calls, since nothing else is read here",
            message,
        )
    }

    @Test
    fun `a DELETE and a FROM on two lines of a raw string are refused as a delete text`() {
        val query = "helper.readableDatabase.rawQuery(${TRIPLE}DELETE\nFROM x$TRIPLE, null)"
        val message = refused(source(inSave = query))

        // The helper indents each line of the planted text, so the space after the newline is not counted on.
        val expected = Regex(
            "the adapter deletes another way than SQLiteDatabase\\.delete: the text 'DELETE\n\\s*FROM'; " +
                "expected only the three delete\\( calls, since nothing else is read here",
        )
        assertTrue(message, expected.matches(message))
    }

    @Test
    fun `a delete call inside a local function written before it is refused, and the local function is named`() {
        val local = "run {\n    fun inner(): Int = ${delete(TRANSCRIPTIONS, quoted("id = ?"))}\n    inner()\n}"
        val message = refused(source(byId = local))

        assertTrue(message, message.contains("found a delete( call in 'inner'"))
        assertTrue(message, message.contains("one in each of deleteById, deleteCreatedBefore"))
    }

    @Test
    fun `a local function written after the delete call of its method does not take the call from it`() {
        val local = "run {\n    ${delete(TRANSCRIPTIONS, quoted("id = ?"))}\n    fun inner(): Int = 0\n}"
        val deletes = AdapterStatements.deletesIn(source(byId = local))

        assertEquals("id = ?", deletes.getValue("deleteById").where)
        assertEquals(3, deletes.size)
    }

    @Test
    fun `a method named delete is counted as a fourth delete call and refused`() {
        val message = refused(source(members = "private fun delete(id: String): Int = 0"))

        assertTrue(message, message.contains("found 4 delete( calls"))
        assertTrue(message, message.contains("deleteTombstonesRecordedBefore, delete)"))
    }
}
