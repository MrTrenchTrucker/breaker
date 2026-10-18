package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.history.HistoryTexts
import dev.breaker.dictation.ui.screen.home.HomeTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one final full stop that a drawn label drops. The pure function is checked on its own, and the two
 * labels that draw a code word through it are checked in their source. The code words keep their full stops.
 */
class DropFinalPeriodTest {
    private val homeTitle = "text = dropFinalPeriod(cardWord(state)), role = TypeRole.DISPLAY"
    private val historyHeading = "dropFinalPeriod(HistoryTexts.EMPTY_HEADING),"

    @Test
    fun `one final full stop is dropped and nothing else is`() {
        assertEquals("the final full stop goes", "Breaker is on", dropFinalPeriod("Breaker is on."))
        assertEquals("only one of two final full stops goes", "Wait.", dropFinalPeriod("Wait.."))
    }

    @Test
    fun `a text without a final full stop comes back as it is`() {
        assertEquals("a quiet sample", "NOTHING HERE YET", dropFinalPeriod("NOTHING HERE YET"))
        assertEquals("a full stop inside the text is kept", "a. b", dropFinalPeriod("a. b"))
    }

    @Test
    fun `an empty text stays empty and a lone full stop goes`() {
        assertEquals("an empty text", "", dropFinalPeriod(""))
        assertEquals("a lone full stop", "", dropFinalPeriod("."))
    }

    @Test
    fun `the code words drop their final full stop when drawn`() {
        assertEquals("the on word", "Breaker is on", dropFinalPeriod(HomeTexts.SWITCH_ON))
        assertEquals("the off word", "Breaker is off", dropFinalPeriod(HomeTexts.SWITCH_OFF))
        assertEquals("the empty heading", "Nothing here yet", dropFinalPeriod(HistoryTexts.EMPTY_HEADING))
    }

    /** The text of a main source file, read from the module directory the tests run in. */
    private fun sourceOf(relative: String): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/$relative")
        assertTrue("the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The labels that are not drawn through the function, one message each. */
    private fun undroppedIn(home: String, history: String): List<String> = listOfNotNull(
        "the card title is not drawn through the function".takeUnless { homeTitle in home },
        "the empty heading is not drawn through the function".takeUnless { historyHeading in history },
    )

    @Test
    fun `the Home status title and the History empty heading are drawn through the function`() {
        val home = sourceOf("screen/home/HomeScreen.kt")
        val history = sourceOf("screen/history/HistoryScreen.kt")
        assertEquals("the real screens", emptyList<String>(), undroppedIn(home, history))
        assertEquals(
            "the title drawing the word itself",
            listOf("the card title is not drawn through the function"),
            undroppedIn(home.replace(homeTitle, "text = cardWord(state), role = TypeRole.DISPLAY"), history),
        )
        assertEquals(
            "the heading drawing the word itself",
            listOf("the empty heading is not drawn through the function"),
            undroppedIn(home, history.replace(historyHeading, "HistoryTexts.EMPTY_HEADING,")),
        )
    }
}
