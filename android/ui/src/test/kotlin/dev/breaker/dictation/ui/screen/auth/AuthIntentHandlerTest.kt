package dev.breaker.dictation.ui.screen.auth

/*
 * RED-first: the handler's submit contract does not exist on the pre-change tree, so every assertion
 * naming it fails before AuthIntentHandler is written. This header records that, as required for a
 * red test, because nothing compiles on this host and CI is the only compiler. The three install-code
 * tests below name the callback's fourth argument, which does not exist on the pre-change tree either.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthIntentHandlerTest {

    /** A password built from [text], one char per character of the literal. */
    private fun typed(text: String): CharArray = text.toCharArray()

    @Test
    fun `the register face tap returns to idle`() {
        val handler = AuthIntentHandler { _, _, _, _ -> }
        assertEquals(AuthState.Idle(register = true), handler.tap("face.register"))
        assertSameState(handler.tap("face.login"), AuthState.Idle(register = false))
    }

    @Test
    fun `a too short password stays refused with no callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        handler.submit(typed("short"))
        assertTrue(handler.current() is AuthState.Refused)
        assertEquals(false, handler.current().register)
        assertEquals(0, calls)
    }

    @Test
    fun `a too short submit zero-fills the array without a callback`() {
        val short = typed("short")
        val handler = AuthIntentHandler { _, _, _, _ -> throw AssertionError("callback fired") }
        handler.submit(short)
        for (index in short.indices) {
            assertEquals('\u0000', short[index])
        }
    }

    @Test
    fun `the submit callback gets the username, the typed password and the face chosen`() {
        var gotUsername: String? = null
        var gotPassword = ""
        var gotRegister: Boolean? = null
        val handler = AuthIntentHandler { name, password, register, _ ->
            gotUsername = name
            gotPassword = String(password)
            gotRegister = register
        }
        handler.username = "alice"
        handler.tap("face.register")
        handler.submit(typed("q1w2e3r4t5y6"))
        assertEquals("alice", gotUsername)
        assertEquals("q1w2e3r4t5y6", gotPassword)
        assertEquals(true, gotRegister)
    }

    @Test
    fun `a refused, weak or ok submit keeps the face it was sent from`() {
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.tap("face.register")
        handler.submit(typed("short"))
        assertEquals(true, handler.current().register)
        handler.submit(typed("qwertyuiop12"))
        assertEquals(true, handler.current().register)
        handler.submitted()
        handler.submit(typed("q1w2e3r4t5y6"))
        assertEquals(true, handler.current().register)
        handler.tap("face.login")
        handler.submit(typed("short"))
        assertEquals(false, handler.current().register)
    }

    @Test
    fun `a weak password warns and fires the callback exactly once`() {
        var fired = 0
        val handler = AuthIntentHandler { _, _, _, _ -> fired++ }
        handler.submit(typed("qwertyuiop12"))  // a real COMMON entry, meets the length rule
        assertTrue(handler.current() is AuthState.Warning)
        assertEquals(false, handler.current().register)
        assertEquals(1, fired)
    }

    @Test
    fun `a weak submit stays warning until submitted returns to idle`() {
        val weak = CharArray(12).apply { fill('a') }
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.submit(weak)
        assertTrue(handler.current() is AuthState.Warning)
        handler.submitted()
        assertEquals(AuthState.Idle(register = false), handler.current())
    }

    @Test
    fun `an ok password goes busy and fires the callback once`() {
        var fired = 0
        val handler = AuthIntentHandler { _, _, _, _ -> fired++ }
        handler.submit(typed("password123456"))  // ok: passes length, not a common entry
        assertTrue(handler.current() is AuthState.Busy)
        assertEquals(1, fired)
    }

    @Test
    fun `an ok submit stays busy until submitted returns to idle`() {
        val ok = typed("correcthorse")
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.submit(ok)
        assertTrue(handler.current() is AuthState.Busy)
        handler.submitted()
        assertEquals(AuthState.Idle(register = false), handler.current())
    }

    @Test
    fun `the password array is zero-filled after submit`() {
        val password = CharArray(12) { 'x' }
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.submit(password)
        for (index in password.indices) {
            assertEquals('\u0000', password[index])
        }
    }

    @Test
    fun `the callback fires once and the array is zero-filled even when the submit throws`() {
        var fired = 0
        val password = typed("password123456")
        val throwing = AuthIntentHandler { _, _, _, _ -> fired++; check(false) }
        assertThrows(IllegalStateException::class.java) { throwing.submit(password) }
        assertEquals(1, fired)
        for (index in password.indices) {
            assertEquals('\u0000', password[index])
        }
    }

    @Test
    fun `second submit while busy fires no callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        val ok = typed("q1w2e3r4t5y6")
        handler.submit(ok)
        assertTrue(handler.current() is AuthState.Busy)
        assertEquals(1, calls)
        val fresh = typed("bearwolf1234")
        handler.submit(fresh)
        assertEquals(1, calls)
        assertTrue(handler.current() is AuthState.Busy)
        for (index in fresh.indices) {
            assertEquals('\u0000', fresh[index])
        }
    }

    @Test
    fun `same array twice while busy fires callback once`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        val ok = typed("q1w2e3r4t5y6")
        handler.submit(ok)
        assertTrue(handler.current() is AuthState.Busy)
        assertEquals(1, calls)
        handler.submit(ok)
        assertEquals(1, calls)
        assertTrue(handler.current() is AuthState.Busy)
    }

    @Test
    fun `second submit while warning fires no callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        val weak = CharArray(12) { 'a' }
        handler.submit(weak)
        assertTrue(handler.current() is AuthState.Warning)
        assertEquals(1, calls)
        val fresh = typed("q1w2e3r4t5y6")
        handler.submit(fresh)
        assertEquals(1, calls)
        assertTrue(handler.current() is AuthState.Warning)
    }

    @Test
    fun `after submitted a new ok array fires callback again`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        val ok = typed("q1w2e3r4t5y6")
        handler.submit(ok)
        assertTrue(handler.current() is AuthState.Busy)
        handler.submitted()
        assertEquals(AuthState.Idle(register = false), handler.current())
        handler.submit(typed("m7n8p2q3r4s5"))
        assertEquals(2, calls)
        assertTrue(handler.current() is AuthState.Busy)
    }

    @Test
    fun `submit after refused is rated normally`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        handler.submit(typed("short"))
        assertTrue(handler.current() is AuthState.Refused)
        assertEquals(0, calls)
        handler.submit(typed("q1w2e3r4t5y6"))
        assertEquals(1, calls)
        assertTrue(handler.current() is AuthState.Busy)
    }

    @Test
    fun `a register submit hands the trimmed install code to the callback`() {
        var got: String? = "unset"
        val handler = AuthIntentHandler { _, _, _, code -> got = code }
        handler.tap("face.register")
        handler.installCode = "  code-123  "
        handler.submit(CharArray(12) { 'a' })
        assertEquals(true, handler.current().register)
        assertEquals("code-123", got)
    }

    @Test
    fun `a blank and a whitespace-only install code both hand null`() {
        var got: String? = "unset"
        val handler = AuthIntentHandler { _, _, _, code -> got = code }
        handler.tap("face.register")
        handler.installCode = ""
        handler.submit(CharArray(12) { 'a' })
        assertTrue(got === null)
        handler.submitted()
        handler.installCode = "   "
        handler.submit(CharArray(12) { 'a' })
        assertTrue(got === null)
    }

    @Test
    fun `a sign-in submit hands null even when a code is held`() {
        var got: String? = "unset"
        val handler = AuthIntentHandler { _, _, _, code -> got = code }
        handler.tap("face.login")
        handler.installCode = "code-123"
        handler.submit(CharArray(12) { 'a' })
        assertEquals(false, handler.current().register)
        assertTrue(got === null)
    }

    /** The three strings a leak could reach: the handler's label, the state's label and the drawn line. */
    private fun assertNoCodeAnywhere(handler: AuthIntentHandler, code: String) {
        assertFalse("the handler's own label carries the code", handler.toString().contains(code))
        assertFalse("the state's label carries the code", handler.current().toString().contains(code))
        assertFalse("the drawn line carries the code", handler.current().line().contains(code))
    }

    @Test
    fun `a refused submit leaks no install code into the handler or its state`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _, _ -> calls++ }
        handler.tap("face.register")
        handler.installCode = "code-123"
        handler.submit(typed("short"))
        assertEquals(0, calls)
        assertTrue(handler.current() is AuthState.Refused)
        assertNoCodeAnywhere(handler, "code-123")
    }

    @Test
    fun `a submit that fires the callback leaks no install code afterwards`() {
        val handler = AuthIntentHandler { _, _, _, _ -> }
        handler.tap("face.register")
        handler.installCode = "code-123"
        handler.submit(CharArray(12) { 'a' })  // weak: the callback fires exactly once
        assertTrue(handler.current() is AuthState.Warning)
        assertNoCodeAnywhere(handler, "code-123")
        handler.submitted()
        assertNoCodeAnywhere(handler, "code-123")
    }

    @Test
    fun `a throwing callback leaks no install code into the handler or its state`() {
        val handler = AuthIntentHandler { _, _, _, _ -> throw IllegalStateException("fixture failure") }
        handler.tap("face.register")
        handler.installCode = "code-123"
        assertThrows(IllegalStateException::class.java) { handler.submit(CharArray(12) { 'a' }) }
        assertNoCodeAnywhere(handler, "code-123")
    }

}

private fun assertSameState(actual: AuthState, expected: AuthState) =
    assertEquals(expected, actual)
