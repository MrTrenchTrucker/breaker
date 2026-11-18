package dev.breaker.dictation.ui.screen.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The refused line: a refusal carries the app's text and the screen shows it when it is set, the
 * fixed refusal when it is not. The tests assert the routing and the exhaustive line of every state,
 * never the wording of the held locked sentence.
 */
class AuthRefusedLineTest {

    @Test
    fun `a refused state shows the line it carries when the text is set`() {
        assertEquals("line-from-the-app", AuthState.Refused(register = false, text = "line-from-the-app").line())
        assertEquals("line-from-the-app", AuthState.Refused(register = true, text = "line-from-the-app").line())
    }

    @Test
    fun `a refused state falls back to the fixed refusal when the text is blank`() {
        assertEquals(AuthTexts.REFUSED, AuthState.Refused(register = false, text = "").line())
        assertEquals(AuthTexts.REFUSED, AuthState.Refused(register = true, text = "   ").line())
    }

    @Test
    fun `every state maps to its own line`() {
        assertEquals(AuthTexts.IDLE, AuthState.Idle(register = false).line())
        assertEquals(AuthTexts.REFUSED, AuthState.Refused(register = false, text = "").line())
        assertEquals(AuthTexts.WEAK_WARNING, AuthState.Warning(register = false, text = "weak").line())
        assertEquals(AuthTexts.BUSY, AuthState.Busy(register = false).line())
    }

    @Test
    fun `the handler carries the app line into the refused state on the submitted face`() {
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.tap(AuthNodeIds.FACE_LOGIN)
        val state = handler.refused(register = false, text = "line-from-the-app")
        assertTrue(state is AuthState.Refused)
        assertEquals(false, (state as AuthState.Refused).register)
        assertEquals("line-from-the-app", (state as AuthState.Refused).text)
        val current = handler.current()
        assertTrue(current is AuthState.Refused)
        assertEquals(false, (current as AuthState.Refused).register)
        assertEquals("line-from-the-app", (current as AuthState.Refused).text)
    }
}
