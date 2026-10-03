package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SqliteHistoryStore.create` builds the store from its own clock and the database with its default name.
 *
 * No JVM test calls `create`: it needs an Android `Context`. A `create` that ignored its clock, or opened the
 * database under another name, would leave every other test green. This reads its one expression as text
 * through [AdapterTextRules.checkCreatePassesItsClock]. The second half runs the rule on made-up store files,
 * one written correctly and then reformatted, and the others each with one mistake planted.
 *
 * What the named database then is (`AppPrivateStorage.DEFAULT_DATABASE_NAME`) is pinned by the tests of that
 * object; here only that `create` passes no name of its own.
 */
class AdapterTextCreateTest {

    // ── the real store file ──────────────────────────────────────────────

    @Test
    fun `create passes its own clock to the store and opens the database with its default name`() {
        AdapterTextRules.checkCreatePassesItsClock(storeSource())
    }

    // ── made-up store files ──────────────────────────────────────────────

    @Test
    fun `a create written correctly is accepted, and so is the same code reformatted`() {
        val variants = mapOf(
            "as the store writes it" to store(),
            "with the arguments on separate lines and a trailing comma" to
                store(call = "SqliteHistoryStore(\n            SqliteHistoryDatabase(context),\n            clock,\n" +
                    "        )"),
            "with spaces inside the parentheses" to
                store(call = "SqliteHistoryStore( SqliteHistoryDatabase( context ), clock )"),
            "with a block body" to store(head = "fun create(context: Context, clock: Clock): SqliteHistoryStore {\n" +
                "            return SqliteHistoryStore(SqliteHistoryDatabase(context), clock)\n        }", call = null),
            "with other parameter names" to store(
                head = "fun create(appContext: Context, time: Clock): SqliteHistoryStore =",
                call = "SqliteHistoryStore(SqliteHistoryDatabase(appContext), time)",
            ),
            "with a comment in the call" to
                store(call = "SqliteHistoryStore(SqliteHistoryDatabase(context) /* default name */, clock)"),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkCreatePassesItsClock(source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct create was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a create that opens the database under a name of its own is refused`() {
        val refused = refusal(store(call = "SqliteHistoryStore(SqliteHistoryDatabase(context, \"history.db\"), clock)"))
        assertTrue(refused, refused.contains("expected [SqliteHistoryDatabase(context), clock]"))
    }

    @Test
    fun `a create that builds its own clock instead of passing the one it was given is refused`() {
        val refused = refusal(
            store(call = "SqliteHistoryStore(SqliteHistoryDatabase(context), Clock { System.currentTimeMillis() })"),
        )
        assertTrue(refused, refused.contains("expected [SqliteHistoryDatabase(context), clock]"))
    }

    @Test
    fun `a create that swaps the clock and the context is refused`() {
        assertTrue(refusal(store(call = "SqliteHistoryStore(SqliteHistoryDatabase(clock), context)")).isNotBlank())
    }

    @Test
    fun `a create that takes a third parameter or another type is refused`() {
        val third = refusal(
            store(head = "fun create(context: Context, clock: Clock, name: String): SqliteHistoryStore ="),
        )
        assertTrue(third, third.contains("create takes"))
        val other = refusal(store(head = "fun create(context: Context, now: Long): SqliteHistoryStore ="))
        assertTrue(other, other.contains("create takes"))
    }

    @Test
    fun `a store file with two creates, or none, is refused`() {
        val two = refusal(store() + "\nfun create(context: Context, clock: Clock) = 1\n")
        assertTrue(two, two.contains("found 2 functions named create"))
        val none = refusal("class SqliteHistoryStore {\n}\n")
        assertTrue(none, none.contains("found 0 functions named create"))
    }

    @Test
    fun `a second database built elsewhere in the store file is refused`() {
        val refused = refusal(store() + "\nprivate val extra = SqliteHistoryDatabase(context)\n")
        assertTrue(refused, refused.contains("found 2 SqliteHistoryDatabase( calls"))
    }

    @Test
    fun `the clock in a comment or a string is not the clock passed`() {
        val refused = refusal(
            store(call = "SqliteHistoryStore(SqliteHistoryDatabase(context), Clock { 0L }) // clock\n" +
                "        val note = \"SqliteHistoryStore(SqliteHistoryDatabase(context), clock)\""),
        )
        assertTrue(refused, refused.contains("create builds SqliteHistoryStore with"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun storeSource(): String {
        val files = ModuleFiles.mainSources().filter { it.name == "SqliteHistoryStore.kt" }
        check(files.size == 1) { "expected exactly one SqliteHistoryStore.kt, found ${files.size}" }
        return files.single().readText()
    }

    private fun refusal(source: String): String =
        assertThrows(AdapterShapeError::class.java) {
            AdapterTextRules.checkCreatePassesItsClock(source)
        }.message.orEmpty()

    /** A store file whose companion holds `create`, written as the real one is, with one piece replaced at a time. */
    private fun store(
        head: String = "fun create(context: Context, clock: Clock): SqliteHistoryStore =",
        call: String? = "SqliteHistoryStore(SqliteHistoryDatabase(context), clock)",
    ): String =
        "class SqliteHistoryStore internal constructor(\n" +
            "    private val database: HistoryDatabase,\n" +
            "    private val clock: Clock,\n" +
            ") : HistoryStore {\n" +
            "    override fun save(transcription: Transcription) {}\n\n" +
            "    companion object {\n" +
            "        $head${if (call == null) "" else "\n            $call"}\n" +
            "    }\n" +
            "}\n"
}
