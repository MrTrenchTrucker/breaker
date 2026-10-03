package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal val expected by lazy { expectedPlan() }

// ── what a correct adapter does, from the module's own statements and types ──

/** One property of the row mapper: the getter and index it is read with. */
internal data class RowRead(
    val property: String,
    val getter: String,
    val index: Int,
    val nullable: Boolean,
    val nullIndex: Int = index,
) {
    /** The expression the mapper gives for the property. */
    val text: String
        get() = if (nullable) "if (isNull($nullIndex)) null else $getter($index)" else "$getter($index)"
}

/** One read of the tombstone query, into the local named [local]. */
internal data class TombstoneRead(val local: String, val getter: String, val index: Int) {
    override fun toString() = "val $local = cursor.$getter($index)"
}

/** A constructor parameter as its source declares it. */
internal class Parameter(val name: String, val type: String, val nullable: Boolean)

/** What an adapter written correctly does, and the statements it names. */
internal data class Plan(
    val saveStatement: String,
    val saveBindings: List<String>,
    val tombstoneStatement: String,
    val tombstoneBindings: List<String>,
    val newestStatement: String,
    val rowReads: List<RowRead>,
    val idsStatement: String,
    val idRead: String,
    val tombstonesStatement: String,
    val tombstoneReads: List<TombstoneRead>,
    val reasonLocal: String,
    val tombstoneProperties: List<String>,
    val tombstoneArguments: List<String>,
    val countStatement: String,
    val countRead: String,
)

private fun expectedPlan(): Plan {
    val row = constructorOf("TranscriptionRow")
    val tombstone = constructorOf("Tombstone")
    val reasonType = Tombstone.Reason::class.java.simpleName
    val storedType = Tombstone.Reason::class.java.getMethod("getStored").returnType.simpleName
    val rowColumns = pieces(HistorySql.TRANSCRIPTION_COLUMNS)
    val tombstoneColumns = selectColumns(HistorySql.SELECT_NEWEST_TOMBSTONES)
    val countColumns = selectColumns(HistorySql.COUNT_TRANSCRIPTIONS)
    val idIndex = selectColumns(HistorySql.SELECT_IDS_CREATED_BEFORE).indexOf("id")
    if (idIndex < 0) refuse("SELECT_IDS_CREATED_BEFORE does not select the id column")
    if (countColumns.size != 1) refuse("COUNT_TRANSCRIPTIONS selects $countColumns, expected one column")
    val countType = when (val returned = HistoryDatabase::class.java.getMethod("countTranscriptions").returnType) {
        Int::class.javaPrimitiveType -> "Int"
        Long::class.javaPrimitiveType -> "Long"
        else -> refuse("countTranscriptions returns ${returned.name}, which has no cursor getter here")
    }
    sameProperties("TRANSCRIPTION_COLUMNS", rowColumns, row)
    sameProperties("SELECT_NEWEST_TOMBSTONES", tombstoneColumns, tombstone)
    return Plan(
        saveStatement = "HistorySql.INSERT_OR_REPLACE",
        saveBindings = bindings("row", row, insertColumns(HistorySql.INSERT_OR_REPLACE), reasonType),
        tombstoneStatement = "HistorySql.INSERT_OR_REPLACE_TOMBSTONE",
        tombstoneBindings = bindings(
            "tombstone",
            tombstone,
            insertColumns(HistorySql.INSERT_OR_REPLACE_TOMBSTONE),
            reasonType,
        ),
        newestStatement = "HistorySql.SELECT_NEWEST",
        rowReads = row.map { parameter ->
            val index = rowColumns.indexOfFirst { camel(it) == parameter.name }
            RowRead(parameter.name, getterFor(parameter.type), index, parameter.nullable)
        },
        idsStatement = "HistorySql.SELECT_IDS_CREATED_BEFORE",
        idRead = "${getterFor(propertyOf(row, "id").type)}($idIndex)",
        tombstonesStatement = "HistorySql.SELECT_NEWEST_TOMBSTONES",
        // The reason is the one column stored through its text: the adapter reads it into `stored`.
        tombstoneReads = tombstoneColumns.mapIndexed { index, column ->
            val parameter = propertyOf(tombstone, column)
            if (parameter.type == reasonType) {
                TombstoneRead(STORED_LOCAL, getterFor(storedType), index)
            } else {
                TombstoneRead(parameter.name, getterFor(parameter.type), index)
            }
        },
        reasonLocal = STORED_LOCAL,
        tombstoneProperties = tombstone.map { it.name },
        tombstoneArguments = tombstone.map { "${it.name} = ${it.name}" },
        countStatement = "HistorySql.COUNT_TRANSCRIPTIONS",
        countRead = "${getterFor(countType)}(0)",
    )
}

