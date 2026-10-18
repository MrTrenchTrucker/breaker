package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The menu-tap rule: while the menu is open, a tap on the mic closes the
 * menu instead of starting a take.
 *
 * The rule is in HomeHostView.act(): the MIC action checks state.menuOpen
 * and calls CLOSE_MENU when the menu is open, onTap when it is closed.
 *
 * Each rule reads the source with comments and literal text removed, and is
 * shown to fire on an edited copy of that source before it is held on the
 * real one.
 */
class HomeIntentHandlerMenuTapTest {

    private val real = mainSourceOf("render/HomeHostView.kt")

    private val WHITESPACE_RUNS = Regex("""\s+""")

    private fun flatCode(text: String): String = withoutCommentsAndStrings(text).replace(WHITESPACE_RUNS, "")

    /** The named problems with the menu-tap rule in [text]; an empty list means the rule holds. */
    private fun menuTapProblemsIn(text: String): List<String> {
        val flat = flatCode(text)
        val problems = mutableListOf<String>()
        if (!flat.contains("HomeActions.MIC->if(state.menuOpen)onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU))elseonTap()")) {
            problems += "the MIC action does not check menuOpen and route to CLOSE_MENU or onTap"
        }
        return problems
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue(
            "ui: menu-tap: the text to change is not in the source exactly once: $old",
            Regex(Regex.escape(old)).findAll(text).count() == 1,
        )
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("ui: menu-tap: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    @Test
    fun `the real Home view holds the menu-tap rule`() {
        assertEquals("ui: menu-tap: the real view: ", emptyList<String>(), menuTapProblemsIn(real))
    }

    @Test
    fun `a MIC action that always calls onTap is reported`() {
        val edited = replaceOnce(
            real,
            "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()",
            "HomeActions.MIC -> onTap()",
        )
        assertFires("the always-onTap edit", menuTapProblemsIn(edited), "does not check menuOpen")
    }

    @Test
    fun `a MIC action that always closes the menu is reported`() {
        val edited = replaceOnce(
            real,
            "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()",
            "HomeActions.MIC -> onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU))",
        )
        assertFires("the always-close edit", menuTapProblemsIn(edited), "does not check menuOpen")
    }

    @Test
    fun `a MIC action with the menu check reversed is reported`() {
        val edited = replaceOnce(
            real,
            "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()",
            "HomeActions.MIC -> if (!state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()",
        )
        assertFires("the reversed check", menuTapProblemsIn(edited), "does not check menuOpen")
    }
}
