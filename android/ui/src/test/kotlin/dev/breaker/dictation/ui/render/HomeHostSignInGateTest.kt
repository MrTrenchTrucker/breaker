package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The way to sign in on the signed-out notice of the Home screen.
 *
 * The host takes the liveness reader and the sign-in answer as its two last
 * parameters, passes them on to the state it builds on every drawing, and the
 * SIGN_IN action calls the app's onSignIn exactly once. While the menu is open
 * the existing rule applies: a tap that is not a drawer item only closes the
 * menu, and nothing else happens, so the sign-in tap is not in the drawer set.
 *
 * Every rule reads the source of the real view with comments and literal text
 * removed. Each rule is shown to fire on an edited copy of that source before
 * it is held on the real one, and every edit fails by name when the text it
 * changes is not there exactly once, so an edited copy cannot pass as a no-op.
 */

private const val REAL_FILE = "render/HomeHostView.kt"

/** How many times [token] occurs in [text]. */
private fun occurrenceCount(text: String, token: String): Int =
    Regex(Regex.escape(token)).findAll(text).count()

/** The named problems with the sign-in wiring in [text]; an empty list means the view meets every rule. */
private fun signInProblemsIn(text: String): List<String> {
    val flat = withoutCommentsAndStrings(text)
        .replace(Regex("""\s+"""), "")
    val problems = mutableListOf<String>()
    if (occurrenceCount(flat, "onSignIn:()->Unit") != 1) {
        problems += "the host does not take the onSignIn answer exactly once"
    }
    if (occurrenceCount(flat, "sessionLive:()->Boolean") != 1) {
        problems += "the host does not take the liveness reader exactly once"
    }
    if (!flat.contains("renderer:ScreenRenderer,privatevalsessionLive:()->Boolean,privatevalonSignIn:()->Unit")) {
        problems += "the two answers are not the last parameters after the renderer"
    }
    if (occurrenceCount(flat, "onSignIn()") != 1) {
        problems += "onSignIn is not called exactly once"
    }
    if (!flat.contains("HomeActions.SIGN_IN->onSignIn()")) {
        problems += "the SIGN_IN action does not call onSignIn"
    }
    if (!flat.contains("sessionLive=sessionLive(),")) {
        problems += "the state built for drawing does not read the liveness reader"
    }
    if (!flat.contains("state.menuOpen&&intent.action!indrawerActions")) {
        problems += "the menu-open check before any action is gone"
    }
    if (!flat.contains("drawerActions=setOf(HomeActions.HISTORY,")) {
        problems += "the drawer set is not the four items and the dim"
    }
    return problems
}

/** The named problems with the entry in [text]: the two answers must go on to the host, in order, after the renderer. */
private fun entryProblemsIn(text: String): List<String> {
    val flat = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")
    return if (flat.contains("ScreenRenderer(context),sessionLive,onSignIn,)")) {
        emptyList()
    } else {
        listOf("the entry does not hand the liveness reader and the sign-in answer to the host in order")
    }
}

/** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
private fun replaceOnce(text: String, old: String, new: String): String {
    assertTrue(
        "ui: home sign in: the text to change is not in the source exactly once: $old",
        occurrenceCount(text, old) == 1,
    )
    return text.replace(old, new)
}

private fun assertFires(what: String, problems: List<String>, part: String) =
    assertTrue("ui: home sign in: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

private fun assertClean(what: String, edited: String) =
    assertEquals("ui: home sign in: $what must not be reported", emptyList<String>(), signInProblemsIn(edited))

/** The sign-in wiring of the Home view, pinned as text. */
class HomeHostSignInGateTest {
    private val real = mainSourceOf(REAL_FILE)

    @Test
    fun `the real Home view meets every sign in rule`() {
        assertEquals("ui: home sign in: the real view: ", emptyList<String>(), signInProblemsIn(real))
    }

