package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.screen.history.HistoryTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The kit's words are the ledger's approved strings, in sentence case, and the destructive
 * button's word is the history screen's own constant - the same word, not a copy.
 */
class ControlTextsTest {
    @Test
    fun `the cancel button's word is the approved sentence`() {
        assertEquals("Cancel", ControlTexts.BUTTON_CANCEL)
    }

    @Test
    fun `the open-settings button's word is the approved sentence`() {
        assertEquals("Open settings", ControlTexts.BUTTON_OPEN_SETTINGS)
    }

    @Test
    fun `the ready badge's word is the approved sentence`() {
        assertEquals("Ready", ControlTexts.BADGE_READY)
    }

    @Test
    fun `the phone-only badge's word is the approved sentence`() {
        assertEquals("Phone only", ControlTexts.BADGE_PHONE_ONLY)
    }

    @Test
    fun `the failed badge's word is the approved sentence`() {
        assertEquals("Failed", ControlTexts.BADGE_FAILED)
    }

    @Test
    fun `the delete button's word is the history screen's own, not a copy`() {
        assertSame(HistoryTexts.BUTTON_DELETE, ControlTexts.BUTTON_DELETE)
    }
}
