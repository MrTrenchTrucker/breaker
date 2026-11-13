package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the signed-out defaults and pass-through of createSettingsView: a caller that
 * passes no session check is signed in (so the block never shows by accident), and a session check or
 * sign in callback that is passed reaches the host view. Each rule holds on the real file and is
 * reported on an edited copy of it.
 */
class SettingsEntrySignedOutRulesTest {
    private val code = withoutComments(mainSourceOf("SettingsEntry.kt")).replace(Regex("""\s+"""), " ")

    private fun editOnce(text: String, old: String, new: String): String {
        assertEquals(
            "entry: signed out: the text to change is not in the entry exactly once: $old",
            1,
            Regex(Regex.escape(old)).findAll(text).count(),
        )
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("entry: signed out: control: $what must be reported, got $problems", problems.isNotEmpty())

    private val liveDefault = "private val ALWAYS_LIVE: () -> Boolean = { true }"
    private val passLiveness = "onDownloadModel, sessionLive ?: ALWAYS_LIVE, onSignIn ?: NO_SIGN_IN)"

    private fun defaultProblems(text: String): List<String> =
        listOfNotNull("the default session check is not the one that answers signed in".takeUnless { liveDefault in text })

    private fun passThroughProblems(text: String): List<String> = listOfNotNull(
        "the session check the caller passes does not reach the host view".takeUnless { "sessionLive ?: ALWAYS_LIVE," in text },
        "the sign in callback the caller passes does not reach the host view".takeUnless { "onSignIn ?: NO_SIGN_IN)" in text },
        "the host view call does not end with the session check and the sign in callback".takeUnless { passLiveness in text },
    )

    @Test
    fun `a caller that passes no session check is treated as signed in`() {
        assertFires("a default that answers signed out", defaultProblems(editOnce(code, liveDefault, "private val ALWAYS_LIVE: () -> Boolean = { false }")))
        assertEquals("entry: signed out: the default", emptyList<String>(), defaultProblems(code))
    }

    @Test
    fun `the session check and the sign in callback reach the host view`() {
        assertFires("a dropped session check", passThroughProblems(editOnce(code, "sessionLive ?: ALWAYS_LIVE", "ALWAYS_LIVE")))
        assertFires("a dropped sign in callback", passThroughProblems(editOnce(code, "onSignIn ?: NO_SIGN_IN", "NO_SIGN_IN")))
        assertEquals("entry: signed out: the pass-through", emptyList<String>(), passThroughProblems(code))
    }
}