    @Test
    fun `the home host acts on the sign in action by calling onSignIn`() {
        // The branch the app answers: present on the real view, and each red control is reported.
        assertFires(
            "a no-op branch",
            signInProblemsIn(replaceOnce(real, "HomeActions.SIGN_IN -> onSignIn()", "HomeActions.SIGN_IN -> Unit")),
            "does not call onSignIn",
        )
        assertFires(
            "a dropped branch",
            signInProblemsIn(replaceOnce(real, "HomeActions.SIGN_IN -> onSignIn()", "HomeActions.SIGN_IN -> onAbout()")),
            "does not call onSignIn",
        )
        assertFires(
            "a second call of the answer",
            signInProblemsIn(replaceOnce(real, "HomeActions.SIGN_IN -> onSignIn()", "HomeActions.SIGN_IN -> { onSignIn(); onSignIn() }")),
            "not called exactly once",
        )
        assertClean(
            "the branch split over lines",
            replaceOnce(real, "HomeActions.SIGN_IN -> onSignIn()", "HomeActions.SIGN_IN ->\n                onSignIn()"),
        )
    }

    @Test
    fun `a host that loses its answers is reported`() {
        assertFires(
            "no onSignIn parameter",
            signInProblemsIn(replaceOnce(real, "    private val onSignIn: () -> Unit,\n", "    private val onSignIn: (String) -> Unit,\n")),
            "does not take the onSignIn answer exactly once",
        )
        assertFires(
            "no liveness reader",
            signInProblemsIn(replaceOnce(real, "    private val sessionLive: () -> Boolean,\n", "    private val sessionLive: () -> String,\n")),
            "does not take the liveness reader exactly once",
        )
        assertFires(
            "the answers moved before the renderer",
            signInProblemsIn(replaceOnce(
                real,
                "    private val renderer: ScreenRenderer,\n    private val sessionLive: () -> Boolean,\n    private val onSignIn: () -> Unit,\n",
                "    private val sessionLive: () -> Boolean,\n    private val onSignIn: () -> Unit,\n    private val renderer: ScreenRenderer,\n",
            )),
            "not the last parameters",
        )
    }

    @Test
    fun `the entry hands the liveness reader and the sign in answer on to the host`() {
        val entry = mainSourceOf("HomeEntry.kt")
        assertEquals("ui: home sign in: the real entry: ", emptyList<String>(), entryProblemsIn(entry))
        val reader = replaceOnce(entry, "        sessionLive,\n        onSignIn,\n", "        { true },\n        onSignIn,\n")
        assertFires("the reader replaced", entryProblemsIn(reader), "does not hand")
        val answer = replaceOnce(entry, "        sessionLive,\n        onSignIn,\n", "        sessionLive,\n        {},\n")
        assertFires("the answer replaced", entryProblemsIn(answer), "does not hand")
        val oneLine = replaceOnce(entry, "        sessionLive,\n        onSignIn,\n", "        sessionLive, onSignIn,\n")
        assertEquals("ui: home sign in: the two on one line must not be reported", emptyList<String>(), entryProblemsIn(oneLine))
    }

    @Test
    fun `a state that does not read the liveness is reported`() {
        assertFires(
            "the reader not read",
            signInProblemsIn(replaceOnce(real, "        sessionLive = sessionLive(),\n", "        sessionLive = true,\n")),
            "does not read the liveness reader",
        )
        assertClean(
            "the read split over lines",
            replaceOnce(real, "        sessionLive = sessionLive(),\n", "        sessionLive =\n            sessionLive(),\n"),
        )
    }

    @Test
    fun `a menu tap that is not a drawer item still only closes the menu`() {
        assertFires(
            "the menu check dropped",
            signInProblemsIn(replaceOnce(
                real,
                "        if (intent is ScreenIntent.Home && state.menuOpen && intent.action !in drawerActions) {",
                "        if (intent is ScreenIntent.Home && false) {",
            )),
            "menu-open check",
        )
        assertClean(
            "the check split over lines",
            replaceOnce(
                real,
                "        if (intent is ScreenIntent.Home && state.menuOpen && intent.action !in drawerActions) {",
                "        if (intent is ScreenIntent.Home &&\n            state.menuOpen &&\n            intent.action !in drawerActions) {",
            ),
        )
    }
}
