package dev.breaker.dictation.history

/**
 * Thrown when the adapter's source is not in a shape [AdapterStatements] can vouch for.
 *
 * It is an [AssertionError], so a test that reads the adapter fails loudly when the adapter changes,
 * instead of finding nothing and passing.
 */
internal class AdapterShapeError(message: String) : AssertionError(message)

/**
 * The delete statements the phone's adapter runs, read out of the adapter's own source text.
 *
 * `SqliteHistoryDatabase` needs Android and runs in no JVM test. It builds its deletes through
 * `SQLiteDatabase.delete(table, where, args)` from expressions written in the adapter itself, so a test
 * that typed its own copy of those expressions would go on passing when the adapter's were changed. This
 * reads them instead: the table, the `WHERE` and the argument list of the one `delete(` call in each of
 * `deleteById`, `deleteCreatedBefore` and `deleteTombstonesRecordedBefore`. The JVM twin runs the table and
 * the `WHERE` it finds here, so the statements the real-SQLite tests execute are the adapter's own.
 *
 * **How it reads.** Comments are removed first ([withoutComments]), and a `//` inside a string literal is not
 * a comment. The calls and names are found on the code with the text of every string literal masked out, so
 * text inside a string is never taken for code; the expression of a `${...}` template inside a string is the
 * exception, which is code and is read as such, with any string literal inside it masked in turn. An argument
 * list is cut at its balanced closing parenthesis, and split at its top-level commas. The table and the
 * `WHERE` are then turned into values: a [HistorySql] constant this object knows takes its value from
 * [HistorySql] itself, and a plain string literal is its content. A call is attributed to the last method
 * declared before it, so a local function inside a method takes the calls written after it.
 *
 * **What it refuses.** Anything it cannot vouch for throws an [AdapterShapeError] that says what it found and
 * what it expected: a block comment, a string, a raw string or a character literal that never ends (named,
 * with the line it starts on), a number of `delete(` calls other than three (a method that is itself named
 * `delete` is counted as a call, so it is refused too), a call outside the three methods, a method with none
 * or with two, a call that does not take three arguments, a table or `WHERE` that is null, blank, built by a
 * template, a concatenation or a call, or named by a constant this object does not know, the name `delete`
 * anywhere in the code that is not followed by an opening parenthesis (a method reference such as
 * `database::delete`, or the bare name used as a value), and a delete made another way (`execSQL` with a
 * `DELETE`, `compileStatement`, `executeUpdateDelete`, the names `deleteDatabase`, `deleteFile` and
 * `deleteRecursively`, or a `HistorySql.DELETE...` constant). Every name `execSQL` in the code, a call or a
 * method reference or any other use, must be exactly one in `save`, one in `putTombstone` and one in
 * `onCreate`, where it must run `HistorySql.CREATE_ALL` and no other [HistorySql] constant; another use, in
 * another method, or a different count, is refused with the method it was found in. A `delete(` call, the
 * bare name `delete`, and the names `execSQL`, `compileStatement`, `executeUpdateDelete`, `deleteDatabase`,
 * `deleteFile` and `deleteRecursively` are found and refused inside a `${...}` template expression too.
 *
 * **What it survives.** Reformatting, wrapping or joining the arguments, comments anywhere (including
 * between arguments), a trailing comma, a method written with a block body or with an expression body,
 * methods reordered, methods added that do not delete, a local function written after the delete call of its
 * method, and the word `delete` inside a string, inside a comment or as part of a longer name.
 *
 * **What it cannot see.** It reads text, not the compiled adapter, and it does not run anything, so a call
 * made through reflection is not seen. The argument-array text is returned so a test can pin it, but what
 * `SQLiteDatabase.delete` does with the table, the `WHERE` and the arguments is not checked here. A `,` inside
 * a generic type-argument list is not tracked, so an argument written that way is split wrongly, which ends in
 * a refusal, not in a wrong value. It reads only the adapter: a `DELETE` or a `DROP` held in a [HistorySql]
 * constant and run through `execSQL` is not seen here, and the tests read every text [HistorySql] holds for
 * both words. A statement other than a delete that destroys data (an `UPDATE`, a `REPLACE`, a trigger) is not
 * looked for. Only the three names `deleteDatabase`, `deleteFile` and `deleteRecursively` are refused among the
 * other ways to remove a file, so a call such as `deleteIfExists` is not seen. A comment written inside a
 * `${...}` expression is not removed, so a call written in one is read as code. A name written between
 * backticks is not read as such, so one that holds a quote character or a `//` is misread.
 */
