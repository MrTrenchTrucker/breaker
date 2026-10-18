package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Home entry hands the caller's onTap to the view it builds.
 *
 * The entry takes onTap as its last parameter and passes it on to the host view in the
 * same place. Each rule reads the entry source with comments and literal text removed,
 * and is shown to fire on an edited copy of that source before it is held on the real one.
 */

private const val ENTRY_FILE = "HomeEntry.kt"

private val WHITESPACE_RUNS = Regex("""\s+""")

/** The named problems with the entry in [text]; an empty list means the entry meets both rules. */
private fun entryProblemsIn(text: String): List<String> {
    val flat = withoutCommentsAndStrings(text).replace(WHITESPACE_RUNS, "")
    val problems = mutableListOf<String>()
    if (!flat.contains("onSettings:()->Unit,onTap:()->Unit,")) problems += "the entry does not take onTap"
    if (!flat.contains("onSettings,onTap,theme,")) problems += "the entry does not pass onTap to the view"
    return problems
}

/** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
private fun replaceOnce(text: String, old: String, new: String): String {
    assertTrue(
        "ui: home entry: the text to change is not in the source exactly once: $old",
        Regex(Regex.escape(old)).findAll(text).count() == 1,
    )
    return text.replace(old, new)
}

private fun assertFires(what: String, problems: List<String>, part: String) =
    assertTrue("ui: home entry: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

/** The Home entry's onTap, pinned as text. */
class HomeEntryTapGateTest {
    private val entry = mainSourceOf(ENTRY_FILE)

    @Test
    fun `the real Home entry passes the caller's onTap to the view`() {
        assertEquals("ui: home entry: the real entry: ", emptyList<String>(), entryProblemsIn(entry))
    }

    @Test
    fun `an entry that passes a no-op lambda in place of onTap is reported`() {
        val edited = replaceOnce(entry, "        onTap,\n        theme,", "        {},\n        theme,")
        assertFires("the no-op lambda", entryProblemsIn(edited), "does not pass onTap to the view")
        val split = replaceOnce(entry, "        onTap,\n        theme,", "        onTap,\n\n        theme,")
        assertEquals("ui: home entry: the split form: ", emptyList<String>(), entryProblemsIn(split))
    }

    @Test
    fun `an entry that drops the onTap parameter is reported`() {
        val edited = replaceOnce(entry, "    onTap: () -> Unit,\n", "")
        assertFires("the dropped parameter", entryProblemsIn(edited), "does not take onTap")
    }
}