/**
 * The values an insert binds for [columns], in order. A column is the property named for it, in camel case,
 * under [receiver]: `duration_ms` is `row.durationMs`. A property of the reason type is the one stored
 * through its text, so it is bound as `.stored`.
 */
private fun bindings(
    receiver: String,
    parameters: List<Parameter>,
    columns: List<String>,
    reasonType: String,
): List<String> {
    sameProperties("the insert", columns, parameters)
    return columns.map { column ->
        val parameter = propertyOf(parameters, column)
        if (parameter.type == reasonType) "$receiver.${parameter.name}.stored" else "$receiver.${parameter.name}"
    }
}

private fun sameProperties(what: String, columns: List<String>, parameters: List<Parameter>) {
    val named = columns.map(::camel)
    if (named.sorted() != parameters.map { it.name }.sorted()) {
        refuse("$what has the columns $columns, which are not the properties ${parameters.map { it.name }}")
    }
}

private fun propertyOf(parameters: List<Parameter>, column: String): Parameter =
    parameters.singleOrNull { it.name == camel(column) }
        ?: refuse("no one property named ${camel(column)} for the column $column in ${parameters.map { it.name }}")

/** The `Cursor` getter for a value of Kotlin type [type]. */
private fun getterFor(type: String): String = when (type) {
    "String" -> "getString"
    "Long" -> "getLong"
    "Int" -> "getInt"
    else -> refuse("no cursor getter is known for the type $type")
}

/** `duration_ms` as `durationMs`. */
private fun camel(column: String): String = column.split('_').mapIndexed { index, part ->
    if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() }
}.joinToString("")

/** The primary constructor of [className], read from its source under `src/main/kotlin`. */
private fun constructorOf(className: String): List<Parameter> {
    val files = ModuleFiles.mainSources().filter { it.name == "$className.kt" }
    if (files.size != 1) refuse("expected exactly one $className.kt under src/main/kotlin, found ${files.size}")
    val text = files.single().readText()
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("""//[^\n]*"""), "")
    val header = Regex("""\bclass\s+$className\s*\(""").find(text)
        ?: refuse("$className.kt declares no class $className with a constructor")
    val open = header.range.last
    var depth = 0
    var close = -1
    for (index in open until text.length) {
        when (text[index]) {
            '(' -> depth++
            ')' -> if (--depth == 0) {
                close = index
                break
            }
        }
    }
    if (close < 0) refuse("the constructor of $className in $className.kt is never closed")
    return pieces(text.substring(open + 1, close)).map { piece ->
        val match = PARAMETER.matchEntire(piece)
            ?: refuse("cannot read the constructor parameter `$piece` of $className")
        Parameter(match.groupValues[1], match.groupValues[2], match.groupValues[3].isNotEmpty())
    }
}

// ── reading statements and expressions ───────────────────────────────

