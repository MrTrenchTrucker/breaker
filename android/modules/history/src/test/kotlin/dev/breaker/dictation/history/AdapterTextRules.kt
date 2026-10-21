package dev.breaker.dictation.history

/**
 * Rules on how the phone's adapter behaves, checked on its source text.
 *
 * `SqliteHistoryDatabase` needs Android and runs in no JVM test, so what it does with a transaction, with a
 * cursor and with an unknown stored reason is pinned here by reading its code through [AdapterStatements]
 * (comments removed, literals masked). `SqliteHistoryStore.create` is not run by any test either, so its one
 * expression is read the same way.
 *
 * Every rule takes the source text, so a test can run it on the real file and on a small made-up one. A rule
 * that does not recognise the shape it is given throws [AdapterShapeError] and says what it found and what it
 * expected: it never passes on a shape it cannot read.
 *
 * **What the rules cannot see.** They read text. A rule is about the shape written, not about what the platform
 * then does with it: that `endTransaction()` without `setTransactionSuccessful()` rolls back, and that `use`
 * closes the cursor, are facts about Android and Kotlin that these rules rely on, not test. A correct rewrite
 * in another shape (a helper function, a `?:` instead of an `if`) is refused, not passed, and the refusal is
 * the prompt to extend the rule.
 */
internal object AdapterTextRules {

    private const val TRANSACTION = "transaction"
    private const val BEGIN = "beginTransaction"
    private const val SUCCESS = "setTransactionSuccessful"
    private const val END = "endTransaction"

    /** The methods that read through `rawQuery`; the rule is refused if one of them is gone. */
    private val QUERY_METHODS = listOf("newest", "idsCreatedBefore", "newestTombstones", "countTranscriptions")

    private val TRY = Regex("""(?<![\p{L}\p{N}_])try(?![\p{L}\p{N}_])""")
    private val CATCH = Regex("""(?<![\p{L}\p{N}_])catch(?![\p{L}\p{N}_])""")
    private val FINALLY = Regex("""^finally\s*\{""")
    private val USE_AFTER = Regex("""^\.\s*use\s*\{""")
    private val BLOCK_CALL = Regex("""(?<![\p{L}\p{N}_.])block\s*\(""")
    private val FROM_STORED = Regex("""(?<![\p{L}\p{N}_])fromStored\s*\(""")
    private val THROWING = Regex("""(?<![\p{L}\p{N}_])throw(?![\p{L}\p{N}_])""")
    /**
     * A call that fails the read. The five are the same idiom under different names: `error(` aborts, `check(` and
     * `require(` assert and abort, and `checkNotNull(` / `requireNotNull(` are their null forms. Each is written
     * out rather than folded into the alternatives of a single name, so that `check(` still matches a bare `check(`
     * and `checkNotNull(` is not double-counted by a `check`-prefixed name.
     */
    private val REFUSING_CALLS =
        Regex("""(?<![\p{L}\p{N}_])(?:error|check|require|checkNotNull|requireNotNull)\s*\(""")
    /**
     * The not-null assertion operator, the fail-the-read idiom in operator form. `!!` is a throw when it meets a
     * null, so a body that asserts on the stored word fails the read the same way `check(` does. `!=` is a
     * single `!` followed by `=`, so it can never hold the two-character `!!` and needs no exclusion; a `!!`
     * inside a string literal is `#` by the time this sees it, so only the operator is matched.
     */
    private val NOT_NULL_ASSERTION = Regex("""!!""")
    private val MAP_NOT_NULL = Regex("""(?<![\p{L}\p{N}_])mapNotNull(?![\p{L}\p{N}_])""")
    private val REPORT_MESSAGE = Regex("""report\s*\(\s*"([^"]*)"\s*\)""")
    private val SKIPS_AFTER_REPORT = Regex("""report\s*\(\s*"[^"]*"\s*\)\s*null(?![\p{L}\p{N}_])""")
    private val DELETES = Regex("""(?i)\bdelete\w*\s*\(""")

