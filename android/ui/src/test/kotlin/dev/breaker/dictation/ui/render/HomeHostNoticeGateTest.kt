package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Home view draws the notice that the handler holds.
 *
 * The notice is the answer to a HOW IT WORKS or ABOUT tap. The handler keeps it, and
 * the view builds the state it draws from the app's facts and that notice. A view that
 * drew a fixed value in its place would show the user nothing while every other test
 * stayed green, so the argument that sets the notice is read off merged() here. Comments
 * and literal text are removed first, and only the top level arguments of the call count,
 * each with all whitespace removed, so where the notice comes from is what counts and
 * not how the line is laid out.
 *
 * Every case but the first edits a copy of the real file. The edit fails by name when the
 * text to change is not there exactly once, so no edited case can pass as a no-op.
 */

private const val REAL_FILE = "render/HomeHostView.kt"
private const val MERGED_HEADER = "    private fun merged(source: Screen?): HomeState = HomeState("
private const val NOTICE_LINE = "notice = handler.notice(),"
private const val NOTICE_LINE_WITH_INDENT = "        notice = handler.notice(),\n"
private const val SWITCH_LINE = "        switchOn = switch.isOn(),"
private const val COMMENT_WORDS = "and the notice the handler is showing:"
private const val MERGED_ANCHOR = "    /** Acts on [intent] and draws the result. */"

/** The problem the notice has when merged() does not pass the handler's own notice() as the notice. */
private const val NOTICE_PROBLEM = "merged() does not give the handler's own notice() as the notice"

private val WHITESPACE_RUNS = Regex("""\s+""")

/** The text before the call, with whitespace removed: the parameter list, then the return type, then the equals sign. */
private val SHAPE_BEFORE_CALL = Regex("""\A[\w:?<>,]*\):HomeState=\z""")

/** The text after the call's closing parenthesis, with whitespace removed: only the modifier words of the next declaration. */
private val SHAPE_AFTER_CALL = Regex("""\A[A-Za-z]*\z""")

/**
 * The text between the parentheses of the HomeState call in merged(), or an empty string when
 * there is none. The search stops at the next fun, val, var or init keyword, so a call written in
 * a later declaration is not read as merged()'s own. The call must be the whole of merged(): the
 * text before it, with whitespace removed, must be the parameter list and the return type, and the
 * text after its closing parenthesis, with whitespace removed, must be only letters, the modifier
 * words of the next declaration. Otherwise the empty string is returned.
 */
private fun argumentsOfMerged(code: String): String {
    val header = Regex("""\bfun\s+merged\s*\(""").find(code) ?: return ""
    val rest = code.substring(header.range.last + 1)
    val next = Regex("""\b(?:fun|val|var|init)\s""").find(rest)
    val tail = if (next == null) rest else rest.substring(0, next.range.first)
    val call = Regex("""\bHomeState\s*\(""").find(tail) ?: return ""
    val before = tail.substring(0, call.range.first).replace(WHITESPACE_RUNS, "")
    if (!SHAPE_BEFORE_CALL.containsMatchIn(before)) return ""
    val start = call.range.last + 1
    var depth = 1
    for (index in start until tail.length) {
        if (tail[index] == '(') depth++
        if (tail[index] == ')') {
            depth--
            if (depth == 0) {
                val after = tail.substring(index + 1).replace(WHITESPACE_RUNS, "")
                return if (SHAPE_AFTER_CALL.containsMatchIn(after)) tail.substring(start, index) else ""
            }
        }
    }
    return ""
}

/** The top-level arguments of a call, each with its whitespace removed; a trailing comma leaves an empty last part. */
private fun topLevelParts(arguments: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (ch in arguments) {
        if (ch == '(' || ch == '{' || ch == '[') depth++
        if (ch == ')' || ch == '}' || ch == ']') depth--
        if (ch == ',' && depth == 0) {
            parts.add(current.toString().replace(WHITESPACE_RUNS, ""))
            current.setLength(0)
        } else {
            current.append(ch)
        }
    }
    parts.add(current.toString().replace(WHITESPACE_RUNS, ""))
    return parts
}

/** The notice problem in [text] as a list: empty when merged() passes the handler's own notice() as the notice. */
private fun noticeProblems(text: String): List<String> {
    val parts = topLevelParts(argumentsOfMerged(withoutCommentsAndStrings(text)))
    return if ("notice=handler.notice()" in parts) emptyList() else listOf(NOTICE_PROBLEM)
}

/** The number of places [token] occurs in [text]. */
private fun occurrenceCount(text: String, token: String): Int =
    Regex(Regex.escape(token)).findAll(text).count()

/** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
private fun replaceOnce(text: String, old: String, new: String): String {
    assertTrue("ui: home: the text to change is not in the source exactly once: $old", occurrenceCount(text, old) == 1)
    return text.replace(old, new)
}

/** [what] is caught only when the one notice problem is the whole result: no other result passes. */
private fun assertCaught(what: String, edited: String) {
    assertEquals("ui: home: $what: ", listOf(NOTICE_PROBLEM), noticeProblems(edited))
}

