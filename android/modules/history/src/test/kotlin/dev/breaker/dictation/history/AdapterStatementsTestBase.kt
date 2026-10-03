package dev.breaker.dictation.history

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The adapter text under test, and the small sources the refusals are run on.
 *
 * The reader has a class of its own and these are shared by both of the classes that exercise it, so
 * they are top-level and `internal` and each test class reaches them by their own names. Nothing here
 * is `public`: the module's surface does not change.
 */
    internal val adapter by lazy { AdapterStatements.adapterSource() }

internal const val TRANSCRIPTIONS = "HistorySql.TABLE_TRANSCRIPTIONS"
internal const val TRIPLE = "\"\"\""

/** The word DELETE or DROP, in any case, standing alone: `deleted_at` and `UNDELETE` do not hold it. */
internal val DELETE_OR_DROP_WORD = Regex("""(?i)\b(delete|drop)\b""")

internal const val ON_CREATE_REFUSAL = "the onCreate of the adapter must run HistorySql.CREATE_ALL through execSQL"

/** A delete call written with the literal `"id = ?"`, `null` and the table, to be hidden in a template. */
internal const val HIDDEN_DELETE = "helper.writableDatabase.delete(HistorySql.TABLE_TRANSCRIPTIONS, \"id = ?\", null)"

/** The names of the `val`s [HistorySql] declares as a `String`, found in the text of its source file. */
internal fun declaredStringConstants(): Set<String> {
    val file = ModuleFiles.mainSources().single { it.name == "HistorySql.kt" }
    return Regex("""\bval\s+(\w+)\s*:\s*String\b""")
        .findAll(AdapterStatements.withoutComments(file.readText()))
        .map { it.groupValues[1] }
        .toSet()
}

/**
 * Every text [HistorySql] holds, by member name, read by reflection.
 *
 * Kotlin compiles the properties of an object to static fields of its class (public for a `const val`,
 * private for any other `val`) and gives a `val` a getter with no parameters. Both are read, so a text is
 * reached whether it sits in a field or is computed by a getter. A list, a set, an array or a map is read
 * through to its elements, named `NAME[0]`, `NAME[1]` and so on (`NAME[key]` for a map); a getter's name
 * is written `getNAME()`.
 */
internal fun historySqlTexts(): Map<String, String> {
    val texts = LinkedHashMap<String, String>()

    fun read(name: String, value: Any?) {
        when (value) {
            is String -> texts[name] = value
            is Lazy<*> -> read(name, value.value)
            is Map<*, *> -> value.forEach { (key, element) -> read("$name[$key]", element) }
            is Iterable<*> -> value.forEachIndexed { index, element -> read("$name[$index]", element) }
            is Array<*> -> value.forEachIndexed { index, element -> read("$name[$index]", element) }
        }
    }

    val type = HistorySql::class.java
    for (field in type.declaredFields.filter { Modifier.isStatic(it.modifiers) }) {
        field.isAccessible = true
        read(field.name, field.get(null))
    }
    for (method in type.declaredMethods.filter { it.parameterCount == 0 && !it.isSynthetic }) {
        method.isAccessible = true
        read("${method.name}()", method.invoke(HistorySql))
    }
    return texts
}

internal fun scanRefused(source: String): String =
    assertThrows(AdapterShapeError::class.java) { AdapterStatements.withoutComments(source) }.message.orEmpty()

internal fun quoted(text: String) = "\"$text\""

/** A method `hide` whose body is a string that holds [expression] as a template expression. */
internal fun templated(expression: String) = "private fun hide() = \"\${$expression}\""

internal fun delete(table: String, where: String, args: String = "arrayOf<String?>(id)") =
    "database.delete($table, $where, $args)"

internal fun refused(source: String): String =
    assertThrows(AdapterShapeError::class.java) { AdapterStatements.deletesIn(source) }.message.orEmpty()

/**
 * A small adapter in the shape the reader accepts: one method per delete, the second written with an
 * expression body and a comment with commas between two arguments, as the real one is. [inSave] is
 * placed in `save` and [members] after the last method, where no delete belongs. [order] names the
 * methods in the order they are written: `save`, `byId`, `created`, `tombstones`. The `execSQL` uses the
 * reader accepts are written too: [saveStatement] is what `save` runs, [putTombstone] is the body of
 * `putTombstone`, and [onCreate] is the body of the `onCreate` of a helper class, followed by
 * [afterOnCreate] inside that class.
 */
internal fun source(
    byId: String = delete(TRANSCRIPTIONS, quoted("id = ?")),
    created: String = """
        helper.writableDatabase.delete(
            HistorySql.TABLE_TRANSCRIPTIONS,
            // The predicate is the tail of the select, taken from the same constant,
            // so the rows selected and the rows deleted, can never differ.
            HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
            arrayOf<String?>(cutoff.toString()),
        )
    """.trimIndent(),
    tombstones: String = """
        helper.writableDatabase.delete(
            HistorySql.TABLE_TOMBSTONES,
            HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
            arrayOf<String?>(cutoff.toString()),
        )
    """.trimIndent(),
    inSave: String = "",
    members: String = "",
    order: List<String> = listOf("save", "byId", "created", "tombstones"),
    saveStatement: String = "HistorySql.INSERT_OR_REPLACE",
    putTombstone: String =
        "helper.writableDatabase.execSQL(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, arrayOf<Any?>(tombstone.id))",
    onCreate: String = "HistorySql.CREATE_ALL.forEach(db::execSQL)",
    afterOnCreate: String = "",
): String {
    val methods = mapOf(
        "save" to "    override fun save(row: TranscriptionRow) {\n" +
            "        helper.writableDatabase.execSQL($saveStatement, arrayOf<Any?>(row.id))\n" +
            "${inSave.prependIndent("        ")}\n    }",
        "byId" to "    override fun deleteById(id: String): Boolean {\n" +
            "        val affected = $byId\n        return affected > 0\n    }",
        "created" to "    override fun deleteCreatedBefore(cutoff: Long): Int =\n" +
            created.prependIndent("        "),
        "tombstones" to "    override fun deleteTombstonesRecordedBefore(cutoff: Long): Int =\n" +
            tombstones.prependIndent("        "),
    )
    val put = "    override fun putTombstone(tombstone: Tombstone) {\n" +
        "${putTombstone.prependIndent("        ")}\n    }"
    val helperClass = "    private class HistoryOpenHelper(context: Context, name: String) :\n" +
        "        SQLiteOpenHelper(context, name) {\n" +
        "        override fun onCreate(db: SQLiteDatabase) {\n" +
        "${onCreate.prependIndent("            ")}\n        }\n" +
        "${afterOnCreate.prependIndent("        ")}\n    }"
    return (
        listOf("internal class Adapter : HistoryDatabase {") +
            order.map { methods.getValue(it) } +
            put +
            members.prependIndent("    ") +
            helperClass +
            "}"
        ).joinToString("\n")
}