internal object AdapterStatements {

    /** The `delete(table, where, args)` call of one adapter method, and what its expressions come to. */
    class ShippedDelete(
        /** The adapter method the call sits in. */
        val method: String,
        /** The table, as a name. */
        val table: String,
        /** The `WHERE` clause, as text. */
        val where: String,
        /** The table expression as written in the adapter. */
        val tableSource: String,
        /** The `WHERE` expression as written in the adapter. */
        val whereSource: String,
        /** The argument-array expression as written in the adapter, whitespace collapsed. */
        val args: String,
    )

    private const val ADAPTER_FILE = "SqliteHistoryDatabase.kt"

    private val METHODS = listOf("deleteById", "deleteCreatedBefore", "deleteTombstonesRecordedBefore")

    /** The [HistorySql] constants a delete may name, with their values taken from [HistorySql], not retyped. */
    private val RESOLVABLE: Map<String, String> = mapOf(
        "TABLE_TRANSCRIPTIONS" to HistorySql.TABLE_TRANSCRIPTIONS,
        "TABLE_TOMBSTONES" to HistorySql.TABLE_TOMBSTONES,
        "CREATED_AT_STRICTLY_BEFORE_WHERE" to HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
        "DELETED_AT_STRICTLY_BEFORE_WHERE" to HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
    )

    private val CONSTANT = Regex("""HistorySql\s*\.\s*(\w+)""")
    private val DELETE_FROM_TEXT = Regex("""(?i)\bdelete\s+from\b""")
    private val OTHER_DELETE_API = Regex("""\b(compileStatement|executeUpdateDelete)\b""")
    private val DELETE_CONSTANT = Regex("""HistorySql\s*\.\s*DELETE_\w*""")
    private val DESTRUCTIVE_NAME =
        Regex("""(?<![\p{L}\p{N}_])(deleteDatabase|deleteFile|deleteRecursively)(?![\p{L}\p{N}_])""")

    /** The whole word `execSQL`, however it is used: a call, a method reference or a bare name. */
    private val EXEC_SQL_WORD = Regex("""(?<![\p{L}\p{N}_])execSQL(?![\p{L}\p{N}_])""")

    /** The whole word `delete`, not followed (after any whitespace) by an opening parenthesis. */
    private val DELETE_NOT_CALLED = Regex("""(?<![\p{L}\p{N}_])delete(?![\p{L}\p{N}_])(?!\s*\()""")

    /** The methods that may name `execSQL`, once each: the two writes, and the helper that creates the schema. */
    private val EXEC_SQL_PLACES = listOf("save", "putTombstone", "onCreate")
    private const val ON_CREATE = "onCreate"
    private const val CREATE_ALL = "CREATE_ALL"

    /** How many characters of code either side of a finding a message quotes. */
    private const val CONTEXT = 30

    /** The three deletes of the real adapter, read once. */
    val shipped: Map<String, ShippedDelete> by lazy { deletesIn(adapterSource()) }

    val deleteById: ShippedDelete get() = shipped.getValue("deleteById")

    val deleteCreatedBefore: ShippedDelete get() = shipped.getValue("deleteCreatedBefore")

    val deleteTombstonesRecordedBefore: ShippedDelete get() = shipped.getValue("deleteTombstonesRecordedBefore")

    /** The adapter's source text, as it is on disk. */
    fun adapterSource(): String {
        val files = ModuleFiles.mainSources().filter { it.name == ADAPTER_FILE }
        if (files.size != 1) {
            refuse("expected exactly one $ADAPTER_FILE under src/main/kotlin, found ${files.size}")
        }
        return files.single().readText()
    }

