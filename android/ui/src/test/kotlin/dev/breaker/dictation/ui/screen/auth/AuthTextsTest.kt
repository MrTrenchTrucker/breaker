package dev.breaker.dictation.ui.screen.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * The words the auth screen shows. The install code label is the project owner's approved sentence for
 * this field, so it is pinned here: a reword ships only with a test change, never silently.
 */
class AuthTextsTest {

    @Test
    fun `the install code label is the approved sentence`() {
        assertEquals("Install code (optional)", AuthTexts.INSTALL_CODE_LABEL)
    }
}
