package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the back intent in the three screen handlers that answer it. A back
 * changes no state: the settings and history handlers draw the held screen as a no-op does, and the
 * set-up handler draws the held screen again keeping its pinned refusal. None of them may answer it
 * with a notice.
 */
class BackAnswerRulesTest {
    private val settings = mainSourceOf("screen/settings/SettingsIntentHandler.kt")
    private val history = mainSourceOf("screen/history/HistoryIntentHandler.kt")
    private val setup = mainSourceOf("screen/onboarding/SetupIntentHandler.kt")

    private val whitespace = Regex("""\s+""")

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    /** [text] with its comments taken out, then every run of whitespace, line breaks included, made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    private fun settingsProblems(text: String): List<String> {
        val body = flat(between(text, "fun handle(intent: ScreenIntent): Rendered {", "private fun themeNotice("))
        return listOfNotNull("the settings back changes nothing".takeUnless { "ScreenIntent.Back -> Unit" in body })
    }

    private fun historyProblems(text: String): List<String> {
        val body = flat(between(text, "fun handle(intent: ScreenIntent): Screen = when (intent) {", "fun close()"))
        return listOfNotNull("the history back draws the held screen".takeUnless { "ScreenIntent.Back, -> draw()" in body })
    }

    private fun setupProblems(text: String): List<String> {
        val body = flat(between(text, "fun handle(intent: ScreenIntent): Screen {", "/** Does what the action string asks"))
        return listOfNotNull(
            "the set-up back draws the held screen again with its pinned refusal and no new notice".takeUnless {
                "ScreenIntent.Back -> return show(notice = null, fresh = false, redraw = true)" in body
            },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("screen: back: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("screen: back: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the settings handler answers back by changing nothing`() {
        assertQuiet("the real settings handler", settingsProblems(settings))
        assertFires("the settings back given a notice", settingsProblems(settings.replace("ScreenIntent.Back -> Unit", "ScreenIntent.Back -> notice = Notice.NOT_ACCEPTED")))
    }

    @Test
    fun `the history handler answers back by drawing the held screen`() {
        assertQuiet("the real history handler", historyProblems(history))
        assertFires("the history back removed from the draw list", historyProblems(history.replace("ScreenIntent.Back,", "")))
    }

    @Test
    fun `the set-up handler answers back by drawing again and never with a notice`() {
        assertQuiet("the real set-up handler", setupProblems(setup))
        assertFires(
            "the set-up back answered with a notice",
            setupProblems(setup.replace("ScreenIntent.Back -> return show(notice = null, fresh = false, redraw = true)", "ScreenIntent.Back -> SetupNotice.NOT_ACCEPTED")),
        )
    }
}