    /** The two things the report for an unmappable tombstone must name: the row id and the stored word. */
    private const val ROW_ID = "row.id"
    private const val ROW_REASON = "row.reason"

    // ── transaction ──────────────────────────────────────────────────────

    /**
     * In `transaction`: `beginTransaction()` comes before the `try`; the `try` runs `block()` and then marks
     * the transaction successful, once, with no call after it; `endTransaction()` is once, in the `finally`;
     * there is no `catch`; and the three calls are on the same receiver.
     *
     * Without the begin nothing is a transaction, without the success mark every block is rolled back, a
     * success mark in a `catch` (or anywhere a failure reaches) commits a half-done delete, and an end outside
     * the `finally` leaves the transaction open when the block throws.
     */
    fun checkTransaction(source: String) {
        val body = AdapterStatements.maskedBodyOf(source, TRANSACTION)
        val begin = onlyOne(body, callOf(BEGIN), "$BEGIN() calls")
        val tryWord = onlyOne(body, TRY, "try blocks")
        if (begin.range.first > tryWord.range.first) {
            refuse("$BEGIN() is after the try, expected it before: a failure in the begin must not reach the finally")
        }
        if (CATCH.containsMatchIn(body)) {
            refuse("$TRANSACTION has a catch, expected none: a failure must reach the caller with no success mark")
        }
        val tryBlock = blockAfter(body, tryWord.range.last + 1, "the try")
        val afterTry = body.substring(tryBlock.last + 1).trimStart()
        if (!FINALLY.containsMatchIn(afterTry)) {
            refuse("the try of $TRANSACTION is not followed by a finally, found `${afterTry.take(40)}`")
        }
        val finallyStart = body.length - afterTry.length
        val finallyBlock = blockAfter(body, finallyStart + "finally".length, "the finally")

        val success = onlyOne(body, callOf(SUCCESS), "$SUCCESS() calls")
        if (success.range.first !in tryBlock) refuse("$SUCCESS() is outside the try, expected it inside")
        val end = onlyOne(body, callOf(END), "$END() calls")
        if (end.range.first !in finallyBlock) refuse("$END() is outside the finally, expected it inside")

        val beforeSuccess = body.substring(tryBlock.first, success.range.first)
        if (!BLOCK_CALL.containsMatchIn(beforeSuccess)) {
            refuse("block() is not called in the try before $SUCCESS(), expected the work first, then the mark")
        }
        val afterSuccess = body.substring(success.range.last + 1, tryBlock.last + 1)
        if (afterSuccess.contains('(')) {
            refuse("a call follows $SUCCESS() in the try: `${afterSuccess.trim()}`, expected it to be the last call")
        }
        val receivers = Regex("""(\w+)\s*\.\s*($BEGIN|$SUCCESS|$END)\s*\(""").findAll(body)
            .map { it.groupValues[1] }.toSet()
        if (receivers.size != 1) {
            refuse("$BEGIN, $SUCCESS and $END are called on $receivers, expected one receiver for all three")
        }
    }

    // ── cursors ──────────────────────────────────────────────────────────

    /**
     * Every `rawQuery(...)` in the adapter is followed directly by `.use {`, so the cursor is closed when the
     * block ends, however it ends. The four reading methods must each hold one, so a scan of nothing is refused.
     */
    fun checkCursorsClosed(source: String) {
        val sites = AdapterStatements.callSites(source, "rawQuery")
        val missing = QUERY_METHODS - sites.map { it.owner }.toSet()
        if (missing.isNotEmpty()) {
            refuse("found no rawQuery call in $missing, expected one in each of $QUERY_METHODS")
        }
        val open = sites.filterNot { USE_AFTER.containsMatchIn(it.after) }
        if (open.isNotEmpty()) {
            refuse(
                "a rawQuery result is not consumed by .use { } in " +
                    open.joinToString { "${it.owner} (followed by `${it.after.take(30)}`)" } +
                    ", expected `.use {` right after the call, so the cursor is closed",
            )
        }
    }

    // ── an unknown stored reason ─────────────────────────────────────────

