package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report hook's default is the only writer production gets, so it is pinned here as text.
 *
 * `SqliteHistoryStore.create(context, clock)` is the one way the app builds the store, and
 * [AdapterTextRules.checkCreatePassesItsClock] freezes it at exactly two arguments. That makes the
 * DEFAULT of the third constructor parameter the writer the shipped app actually runs: `list` calls
 * it for every row it cannot map. A default of `{ message -> Log.w(TAG, message) }` logs; a default of
 * `{}` swallows the bad row in silence, which is the exact failure the hook exists to prevent. No JVM
 * test can catch that — an `android.util.Log` call in a JVM unit test throws the stub, so no test
 * may take the default and read an unknown row — so the shape is pinned by reading the source.
 *
 * The rule takes the source text, so it runs on the real file and on a small made-up one. It reads
 * the code with comments removed and the text of every literal masked out ([AdapterStatements.maskedCode]),
 * so a comment or a string cannot satisfy the check or hide from it. A shape it cannot read is refused
 * with [AdapterShapeError] naming what it found and what it expected, never passed.
 *
 * **What the rule cannot see.** It reads text, not the compiled class. It does not check that the
 * tag is the right one, that `Log.w` is reached at runtime, or that the emulator log carries the
 * line: the device proof runs that end to end with a planted bad row and a `BreakerHistory` log read.
 */
class StoreReportHookGateTest {

    private val logWriter = "{ message -> Log.w(TAG, message) }"

    // ── the real store file ──────────────────────────────────────────────

    @Test
    fun `the store's report hook defaults to the android util Log writer and is not a no-op`() {
        checkReportHookIsTheLogWriter(storeSource())
    }

    @Test
    fun `create still builds the store with exactly two arguments, so the default is what runs`() {
        // The same fact AdapterTextCreateTest pins. Both gates must agree: if `create` grew a third
        // argument, the default would no longer be the production writer and this file's first test
        // would be pinning the wrong thing.
        AdapterTextRules.checkCreatePassesItsClock(storeSource())
    }

    // ── made-up store files ──────────────────────────────────────────────

    @Test
    fun `a report hook whose default is a no-op is refused, because production would stay silent`() {
        val refused = refusal(store(default = "{}"))

        assertTrue(
            "the no-op default must be refused by name: $refused",
            refused.contains("no-op"),
        )
    }

    @Test
    fun `a report hook whose default writes somewhere other than android util Log is refused`() {
        val refused = refusal(store(default = "{ message -> println(message) }"))

        assertTrue(
            "a default that does not write through Log must be refused by name: $refused",
            refused.contains("android.util.Log"),
        )
    }

    @Test
    fun `a store with no report hook at all is refused, so a silent read cannot come back`() {
        val refused = refusal(
            "class SqliteHistoryStore internal constructor(\n" +
                "    private val database: HistoryDatabase,\n" +
                "    private val clock: Clock,\n" +
                ") : HistoryStore {\n" +
                "    companion object {\n" +
                "        fun create(context: Context, clock: Clock): SqliteHistoryStore =\n" +
                "            SqliteHistoryStore(SqliteHistoryDatabase(context), clock)\n" +
                "    }\n" +
                "}\n",
        )

        assertTrue(
            "a constructor without the report hook must be refused by name: $refused",
            refused.contains("third parameter"),
        )
    }

    @Test
    fun `the same default written with the spaces spread out is still the Log writer`() {
        // Reformatting cannot change what the rule reads: each parameter is read with its
        // whitespace collapsed before it is matched, so the gate is not a brittle exact string match.
        checkReportHookIsTheLogWriter(store(default = "{  message  ->  Log.w( TAG , message )  }"))
    }

    // ── the rule ─────────────────────────────────────────────────────────

    /**
     * The third parameter of `SqliteHistoryStore`'s constructor is `private val report`, its default is
     * a lambda, and that lambda writes through `Log.w`. Refuses on any other shape.
     */
    private fun checkReportHookIsTheLogWriter(source: String) {
        val code = AdapterStatements.maskedCode(source)
        val header = Regex("""(?m)^class SqliteHistoryStore internal constructor\(""")
        val declarations = header.findAll(code).count()
        if (declarations != 1) {
            refuse("found $declarations `class SqliteHistoryStore internal constructor(` declarations, expected exactly 1")
        }
        val open = code.indexOf("internal constructor(") + "internal constructor(".length - 1
        val parameters = parametersOf(code, open)
        if (parameters.size != 3) {
            refuse(
                "the constructor takes ${parameters.size} parameters, expected 3 (database, clock, " +
                    "report); the report hook is the third parameter, and a store without it is a " +
                    "store that swallows the row it cannot map",
            )
        }
        val report = parameters[2].let { collapse(it) }
        if (!Regex("""^private\s+val\s+report\s*:\s*\(String\)\s*->\s*Unit\s*=\s*(.+)$""").matches(report)) {
            refuse(
                "the third parameter is `$report`, expected " +
                    "`private val report: (String) -> Unit = <a lambda>`",
            )
        }
        val default = collapse(report.substringAfterLast('='))
        if (default == "{}") {
            refuse(
                "the report hook's default is a no-op `$default`: production builds the store through " +
                    "`create`, which takes two arguments, so the default is the only writer that runs, " +
                    "and a no-op default means a row the store cannot map is dropped in silence",
            )
        }
        if (!default.contains("Log.w(")) {
            refuse(
                "the report hook's default `$default` does not write through android.util.Log: expected " +
                    "`Log.w(TAG, message)`, so the bad row reaches the log and the tag the proof reads",
            )
        }
    }

    /** The parameters of the constructor whose `(` is at [open], split at their top-level commas. */
    private fun parametersOf(code: String, open: Int): List<String> {
        var depth = 0
        val parameters = mutableListOf<String>()
        var current = StringBuilder()
        for (index in open until code.length) {
            when (val char = code[index]) {
                '(', '{', '[' -> {
                    depth++
                    if (depth > 1) current.append(char)
                }
                ')', '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        parameters.add(current.toString())
                        return parameters.map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    current.append(char)
                }
                ',' -> if (depth == 1) {
                    parameters.add(current.toString())
                    current = StringBuilder()
                } else {
                    current.append(char)
                }
                else -> if (depth >= 1) current.append(char)
            }
        }
        refuse("the constructor's parameter list is never closed")
    }

    private fun collapse(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    private fun refuse(message: String): Nothing = throw AdapterShapeError(message)

    // ── helpers ──────────────────────────────────────────────────────────

    private fun storeSource(): String {
        val files = ModuleFiles.mainSources().filter { it.name == "SqliteHistoryStore.kt" }
        check(files.size == 1) { "expected exactly one SqliteHistoryStore.kt, found ${files.size}" }
        return files.single().readText()
    }

    private fun refusal(source: String): String =
        assertThrows(AdapterShapeError::class.java) {
            checkReportHookIsTheLogWriter(source)
        }.message.orEmpty()

    /** A store file written as the real one is, with the report hook's default replaced at will. */
    private fun store(default: String = logWriter): String =
        "class SqliteHistoryStore internal constructor(\n" +
            "    private val database: HistoryDatabase,\n" +
            "    private val clock: Clock,\n" +
            "    private val report: (String) -> Unit = $default,\n" +
            ") : HistoryStore {\n" +
            "    companion object {\n" +
            "        fun create(context: Context, clock: Clock): SqliteHistoryStore =\n" +
            "            SqliteHistoryStore(SqliteHistoryDatabase(context), clock)\n" +
            "    }\n" +
            "}\n"
}