private fun assertClean(what: String, edited: String) {
    assertEquals("ui: home: $what: ", emptyList<String>(), noticeProblems(edited))
}

/** The Home view's notice, pinned as text. */
class HomeHostNoticeGateTest {
    @Test
    fun `the real Home view draws the notice the handler holds`() {
        assertEquals("ui: home: the real notice argument: ", emptyList<String>(), noticeProblems(mainSourceOf(REAL_FILE)))
    }

    @Test
    fun `a notice fixed to null is caught`() {
        assertCaught("the notice fixed to null", replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice = null,"))
    }

    @Test
    fun `a notice line removed altogether is caught`() {
        assertCaught("the notice line removed", replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE_WITH_INDENT, ""))
    }

    @Test
    fun `a notice taken from another call is caught`() {
        assertCaught("the notice taken from shown()", replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice = handler.shown(),"))
    }

    @Test
    fun `a handler notice call moved out of the state build is caught`() {
        val nulled = replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice = null,")
        val moved = replaceOnce(nulled, MERGED_ANCHOR, "    private fun other() = handler.notice()\n\n$MERGED_ANCHOR")
        assertCaught("the call moved out of merged()", moved)
    }

    @Test
    fun `a notice call written only in a comment does not count`() {
        assertCaught("the call kept in a comment", replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice = null, // $NOTICE_LINE"))
    }

    @Test
    fun `a notice named inside a nested call is not the top level notice`() {
        val nulled = replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice = null,")
        val nested = replaceOnce(nulled, SWITCH_LINE, "        switchOn = check(1, notice = handler.notice(), 2),")
        assertCaught("the nested notice with the top level one null", nested)
    }

    @Test
    fun `merged copies a state built in a later function and the copy is caught`() {
        val copied = replaceOnce(
            mainSourceOf(REAL_FILE),
            MERGED_HEADER,
            "    private fun merged(source: Screen?): HomeState = fresh(source).copy(notice = null)\n\n" +
                "    private fun fresh(source: Screen?): HomeState = HomeState(",
        )
        assertCaught("the copy of a later state", copied)
    }

    @Test
    fun `a notice line broken across lines and padded with spaces is not a change`() {
        val padded = replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE, "notice =\n            handler.notice(\n            ),")
        assertClean("the padded notice line", padded)
    }

    @Test
    fun `a comment about the notice reworded is not a change`() {
        val reworded = replaceOnce(mainSourceOf(REAL_FILE), COMMENT_WORDS, "and the message the handler is showing:")
        assertClean("the reworded comment", reworded)
    }

    @Test
    fun `the notice argument moved to the front is not a change`() {
        val removed = replaceOnce(mainSourceOf(REAL_FILE), NOTICE_LINE_WITH_INDENT, "")
        assertClean("the notice argument first", replaceOnce(removed, SWITCH_LINE, NOTICE_LINE_WITH_INDENT + SWITCH_LINE))
    }

    @Test
    fun `a val declared after merged that holds the notice call does not count`() {
        val probed = replaceOnce(
            mainSourceOf(REAL_FILE),
            MERGED_HEADER,
            "    private fun merged(source: Screen?): HomeState = probe.copy(notice = null)\n\n" +
                "    private val probe: HomeState = HomeState(",
        )
        assertCaught("the notice held by a later val", probed)
    }

    @Test
    fun `a copy made after the call of merged is caught`() {
        val chained = replaceOnce(mainSourceOf(REAL_FILE), "    )\n\n$MERGED_ANCHOR", "    ).copy(notice = null)\n\n$MERGED_ANCHOR")
        assertCaught("the copy after the call", chained)
    }

    @Test
    fun `an identity wrapper around the call with a chained copy is caught`() {
        val wrapped = replaceOnce(mainSourceOf(REAL_FILE), MERGED_HEADER, "    private fun merged(source: Screen?): HomeState = identity(HomeState(")
        assertCaught("the identity wrapper and the copy", replaceOnce(wrapped, "    )\n\n$MERGED_ANCHOR", "    )).copy(notice = null)\n\n$MERGED_ANCHOR"))
    }

    @Test
    fun `an if else around the call that gives a null notice is caught`() {
        val branched = replaceOnce(
            mainSourceOf(REAL_FILE),
            MERGED_HEADER,
            "    private fun merged(source: Screen?): HomeState = if (source == null) HomeState(",
        )
        assertCaught("the if else with a null branch", replaceOnce(branched, "    )\n\n$MERGED_ANCHOR", "    ) else HomeState(notice = null)\n\n$MERGED_ANCHOR"))
    }

    @Test
    fun `a block body that returns the call is caught`() {
        val block = replaceOnce(
            mainSourceOf(REAL_FILE),
            MERGED_HEADER,
            "    private fun merged(source: Screen?): HomeState {\n        return HomeState(",
        )
        assertCaught("the block body", replaceOnce(block, "    )\n\n$MERGED_ANCHOR", "    )\n    }\n\n$MERGED_ANCHOR"))
    }
}
