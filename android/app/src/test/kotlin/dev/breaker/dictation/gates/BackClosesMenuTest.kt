package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Back press on the home screen asks the home view to close its menu first, and the activity's own
 * Back runs only when the menu was not open. The launcher activity is read with its comments and literal
 * text removed, the way the other rules of this folder read it.
 */
class BackClosesMenuTest {
    private val activityPath: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** The else branch of the activity's Back: the home view answers first, and super runs only on a no. */
    private val closeFirst = Regex(
        """else\s*->\s*if\s*\(\s*\(\s*homeView\s+as\?\s+dev\.breaker\.dictation\.ui\.HomeControl\s*\)\s*\?\.\s*closeMenu\s*\(\s*\)\s*!=\s*true\s*\)\s*super\.onBackPressed\s*\(\s*\)""",
    )

    private fun codeOf(source: String): String = AppSourceFiles.strip(source).code

    private fun countOf(code: String): Int = closeFirst.findAll(code).count()

    /** [source] with the text [from] replaced by [to]; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun edit(label: String, source: String, from: String, to: String): String {
        check(source.contains(from)) { "the sample \"$label\" has no text \"$from\" to change" }
        return source.replace(from, to)
    }

    @Test
    fun `the back press asks the home view to close its menu before the activity's own back`() {
        val source = AppSourceFiles.mainFile(activityPath)
        assertEquals("app: the else branch must consult closeMenu before super", 1, countOf(codeOf(source)))
    }

    @Test
    fun `a bare super call or an inverted answer in the else branch is reported`() {
        val source = AppSourceFiles.mainFile(activityPath)
        val bare = edit(
            "else branch",
            source,
            "else -> if ((homeView as? dev.breaker.dictation.ui.HomeControl)?.closeMenu() != true) super.onBackPressed()",
            "else -> super.onBackPressed()",
        )
        assertEquals("app: a bare super call must be reported", 0, countOf(codeOf(bare)))
        val inverted = edit("answer", source, "!= true) super.onBackPressed()", "== true) super.onBackPressed()")
        assertEquals("app: an inverted answer must be reported", 0, countOf(codeOf(inverted)))
    }

    @Test
    fun `a comment about the close answer is not a change to the else branch`() {
        val source = AppSourceFiles.mainFile(activityPath)
        val quiet = edit("comment", source, "    override fun onBackPressed() {", "    // the home view closes its menu first\n    override fun onBackPressed() {")
        assertEquals("app: a comment is not a change", 1, countOf(codeOf(quiet)))
    }
}