    /**
     * An unknown stored tombstone reason is tolerated, never thrown, and it is the store that
     * reports it.
     *
     * In the three databases' `newestTombstones` the stored reason is handed back as the word on
     * the disk — a `Tombstone.StoredRow`, not a `Tombstone` — and no one of them throws, refuses a
     * row with `error`, `check`, `require`, `checkNotNull` or `requireNotNull`, asserts on it with
     * `!!`, or calls `fromStored`: a row this module cannot read must not stop the tombstones behind
     * it, and the mapping is the store's, which is where an unmappable row is skipped and reported.
     *
     * In `SqliteHistoryStore.pendingTombstones` the stored row is mapped with the tolerant
     * sibling, and an unmappable one is skipped (the branch yields `null`), reported through the
     * report hook, and left on file: nothing here deletes it.
     */
    fun checkUnknownReasonThrows(
        adapterSource: String,
        jdbcSource: String,
        inMemorySource: String,
        storeSource: String,
    ) {
        for ((label, source) in listOf(
            "SqliteHistoryDatabase" to adapterSource,
            "JdbcHistoryDatabase" to jdbcSource,
            "InMemoryHistoryDatabase" to inMemorySource,
        )) {
            val body = AdapterStatements.maskedBodyOf(source, "newestTombstones")
            if (THROWING.containsMatchIn(body)) {
                refuse(
                    "newestTombstones in $label still throws, found `$body`: the stored reason is handed back as " +
                        "the word on the disk, and a row this module cannot read is the store's to skip and report, " +
                        "not the read's to fail — not with MappingFailure, and not with any other failure",
                )
            }
            if (REFUSING_CALLS.containsMatchIn(body)) {
                refuse(
                    "newestTombstones in $label refuses a stored row with error(, check(, require(, " +
                        "checkNotNull( or requireNotNull(, found `$body`: the stored reason is handed back as the " +
                        "word on the disk, and a row this module cannot read is the store's to skip and report, " +
                        "not the read's to fail",
                )
            }
            if (FROM_STORED.containsMatchIn(body)) {
                refuse(
                    "newestTombstones in $label still calls fromStored, found `$body`: the mapping belongs " +
                        "to the store, which is where an unmappable row is skipped and reported",
                )
            }
            // Last: a body that both maps and asserts reports the mapping, which is the deeper defect, so the
            // message a body that maps already gives is not displaced by the operator form of the fail.
            if (NOT_NULL_ASSERTION.containsMatchIn(body)) {
                refuse(
                    "newestTombstones in $label fails the read with the not-null operator !!, found `$body`: " +
                        "the stored reason is handed back as the word on the disk, and a row this module cannot " +
                        "read is the store's to skip and report, not the read's to fail",
                )
            }
        }
        checkPendingTombstones(storeSource)
    }

    /** `pendingTombstones` skips an unmappable stored row, reports it, and deletes nothing. */
    private fun checkPendingTombstones(storeSource: String) {
        val body = AdapterStatements.maskedBodyOf(storeSource, "pendingTombstones")
        if (!MAP_NOT_NULL.containsMatchIn(body)) {
            refuse(
                "pendingTombstones does not map its stored rows with mapNotNull, found `$body`: an " +
                    "unmappable row is to be skipped, not to stop the tombstones behind it",
            )
        }
        if (THROWING.containsMatchIn(body)) {
            refuse(
                "pendingTombstones throws MappingFailure, found `$body`: a row this module cannot read is " +
                    "skipped and reported, never thrown",
            )
        }
        val report = REPORT_MESSAGE.find(body)
            ?: refuse(
                "pendingTombstones does not report an unmappable row through the report hook, found " +
                    "`$body`: a skipped row nobody hears about is a delete the server never learns of",
            )
        val message = report.groupValues[1]
        if (!message.contains(ROW_ID) || !message.contains(ROW_REASON)) {
            refuse(
                "the report for an unmappable tombstone is `$message`, expected the row id and the stored " +
                    "reason, and nothing else",
            )
        }
        if (!SKIPS_AFTER_REPORT.containsMatchIn(body)) {
            refuse(
                "pendingTombstones reports an unmappable row but does not skip it, found `$body`: the " +
                    "branch must yield null so the row is left out, not handed on",
            )
        }
        if (DELETES.containsMatchIn(body)) {
            refuse(
                "pendingTombstones deletes, found `$body`: an unmappable tombstone stays stored and goes " +
                    "only when the age sweep takes it",
            )
        }
    }

