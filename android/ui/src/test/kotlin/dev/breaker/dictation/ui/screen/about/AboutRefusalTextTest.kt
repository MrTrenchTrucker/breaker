package dev.breaker.dictation.ui.screen.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The refusal sentence, pinned word for word.
 *
 * The sentence is the project owner's word and must not drift, so the words are written out
 * here again and compared with the constant. Every other About text is compared too, so a
 * copy-paste cannot ship the refusal sentence under another row's words, and the texts that
 * were there before the refusal keep their words.
 */
class AboutRefusalTextTest {

    /** The sentence written out again here, so a reword of the constant fails this test. */
    private val frozen = "The update could not be installed. Tap Install update to try again."

    /** Every other About text, the failure sentence included. */
    private val others = listOf(
        AboutTexts.TITLE,
        AboutTexts.LINE_1,
        AboutTexts.LINE_2,
        AboutTexts.CHECK_LABEL,
        AboutTexts.INSTALL,
        AboutTexts.CHECKING,
        AboutTexts.UP_TO_DATE,
        AboutTexts.OFFERED,
        AboutTexts.INSTALLING,
        AboutTexts.FAILED,
    )

    @Test
    fun `the refusal sentence keeps the frozen words exactly`() {
        assertEquals(frozen, AboutTexts.REFUSED)
    }

    @Test
    fun `the refusal sentence is plain ASCII on one line and not blank`() {
        assertTrue("the refusal sentence is not plain ASCII: ${AboutTexts.REFUSED}", AboutTexts.REFUSED.all { it.code < 128 })
        assertTrue("the refusal sentence spans more than one line", !AboutTexts.REFUSED.contains('\n'))
        assertTrue("the refusal sentence is blank", AboutTexts.REFUSED.isNotBlank())
    }

    @Test
    fun `the refusal sentence differs from every other About text, the failure sentence included`() {
        others.forEach { text ->
            assertTrue("the refusal sentence must not equal another About text: $text", AboutTexts.REFUSED != text)
        }
    }

    @Test
    fun `the texts from before the refusal keep their words`() {
        assertEquals("About", AboutTexts.TITLE)
        assertEquals("Breaker turns your voice into text, hands free, on this phone.", AboutTexts.LINE_1)
        assertEquals("Open source under the Apache License 2.0.", AboutTexts.LINE_2)
        assertEquals("Check for updates", AboutTexts.CHECK_LABEL)
        assertEquals("Install update", AboutTexts.INSTALL)
        assertEquals("Checking for updates...", AboutTexts.CHECKING)
        assertEquals("Breaker is up to date.", AboutTexts.UP_TO_DATE)
        assertEquals("An update is available.", AboutTexts.OFFERED)
        assertEquals("Installing the update...", AboutTexts.INSTALLING)
        assertEquals("That did not work. Try again.", AboutTexts.FAILED)
    }
}