/** The columns named by the column list of the `INSERT` [sql], checked to have one placeholder each. */
internal fun insertColumns(sql: String): List<String> {
    val match = INSERT.matchEntire(sql)
        ?: refuse(
            "cannot read a column list out of `${flat(sql)}`, " +
                "expected INSERT OR REPLACE INTO t (a, b) VALUES (?, ?)",
        )
    val columns = pieces(match.groupValues[1])
    val placeholders = pieces(match.groupValues[2])
    if (columns.isEmpty() || placeholders.any { it != "?" } || placeholders.size != columns.size) {
        refuse("`${flat(sql)}` has ${columns.size} columns and the placeholders $placeholders, expected one ? each")
    }
    return columns
}

/** The columns named by the select list of the `SELECT` [sql]. */
internal fun selectColumns(sql: String): List<String> {
    val match = SELECT.matchEntire(sql)
        ?: refuse("cannot read a select list out of `${flat(sql)}`, expected SELECT a, b FROM t")
    return pieces(match.groupValues[1]).also { columns ->
        if (columns.isEmpty() || columns.any { it.isEmpty() }) refuse("the select list of `${flat(sql)}` is empty")
    }
}

private fun flat(sql: String): String = sql.replace(Regex("""\s+"""), " ").trim()

/** The pieces of [text] between its top-level commas, trimmed; a trailing comma leaves no empty piece. */
internal fun pieces(text: String): List<String> {
    val parts = ArrayList<String>()
    var depth = 0
    var from = 0
    for (index in text.indices) {
        when (text[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) {
                parts.add(text.substring(from, index).trim())
                from = index + 1
            }
        }
    }
    parts.add(text.substring(from).trim())
    if (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.lastIndex)
    return if (parts == listOf("")) emptyList() else parts
}

/** The read in [expression] as `getX(i)` or `if (isNull(i)) null else getX(i)`; anything else as it is. */
internal fun canonical(expression: String): String {
    PLAIN_READ.matchEntire(expression)?.let { return "${it.groupValues[1]}(${it.groupValues[2]})" }
    NULLABLE_READ.matchEntire(expression)?.let {
        return "if (isNull(${it.groupValues[1]})) null else ${it.groupValues[2]}(${it.groupValues[3]})"
    }
    return expression
}

/** The local the adapter reads the reason's stored text into, and hands to `Reason.fromStored`. */
private const val STORED_LOCAL = "stored"

private val PARAMETER = Regex("""(?:val|var)\s+(\w+)\s*:\s*([\w.]+)(\??)\s*(?:=.*)?""", RegexOption.DOT_MATCHES_ALL)
private val INSERT = Regex(
    """\s*insert\s+or\s+replace\s+into\s+\w+\s*\(([^)]*)\)\s*values\s*\(([^)]*)\)\s*""",
    RegexOption.IGNORE_CASE,
)
private val SELECT = Regex(
    """\s*select\s+(.*?)\s+from\s.*""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
internal val ARRAY = Regex("""arrayOf\s*(?:<[^>]*>)?\s*\((.*)\)""", RegexOption.DOT_MATCHES_ALL)
internal val NAMED = Regex("""(\w+)\s*=\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
private val PLAIN_READ = Regex("""(?:this\s*\.\s*)?(get[A-Z]\w*)\s*\(\s*(\d+)\s*\)""")
private val NULLABLE_READ = Regex(
    """if\s*\(\s*(?:this\s*\.\s*)?isNull\s*\(\s*(\d+)\s*\)\s*\)\s*null\s+else\s+""" +
        """(?:this\s*\.\s*)?(get[A-Z]\w*)\s*\(\s*(\d+)\s*\)""",
)
internal val LOCAL_READ = Regex("""\bval\s+(\w+)\s*=\s*(?:\w+\s*\.\s*)?(get[A-Z]\w*)\s*\(\s*(\d+)\s*\)""")
internal val CURSOR_READ = Regex("""\b(get[A-Z]\w*)\s*\(\s*(\d+)\s*\)""")
internal val MAPPER_CALL = Regex("""\.\s*toTranscriptionRow\s*\(\s*\)""")
