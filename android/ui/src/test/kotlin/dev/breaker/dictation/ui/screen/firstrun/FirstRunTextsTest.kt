package dev.breaker.dictation.ui.screen.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run words and the two bar captions, checked as plain values.
 *
 * The caption numbers are put in exactly as they are given. The app side forwards its numbers
 * unchanged, and the test below checks that this side shows them unchanged.
 */
class FirstRunTextsTest {
    private val constants = listOf(
        FirstRunTexts.NEXT,
        FirstRunTexts.MISSING_HEADING,
        FirstRunTexts.MISSING_NONE,
        FirstRunTexts.BAR_MB_OF,
        FirstRunTexts.BAR_MB_SO_FAR,
        FirstRunTexts.BAR_PERCENT,
        FirstRunTexts.LOGO_DESCRIPTION,
    )

    @Test
    fun `the megabyte line is the total form when the total is known`() {
        assertEquals("7 of 365 MB", FirstRunTexts.mbLine(7, 365))
    }

    @Test
    fun `the megabyte line is the so far form when the total is zero`() {
        assertEquals("7 MB so far", FirstRunTexts.mbLine(7, 0))
    }

    @Test
    fun `the percent line puts the number in`() {
        assertEquals("41%", FirstRunTexts.percentLine(41))
    }

    @Test
    fun `every first-run text is plain ASCII and not blank`() {
        for (text in constants) {
            assertTrue("blank: '$text'", text.isNotBlank())
            assertTrue("not ASCII: '$text'", text.all { it.code < 128 })
        }
    }

    @Test
    fun `the total caption holds both placeholders`() {
        assertTrue(FirstRunTexts.BAR_MB_OF.contains("{done}"))
        assertTrue(FirstRunTexts.BAR_MB_OF.contains("{total}"))
    }

    @Test
    fun `the approved words are exactly as approved`() {
        assertEquals("Next", FirstRunTexts.NEXT)
        assertEquals("Still missing", FirstRunTexts.MISSING_HEADING)
        assertEquals("Everything is set.", FirstRunTexts.MISSING_NONE)
        assertEquals("Breaker", FirstRunTexts.LOGO_DESCRIPTION)
    }

    @Test
    fun `the megabyte line uses the numbers it is given without rounding them again`() {
        assertEquals("7 of 365 MB", FirstRunTexts.mbLine(7, 365))
        assertEquals("1000 of 1048 MB", FirstRunTexts.mbLine(1000, 1048))
    }
}