    /**
     * The three deletes the adapter in [source] runs, keyed by method name.
     *
     * Throws [AdapterShapeError] for anything this object cannot vouch for; see the class comment.
     */
    fun deletesIn(source: String): Map<String, ShippedDelete> {
        val lexed = lex(source)
        refuseOtherWaysToDelete(lexed)
        refuseDeleteNotCalled(lexed)
        refuseExecSqlOutsideKnownPlaces(lexed)
        val calls = callPattern("delete").findAll(lexed.masked).toList()
        val functions = functionsIn(lexed.masked)
        val owned = calls.groupBy { owner(functions, it.range.first) }
        if (calls.size != METHODS.size) {
            val where = if (calls.isEmpty()) "" else " (in ${calls.joinToString { owner(functions, it.range.first) }})"
            refuse(
                "found ${calls.size} delete( calls in the adapter$where, expected exactly ${METHODS.size}, " +
                    "one in each of ${METHODS.joinToString()}",
            )
        }
        for (method in METHODS) {
            if (functions.none { it.name == method }) refuse("the adapter declares no method named $method")
        }
        val strays = owned.keys - METHODS.toSet()
        if (strays.isNotEmpty()) {
            refuse(
                "found a delete( call in ${strays.joinToString { "'$it'" }}, expected the only delete( calls " +
                    "to be one in each of ${METHODS.joinToString()}",
            )
        }
        val wrong = METHODS.associateWith { owned[it].orEmpty().size }.filterValues { it != 1 }
        if (wrong.isNotEmpty()) {
            refuse(
                wrong.entries.joinToString { "${it.key} holds ${it.value} delete( calls" } +
                    ", expected exactly 1 in each of ${METHODS.joinToString()}",
            )
        }
        return METHODS.associateWith { method -> shippedDelete(lexed, method, owned.getValue(method).single()) }
    }

    /**
     * The argument expressions of the one [call] inside the method [method] of [source], in order, with
     * whitespace outside string literals collapsed.
     */
    fun callArguments(source: String, method: String, call: String): List<String> {
        val lexed = lex(source)
        val function = functionNamed(lexed, method)
        val inMethod = callPattern(call).findAll(lexed.masked)
            .filter { it.range.first in function.start until function.end }
            .toList()
        if (inMethod.size != 1) refuse("$method holds ${inMethod.size} $call( calls, expected exactly 1")
        return argumentsOf(lexed, inMethod.single().range.last)
    }

    /** The body of the method [method] in [source], comments removed and whitespace collapsed. */
    fun bodyOf(source: String, method: String): String {
        val lexed = lex(source)
        return tidy(lexed, bodyRange(lexed, functionNamed(lexed, method)))
    }


    /** The content of every string literal in [source] outside comments, in order. */
    fun stringLiterals(source: String): List<String> {
        val lexed = lex(source)
        return lexed.literals.filter { lexed.code[it.first] == '"' }.map { literal ->
            val quotes = if (lexed.code.startsWith("\"\"\"", literal.first) && literal.count() >= 6) 3 else 1
            lexed.code.substring(literal.first + quotes, literal.last + 1 - quotes)
        }
    }

    /**
     * [source] with its comments removed, so that a comment can neither satisfy a check on the code nor hide
     * code from one. String, raw string and character literals are copied through whole, so a comment marker
     * inside one is not a comment, and a `${...}` expression in a string is part of that string. A block
     * comment, a string, a raw string or a character literal that never ends throws [AdapterShapeError]
     * naming the construct and the line it starts on, rather than returning what was read before it.
     */
    fun withoutComments(source: String): String = lex(source).code

    /**
     * Each use of the name `delete` in the code of [source] that is not a call, as the code around it.
     *
     * The name counts when it stands alone (not inside a longer name) and is not followed, after any
     * whitespace, by an opening parenthesis: a method reference such as `database::delete`, or the bare name
     * used as a value. A declaration such as `fun delete(` has the parenthesis, so it is not reported here.
     * [deletesIn] refuses a source for which this is not empty.
     */
    fun deleteUsesNotCalled(source: String): List<String> = deleteNotCalled(lex(source))

    /**
     * How many times the name `execSQL` is used in the code of [source], by the method it sits in.
     *
     * Every use counts: a call, a method reference such as `db::execSQL`, or the bare name. A use outside any
     * method is keyed `(outside any method)`. [deletesIn] refuses a source in which this is anything other
     * than one use in each of `save`, `putTombstone` and `onCreate`.
     */
    fun execSqlUses(source: String): Map<String, Int> = execSqlUsed(lex(source))

