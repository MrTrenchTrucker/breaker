package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks the binding order is held to, and the made-up adapter they are run on.
 *
 * Each check is a function of the adapter's source text alone, so it can be run on the real adapter or
 * on a small one written for the purpose. Every one of them refuses with an [AdapterShapeError] rather
 * than passing a source it could not read, and a check that finds nothing where it expects to find
 * something is a refusal, never a pass.
 */
// ── the checks ───────────────────────────────────────────────────────

private val checks = linkedMapOf<String, (String) -> Unit>(
    "save" to ::checkSave,
    "putTombstone" to ::checkPutTombstone,
    "rowMapper" to { source: String -> checkRowMapper(source, nullable = false) },
    "nullableRowMapper" to { source: String -> checkRowMapper(source, nullable = true) },
    "newest" to ::checkNewest,
    "tombstoneReads" to ::checkTombstoneReads,
    "idRead" to ::checkIdRead,
    "countRead" to ::checkCountRead,
)

/** Each check that refuses [source], with its message; empty when every check accepts it. */
internal fun refusals(source: String): Map<String, String> = checks.mapNotNull { (name, check) ->
    try {
        check(source)
        null
    } catch (refusal: AdapterShapeError) {
        name to refusal.message.orEmpty()
    }
}.toMap()

internal fun assertRefusedBy(names: Set<String>, source: String, vararg mentions: String) {
    val refused = refusals(source)

    assertEquals("the checks that refused, with their messages: $refused", names, refused.keys)
    for (mention in mentions) {
        assertTrue("no refusal says `$mention`: $refused", refused.values.any { mention in it })
    }
}

/** `save` runs the insert, and binds the row's properties in the order of the insert's columns. */
internal fun checkSave(source: String) =
    checkBinding(source, "save", expected.saveStatement, expected.saveBindings)

/** `putTombstone` runs the tombstone insert, and binds the tombstone in the order of its columns. */
internal fun checkPutTombstone(source: String) =
    checkBinding(source, "putTombstone", expected.tombstoneStatement, expected.tombstoneBindings)

private fun checkBinding(source: String, method: String, statement: String, bindings: List<String>) {
    val arguments = AdapterStatements.callArguments(source, method, "execSQL")
    if (arguments.size != 2) {
        refuse(
            "the execSQL call in $method has ${arguments.size} arguments, expected 2 " +
                "(the statement and the array it binds); found $arguments",
        )
    }
    if (arguments[0] != statement) refuse("$method runs `${arguments[0]}`, expected $statement")
    val bound = elementsOf(method, arguments[1])
    if (bound != bindings) {
        refuse("$method binds $bound, expected $bindings, in the order of the columns of $statement")
    }
}

/** The values of the array expression [expression], in order; refuses any other shape than `arrayOf`. */
private fun elementsOf(method: String, expression: String): List<String> {
    val inner = ARRAY.matchEntire(expression)?.groupValues?.get(1)
        ?: refuse("the bound values in $method are written as `$expression`, expected arrayOf<...>(a, b, ...)")
    return pieces(inner)
}

/**
 * The properties of the row mapper that are (or are not) nullable, each read at the position of its column
 * with the getter for its type. A nullable property is read as `if (isNull(i)) null else getX(i)`, with
 * both indexes the same.
 */
internal fun checkRowMapper(source: String, nullable: Boolean) {
    val wanted = expected.rowReads.filter { it.nullable == nullable }
    if (wanted.isEmpty()) {
        refuse("TranscriptionRow has no ${if (nullable) "nullable" else "non-null"} property, nothing to check")
    }
    val arguments = AdapterStatements.callArguments(source, "toTranscriptionRow", "TranscriptionRow")
    val properties = expected.rowReads.map { it.property }
    if (arguments.size != properties.size) {
        refuse(
            "the TranscriptionRow( call in toTranscriptionRow has ${arguments.size} arguments, expected " +
                "${properties.size}, one per property $properties; found $arguments",
        )
    }
    val given = arguments.associate { argument ->
        val named = NAMED.matchEntire(argument)
            ?: refuse("toTranscriptionRow passes `$argument`, expected each property as `property = expression`")
        named.groupValues[1] to canonical(named.groupValues[2])
    }
    for (read in wanted) {
        val found = given[read.property]
            ?: refuse("toTranscriptionRow gives no value for ${read.property}; it gives ${given.keys}")
        if (found != read.text) {
            refuse("toTranscriptionRow reads ${read.property} as `$found`, expected `${read.text}`")
        }
    }
}

