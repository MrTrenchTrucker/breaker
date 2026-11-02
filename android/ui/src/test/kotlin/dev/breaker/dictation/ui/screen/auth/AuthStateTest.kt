package dev.breaker.dictation.ui.screen.auth

/*
 * RED-first: the sealed state types and their shake surface do not exist on the pre-change tree,
 * so every assertion that names them fails before AuthState is written. This header records that,
 * as required for a red test, because nothing compiles on this host and CI is the only compiler.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthStateTest {

    @Test
    fun `the two idle faces carry no password text`() {
        assertEquals("Idle(register=true)", AuthState.Idle(true).toString())
        assertEquals("Idle(register=false)", AuthState.Idle(false).toString())
    }

    @Test
    fun `refused and warning labels print no password`() {
        val typedText = "typed-sample-text"
        val refused = AuthState.Refused(false, typedText)
        val warning = AuthState.Warning(true, typedText)
        // The text the state was built with is really in the object...
        assertEquals(typedText, refused.text)
        assertEquals(typedText, warning.text)
        // ...but the label never prints it: a pinned name, not the fields.
        assertEquals("Refused", refused.toString())
        assertEquals("Warning", warning.toString())
        assertFalse(refused.toString().contains(typedText))
        assertFalse(warning.toString().contains(typedText))
    }

    @Test
    fun `busy carries the register face and no shake, and idle carries none`() {
        assertEquals(true, AuthState.Busy(true).register)
        assertEquals(false, AuthState.Busy(false).register)
        assertEquals(null, AuthState.Busy(true).shakeMs)
        assertEquals(null, AuthState.Idle(false).shakeMs)
    }

    @Test
    fun `refused and warning shake the masked surface for a fixed duration`() {
        assertEquals(600L, AuthState.Refused(false, "No").shakeMs)
        assertEquals(600L, AuthState.Warning(false, "Weak").shakeMs)
        assertEquals(null, AuthState.Idle(register = false).shakeMs)
        assertEquals(null, AuthState.Busy(register = true).shakeMs)
    }

}