    // ── reading the code for rules that are not about deletes ────────────

    /** One call of a name in the code of a source: the method it sits in, and the code that follows the call. */
    class CallSite(
        /** The method the call sits in, or `(outside any method)`. */
        val owner: String,
        /** The masked code after the call's closing parenthesis, to the end of the method, whitespace collapsed. */
        val after: String,
    )

    /** One whole-word use of a name in the code of a source. */
    class WordUse(
        /** The method the use sits in, or `(outside any method)`. */
        val owner: String,
        /** The masked code just before the use (40 characters), whitespace collapsed and trimmed. */
        val before: String,
    )

    /**
     * [source] with its comments removed and the text of every string and character literal replaced by `#`, so
     * that a word inside a literal is never taken for code. The expression of a `${...}` template stays code.
     * Whitespace is kept as written.
     */
    fun maskedCode(source: String): String = lex(source).masked

    /**
     * The body of the method [method] in [source], as [maskedCode] reads it, with runs of whitespace made one
     * space. Nothing in it is a comment or the text of a literal, so a rule on the code cannot be met or broken
     * by either. An expression body runs to the next method, so it may end with that method's modifiers.
     */
    fun maskedBodyOf(source: String, method: String): String {
        val lexed = lex(source)
        val range = bodyRange(lexed, functionNamed(lexed, method))
        return collapse(lexed.masked.substring(range.first, range.last + 1))
    }

    /** Every call of [name] in the code of [source], in order: the method it sits in and what follows it. */
    fun callSites(source: String, name: String): List<CallSite> {
        val lexed = lex(source)
        val functions = functionsIn(lexed.masked)
        return callPattern(name).findAll(lexed.masked).map { call ->
            val close = closing(lexed, call.range.last)
            val end = functions.firstOrNull { it.start > close }?.start ?: lexed.masked.length
            CallSite(owner(functions, call.range.first), collapse(lexed.masked.substring(close + 1, end)))
        }.toList()
    }

    /** Every use of the whole word [word] in the code of [source], in order, declarations included. */
    fun wordUses(source: String, word: String): List<WordUse> {
        val lexed = lex(source)
        val functions = functionsIn(lexed.masked)
        val pattern = Regex("""(?<![\p{L}\p{N}_])${Regex.escape(word)}(?![\p{L}\p{N}_])""")
        return pattern.findAll(lexed.masked).map { use ->
            val from = maxOf(0, use.range.first - CONTEXT - 10)
            WordUse(owner(functions, use.range.first), collapse(lexed.masked.substring(from, use.range.first)))
        }.toList()
    }

    private fun collapse(text: String): String = text.replace(Regex("""\s+"""), " ").trim()
    // ── reading the statements ───────────────────────────────────────────

    private fun shippedDelete(lexed: Lexed, method: String, call: MatchResult): ShippedDelete {
        val arguments = argumentsOf(lexed, call.range.last)
        if (arguments.size != 3) {
            refuse(
                "the delete( call in $method has ${arguments.size} arguments, expected 3 " +
                    "(table, where, args); found $arguments",
            )
        }
        val (table, where, args) = arguments
        return ShippedDelete(
            method = method,
            table = resolve("table", method, table),
            where = resolve("where", method, where),
            tableSource = table,
            whereSource = where,
            args = args,
        )
    }

    /** The value of the table or `WHERE` expression [expression], or a refusal that names it. */
    private fun resolve(part: String, method: String, expression: String): String {
        if (expression == "null") {
            refuse(
                "the $part of the delete in $method is null; SQLiteDatabase.delete documents " +
                    "that a null where deletes all rows",
            )
        }
        CONSTANT.matchEntire(expression)?.let { match ->
            val name = match.groupValues[1]
            return RESOLVABLE[name] ?: refuse(
                "the $part of the delete in $method is HistorySql.$name, which this reader does not know; " +
                    "it knows ${RESOLVABLE.keys}",
            )
        }
        if (expression.length >= 2 && expression.startsWith('"') && expression.endsWith('"')) {
            val content = expression.substring(1, expression.length - 1)
            if (content.none { it == '"' || it == '\\' || it == '$' }) {
                if (content.isBlank()) refuse("the $part of the delete in $method is the blank literal $expression")
                return content
            }
        }
        refuse(
            "cannot resolve the $part expression `$expression` of the delete in $method, expected a " +
                "HistorySql constant from ${RESOLVABLE.keys} or a plain string literal",
        )
    }