/** `newest` runs the newest query and maps each row through `toTranscriptionRow`, once. */
internal fun checkNewest(source: String) {
    checkQueries(source, "newest", expected.newestStatement)
    val mapped = MAPPER_CALL.findAll(AdapterStatements.bodyOf(source, "newest")).count()
    if (mapped != 1) refuse("newest maps its rows through toTranscriptionRow() $mapped times, expected once")
}

/** The query [sql] selects the row's columns in the order of the column list the mapper reads by. */
internal fun checkSelectOrder(sql: String) {
    val selected = selectColumns(sql)
    val columns = pieces(HistorySql.TRANSCRIPTION_COLUMNS)
    if (selected != columns) refuse("the query selects $selected, expected the row columns in order: $columns")
}

/**
 * `newestTombstones` reads the id, the deletion time and the reason text at the positions of their columns
 * in its query, each into the local that goes on to build the tombstone. The reason is the one column
 * stored through its text: it is read into `stored`, given to `Reason.fromStored`, and the result is what
 * the `Tombstone(` call is given.
 */
internal fun checkTombstoneReads(source: String) {
    checkQueries(source, "newestTombstones", expected.tombstonesStatement)
    val body = AdapterStatements.bodyOf(source, "newestTombstones")
    val found = LOCAL_READ.findAll(body).map { match ->
        TombstoneRead(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
    }.toList().sortedBy { it.local }
    val wanted = expected.tombstoneReads.sortedBy { it.local }
    if (found != wanted) refuse("newestTombstones reads $found, expected $wanted")

    val decoded = AdapterStatements.callArguments(source, "newestTombstones", "fromStored")
    if (decoded != listOf(expected.reasonLocal)) {
        refuse("newestTombstones gives Reason.fromStored $decoded, expected [${expected.reasonLocal}]")
    }
    val built = AdapterStatements.callArguments(source, "newestTombstones", "Tombstone")
    if (built.sorted() != expected.tombstoneArguments.sorted()) {
        refuse("newestTombstones builds its Tombstone from $built, expected ${expected.tombstoneArguments}")
    }
}

/** `idsCreatedBefore` reads the id column, and only that, from the query that selects it. */
internal fun checkIdRead(source: String) {
    checkQueries(source, "idsCreatedBefore", expected.idsStatement)
    checkReads(source, "idsCreatedBefore", expected.idRead)
}

/** `countTranscriptions` reads the one column the count selects, as the type the count is returned as. */
internal fun checkCountRead(source: String) {
    checkQueries(source, "countTranscriptions", expected.countStatement)
    checkReads(source, "countTranscriptions", expected.countRead)
}

private fun checkQueries(source: String, method: String, statement: String) {
    val arguments = AdapterStatements.callArguments(source, method, "rawQuery")
    if (arguments.firstOrNull() != statement) {
        refuse("$method queries `${arguments.firstOrNull()}`, expected $statement; found $arguments")
    }
}

private fun checkReads(source: String, method: String, wanted: String) {
    val found = CURSOR_READ.findAll(AdapterStatements.bodyOf(source, method))
        .map { "${it.groupValues[1]}(${it.groupValues[2]})" }
        .toList()
    if (found != listOf(wanted)) refuse("$method reads $found from its cursor, expected exactly [$wanted]")
}


// ── a made-up adapter, in the shape of the real one ──────────────────

/**
 * An adapter for [plan]: what [AdapterStatements] and the checks above read, in the shape of the real one.
 * A [note] is written after every comma between values, to show the checks read past comments.
 */
internal fun render(plan: Plan = expected, note: String = ""): String {
    val trail = if (note.isEmpty()) "" else " $note"
    fun items(entries: List<String>, indent: Int) =
        entries.joinToString("\n") { "${" ".repeat(indent)}$it,$trail" }
    return listOf(
        "internal class Adapter : HistoryDatabase {",
        "    override fun save(row: TranscriptionRow) {",
        "        helper.writableDatabase.execSQL(",
        "            ${plan.saveStatement},",
        "            arrayOf<Any?>(",
        items(plan.saveBindings, 16),
        "            ),",
        "        )",
        "    }",
        "",
        "    override fun newest(limit: Int): List<TranscriptionRow> =",
        "        helper.readableDatabase.rawQuery(${plan.newestStatement}, arrayOf(limit.toString()))",
        "            .use { cursor ->",
        "            buildList {",
        "                while (cursor.moveToNext()) {",
        "                    add(cursor.toTranscriptionRow())",
        "                }",
        "            }",
        "        }",
        "",
        "    override fun idsCreatedBefore(cutoff: Long): List<String> =",
        "        helper.readableDatabase",
        "            .rawQuery(${plan.idsStatement}, arrayOf(cutoff.toString()))",
        "            .use { cursor ->",
        "                buildList {",
        "                    while (cursor.moveToNext()) {",
        "                        add(cursor.${plan.idRead})",
        "                    }",
        "                }",
        "            }",
        "",
        "    override fun putTombstone(tombstone: Tombstone) {",
        "        helper.writableDatabase.execSQL(",
        "            ${plan.tombstoneStatement},",
        "            arrayOf<Any?>(",
        items(plan.tombstoneBindings, 16),
        "            ),",
        "        )",
        "    }",
        "",
        "    override fun newestTombstones(limit: Int): List<Tombstone> =",
        "        helper.readableDatabase",
        "            .rawQuery(${plan.tombstonesStatement}, arrayOf(limit.toString()))",
        "            .use { cursor ->",
        "                buildList {",
        "                    while (cursor.moveToNext()) {",
        plan.tombstoneReads.joinToString("\n") { "                        $it" },
        "                        val reason = Tombstone.Reason.fromStored(${plan.reasonLocal})",
        "                        add(Tombstone(${plan.tombstoneArguments.joinToString(", ")}))",
        "                    }",
        "                }",
        "            }",
        "",
        "    override fun countTranscriptions(): Int =",
        "        helper.readableDatabase.rawQuery(${plan.countStatement}, emptyArray()).use { cursor ->",
        "            if (cursor.moveToNext()) cursor.${plan.countRead} else 0",
        "        }",
        "}",
        "",
        "private fun android.database.Cursor.toTranscriptionRow(): TranscriptionRow = TranscriptionRow(",
        plan.rowReads.joinToString("\n") { "    ${it.property} = ${it.text},$trail" },
        ")",
    ).joinToString("\n")
}

/** The list with the items at [first] and [second] exchanged; refuses two equal items, which change nothing. */
internal fun <T> List<T>.swapped(first: Int, second: Int): List<T> {
    require(this[first] != this[second]) { "swapping two equal items plants nothing: ${this[first]}" }
    return toMutableList().also {
        it[first] = this[second]
        it[second] = this[first]
    }
}

internal fun <T> List<T>.replacedAt(position: Int, item: T): List<T> {
    require(this[position] != item) { "replacing ${this[position]} with itself plants nothing" }
    return toMutableList().also { it[position] = item }
}

/** The reads with the indexes of the properties at [first] and [second] exchanged. */
internal fun List<RowRead>.rowPositionsSwapped(first: Int, second: Int): List<RowRead> {
    require(this[first].index != this[second].index) { "the two properties are read at one index" }
    return mapIndexed { position, read ->
        when (position) {
            first -> read.copy(index = this[second].index, nullIndex = this[second].index)
            second -> read.copy(index = this[first].index, nullIndex = this[first].index)
            else -> read
        }
    }
}

internal fun List<TombstoneRead>.tombstonePositionsSwapped(first: Int, second: Int): List<TombstoneRead> {
    require(this[first].index != this[second].index) { "the two reads are at one index" }
    return mapIndexed { position, read ->
        when (position) {
            first -> read.copy(index = this[second].index)
            second -> read.copy(index = this[first].index)
            else -> read
        }
    }
}

/** The source with its first [old] replaced; refuses an edit that finds nothing to replace. */
internal fun String.replacedFirst(old: String, new: String): String {
    require(old in this) { "the planted edit finds no `$old` in the source" }
    return replaceFirst(old, new)
}
