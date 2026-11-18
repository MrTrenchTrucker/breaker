package dev.breaker.dictation.ui.screen.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The words the About screen shows, pinned where a silent reword would be caught.
 *
 * One of them is the project owner's word and must not drift: the check row. The other six are
 * proposals, so what is pinned for them is weaker but still real: every text is plain ASCII, none
 * is empty, and no two of the six are the same sentence, so a copy-paste cannot ship two rows that
 * read alike.
 */
class AboutTextsTest {

    @Test
    fun `the frozen labels are the owner's words`() {
        assertEquals("Check for updates", AboutTexts.CHECK_LABEL)
    }

    @Test
    fun `the sentence for an available update keeps its frozen words`() {
        assertEquals("An update is available.", AboutTexts.OFFERED)
    }

    @Test
    fun `every About text is plain ASCII`() {
        val all = listOf(
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
        all.forEach { text ->
            assertTrue("this text is not plain ASCII: $text", text.all { it.code < 128 })
        }
    }

    @Test
    fun `the six proposed sentences are non-empty and no two are alike`() {
        val proposed = listOf(
            AboutTexts.INSTALL,
            AboutTexts.CHECKING,
            AboutTexts.UP_TO_DATE,
            AboutTexts.OFFERED,
            AboutTexts.INSTALLING,
            AboutTexts.FAILED,
        )
        proposed.forEach { sentence ->
            assertTrue("an empty sentence would draw a blank line", sentence.isNotBlank())
        }
        assertEquals(
            "two rows must not carry the same sentence",
            proposed.size,
            proposed.toSet().size,
        )
    }
}