    private fun refuseOtherWaysToDelete(lexed: Lexed) {
        val found = buildList {
            DELETE_FROM_TEXT.findAll(lexed.code).forEach { add("the text '${it.value}'") }
            OTHER_DELETE_API.findAll(lexed.masked).forEach { add("a call to ${it.value}") }
            DESTRUCTIVE_NAME.findAll(lexed.masked).forEach { add("the name ${it.value}") }
            DELETE_CONSTANT.findAll(lexed.masked).forEach {
                add("the constant ${it.value.filterNot(Char::isWhitespace)}")
            }
            callPattern("execSQL").findAll(lexed.masked).forEach { call ->
                val text = lexed.code.substring(call.range.last, closing(lexed, call.range.last) + 1)
                if (Regex("""(?i)\bdelete\b""").containsMatchIn(text)) add("an execSQL call that mentions delete")
            }
        }
        if (found.isNotEmpty()) {
            refuse(
                "the adapter deletes another way than SQLiteDatabase.delete: ${found.joinToString()}; " +
                    "expected only the three delete( calls, since nothing else is read here",
            )
        }
    }

    private fun execSqlUsed(lexed: Lexed): Map<String, Int> {
        val functions = functionsIn(lexed.masked)
        return EXEC_SQL_WORD.findAll(lexed.masked).groupingBy { owner(functions, it.range.first) }.eachCount()
    }

    /**
     * A statement built from several constants and run through `execSQL` is text this reader never sees
     * whole, so the places that may run one are fixed: one use in `save`, one in `putTombstone`, and the one in
     * `onCreate`, which must run `HistorySql.CREATE_ALL` and no other [HistorySql] constant.
     */
    private fun refuseExecSqlOutsideKnownPlaces(lexed: Lexed) {
        val uses = execSqlUsed(lexed)
        if (uses != EXEC_SQL_PLACES.associateWith { 1 }) {
            val found = uses.entries.joinToString { "${it.key} (${it.value})" }.ifEmpty { "nowhere" }
            refuse(
                "found the name execSQL used in $found, expected exactly one use in each of " +
                    "${EXEC_SQL_PLACES.joinToString()} and nowhere else",
            )
        }
        val function = functionNamed(lexed, ON_CREATE)
        val range = bodyRange(lexed, function)
        val body = lexed.masked.substring(range.first, range.last + 1)
        val named = CONSTANT.findAll(body).map { it.groupValues[1] }.toSet()
        if (named != setOf(CREATE_ALL) || !EXEC_SQL_WORD.containsMatchIn(body)) {
            refuse(
                "the $ON_CREATE of the adapter must run HistorySql.$CREATE_ALL through execSQL and name no " +
                    "other HistorySql constant, found ${named.ifEmpty { setOf("none") }} in " +
                    "`${tidy(lexed, range)}`",
            )
        }
    }

    private fun refuseDeleteNotCalled(lexed: Lexed) {
        val found = deleteNotCalled(lexed)
        if (found.isNotEmpty()) {
            refuse(
                "found the name delete used other than in a call: ${found.joinToString { "`$it`" }}; " +
                    "a method reference or a bare use can run a delete this reader does not read, " +
                    "expected the name only in the three delete( calls",
            )
        }
    }

    private fun deleteNotCalled(lexed: Lexed): List<String> =
        DELETE_NOT_CALLED.findAll(lexed.masked).map { around(lexed, it.range) }.toList()

    /** The code within [CONTEXT] characters either side of [range], whitespace collapsed. */
    private fun around(lexed: Lexed, range: IntRange): String =
        tidy(lexed, maxOf(0, range.first - CONTEXT)..minOf(lexed.code.length - 1, range.last + CONTEXT))
}
