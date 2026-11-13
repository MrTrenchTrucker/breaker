package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the signed-out wiring of the settings host: the liveness answer is the negation of
 * sessionLive, both drawing paths hand it to the handler before the handler draws, and the Sign in intent
 * is answered with onSignIn before any other intent branch can take it. Each rule holds on the real file
 * and is reported on an edited copy of it. Comments are removed and white space is made into one space.
 */
class SettingsHostSignedOutRulesTest {
    private val code = flat(mainSourceOf("render/SettingsHostView.kt"))

    private fun flat(text: String): String = withoutComments(text).replace(Regex("""\s+"""), " ")

    /** The text from [start] up to [end]; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String): String {
        val after = text.substringAfter(start, "")
        return after.substringBefore(end, after)
    }

    private fun editOnce(text: String, old: String, new: String): String {
        assertEquals(
            "screen: settings host: signed out: the text to change is not in the host exactly once: $old",
            1,
            Regex(Regex.escape(old)).findAll(text).count(),
        )
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("screen: settings host: signed out: control: $what must be reported, got $problems", problems.isNotEmpty())

    private val livenessLine = "private fun signedOut(): Boolean = !sessionLive()"
    private val flagCall = "handler.showingSignedOut(signedOut())"
    private val signInBranch = "intent is ScreenIntent.Home && intent.action == HomeActions.SIGN_IN -> onSignIn()"

    private fun livenessProblems(text: String): List<String> =
        listOfNotNull("signedOut() is not exactly the negation of sessionLive()".takeUnless { livenessLine in flat(text) })

    /** The redraw path: the flag is set, then the handler draws, in that order. */
    private fun refreshProblems(text: String): List<String> {
        val body = between(flat(text), "private fun refreshed(): Rendered {", "private fun signedOut()")
        return listOfNotNull(
            "refreshed() does not set the signed-out flag before the handler draws".takeUnless {
                "handler.showing(rowNow()) $flagCall return handler.current()" in body
            },
        )
    }

    /** The intent path: the flag is set, then the handler acts and the screen is drawn, in that order. */
    private fun intentFlagProblems(text: String): List<String> {
        val body = between(flat(text), "private fun onIntent(intent: ScreenIntent) {", "override fun downloadProgress")
        return listOfNotNull(
            "onIntent does not set the signed-out flag before the handler acts".takeUnless {
                "else -> { handler.showing(rowNow()) $flagCall show(handler.handle(intent)) }" in body
            },
        )
    }

    /** The Sign in intent goes to onSignIn, and its branch comes before the catch-all branch. */
    private fun signInProblems(text: String): List<String> {
        val body = between(flat(text), "private fun onIntent(intent: ScreenIntent) {", "override fun downloadProgress")
        val branch = body.indexOf(signInBranch)
        val rest = body.indexOf("else ->")
        return listOfNotNull(
            "onIntent does not answer the Sign in intent with onSignIn()".takeIf { branch < 0 },
            "the Sign in branch comes after the catch-all branch".takeIf { branch >= 0 && rest in 0 until branch },
        )
    }

    @Test
    fun `the liveness answer is the negation of the session check`() {
        assertFires("a flipped liveness", livenessProblems(editOnce(code, livenessLine, "private fun signedOut(): Boolean = sessionLive()")))
        assertEquals("screen: settings host: signed out: the liveness answer", emptyList<String>(), livenessProblems(code))
    }

    @Test
    fun `the redraw path hands the flag to the handler before it draws`() {
        val without = editOnce(code, "handler.showing(rowNow()) $flagCall return handler.current()", "handler.showing(rowNow()) return handler.current()")
        assertFires("a redraw without the flag", refreshProblems(without))
        assertEquals("screen: settings host: signed out: the redraw path", emptyList<String>(), refreshProblems(code))
    }

    @Test
    fun `the intent path hands the flag to the handler before it acts`() {
        val without = editOnce(code, "handler.showing(rowNow()) $flagCall show(handler.handle(intent))", "handler.showing(rowNow()) show(handler.handle(intent))")
        assertFires("an intent path without the flag", intentFlagProblems(without))
        assertEquals("screen: settings host: signed out: the intent path", emptyList<String>(), intentFlagProblems(code))
    }

    @Test
    fun `the sign in intent is answered with the app callback and nothing else`() {
        assertFires("a deleted sign in branch", signInProblems(editOnce(code, "$signInBranch ", "")))
        assertFires("a sign in branch that does nothing", signInProblems(editOnce(code, "HomeActions.SIGN_IN -> onSignIn()", "HomeActions.SIGN_IN -> Unit")))
        assertFires("a sign in branch after the catch-all", signInProblems(editOnce(editOnce(code, "$signInBranch ", ""), "else -> {", "else -> { $signInBranch")))
        assertEquals("screen: settings host: signed out: the sign in branch", emptyList<String>(), signInProblems(code))
    }
}