    // ── the count ────────────────────────────────────────────────────────

    /**
     * In `countTranscriptions`, a cursor with no row gives 0: `if (c.moveToNext()) c.getInt(0) else 0`, with or
     * without braces round the two branches.
     */
    fun checkCountFallsBackToZero(source: String) {
        val body = AdapterStatements.maskedBodyOf(source, "countTranscriptions")
        val read = """\1\s*\.\s*getInt\s*\(\s*0\s*\)"""
        val shape = Regex(
            """\bif\s*\(\s*(\w+)\s*\.\s*moveToNext\s*\(\s*\)\s*\)\s*(?:\{\s*$read\s*\}|$read)""" +
                """\s*else\s*(?:\{\s*0\s*\}|0(?![\p{L}\p{N}_.]))""",
        )
        if (!shape.containsMatchIn(body)) {
            refuse("countTranscriptions is not `if (c.moveToNext()) c.getInt(0) else 0`, found `$body`")
        }
    }

    // ── create() ─────────────────────────────────────────────────────────

    /**
     * `SqliteHistoryStore.create(context, clock)` builds `SqliteHistoryStore(SqliteHistoryDatabase(context), clock)`:
     * its own two parameters, the database opened with the default name, and no other clock.
     */
    fun checkCreatePassesItsClock(storeSource: String) {
        val code = AdapterStatements.maskedCode(storeSource)
        val declarations = Regex("""\bfun\s+create\s*\(([^)]*)\)""").findAll(code).toList()
        if (declarations.size != 1) refuse("found ${declarations.size} functions named create, expected exactly 1")
        val parameters = declarations.single().groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { parameter -> parameter.split(':').map { it.trim() } }
        val typed = parameters.size == 2 && parameters.all { it.size == 2 }
        if (!typed || parameters.map { it[1] } != listOf("Context", "Clock")) {
            refuse("create takes $parameters, expected (name: Context, name: Clock)")
        }
        val (context, clock) = parameters.map { it[0] }
        val arguments = AdapterStatements.callArguments(storeSource, "create", "SqliteHistoryStore")
            .map { argument -> argument.filterNot(Char::isWhitespace) }
        val expected = listOf("SqliteHistoryDatabase($context)", clock)
        if (arguments != expected) {
            refuse(
                "create builds SqliteHistoryStore with $arguments, expected $expected: " +
                    "its own clock, and the database with the default name",
            )
        }
        val databases = Regex("""(?<![\p{L}\p{N}_])SqliteHistoryDatabase\s*\(""").findAll(code).count()
        if (databases != 1) {
            refuse("found $databases SqliteHistoryDatabase( calls in the store file, expected exactly 1")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun callOf(name: String) = Regex("""(?<![\p{L}\p{N}_])$name\s*\(""")

    private fun onlyOne(body: String, pattern: Regex, what: String): MatchResult {
        val found = pattern.findAll(body).toList()
        if (found.size != 1) refuse("$TRANSACTION holds ${found.size} $what, expected exactly 1")
        return found.single()
    }

    /** The range between the braces of the block whose `{` is at or after [from], with only spaces before it. */
    private fun blockAfter(text: String, from: Int, what: String): IntRange {
        val open = text.indexOf('{', from)
        if (open < 0 || text.substring(from, open).isNotBlank()) {
            val found = text.substring(from, minOf(text.length, from + 30)).trim()
            refuse("expected `{` right after $what, found `$found`")
        }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return (open + 1)..index
            }
        }
        refuse("the block of $what is never closed")
    }

    private fun refuse(message: String): Nothing = throw AdapterShapeError(message)
}
