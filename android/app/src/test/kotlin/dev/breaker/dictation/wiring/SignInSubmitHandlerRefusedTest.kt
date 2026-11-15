package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthFailure
import dev.breaker.dictation.auth.client.AuthFailureReason
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.ui.AuthControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submit handler's reason-to-shown-line channel: a locked name reaches the control as a refused
 * line, every other failure keeps the old return to idle, and the password is zeroed on every path.
 */
class SignInSubmitHandlerRefusedTest {

    /** An [AuthService] that throws [failure] on login and records the array it was handed. */
    private class FailingAuth(private val failure: Exception) : AuthService {
        var recorded: CharArray? = null

        override fun register(username: String, password: CharArray): AuthSession = throw failure

        override fun login(username: String, password: CharArray): AuthSession {
            recorded = password
            throw failure
        }

        override fun logout() {}

        override fun currentSession(): AuthSession? = null
    }

    /** A control that records the refused line and face, and the submitted calls. */
    private class RecordingControl : AuthControl {
        var refusedLine: String? = null
        var refusedRegister: Boolean? = null
        var submittedCount = 0

        override fun refresh() {}

        override fun submitted(register: Boolean) {
            submittedCount += 1
        }

        override fun refused(register: Boolean, text: String) {
            refusedRegister = register
            refusedLine = text
        }
    }

    /** Runs every block at once, on the calling thread. */
    private class InlineBackground : Background {
        override fun submit(block: () -> Unit) = block()
    }

    /** Runs every posted block at once. */
    private class InlineMainPost : MainPost {
        override fun post(block: () -> Unit) = block()
    }

    private fun harness(failure: Exception): Triple<SignInSubmitHandler, RecordingControl, FailingAuth> {
        val auth = FailingAuth(failure)
        val control = RecordingControl()
        val handler = SignInSubmitHandler(auth, SessionLiveState(false), InlineMainPost(), InlineBackground())
        handler.authControl = control
        return Triple(handler, control, auth)
    }

    @Test
    fun `a locked name reaches the control as a refused line and not as a return to idle`() {
        val failure = AuthFailure(AuthFailureReason.NAME_LOCKED)
        val (handler, control, _) = harness(failure)
        handler.submit("ada", charArrayOf('p', 'a', 's'), false)
        assertEquals(false, control.refusedRegister)
        assertNotNull(control.refusedLine)
        assertEquals("the refused line is the failure's own message", failure.message, control.refusedLine)
        assertEquals(0, control.submittedCount)
    }

    @Test
    fun `a failure with no line of its own returns to idle as before`() {
        val (handler, control, _) = harness(AuthFailure(AuthFailureReason.BAD_CREDENTIALS, null))
        handler.submit("ada", charArrayOf('p', 'a', 's'), false)
        assertNull(control.refusedLine)
        assertEquals(1, control.submittedCount)
    }

    @Test
    fun `a plain exception returns to idle as before`() {
        val (handler, control, _) = harness(IllegalStateException("no network"))
        handler.submit("ada", charArrayOf('p', 'a', 's'), false)
        assertNull(control.refusedLine)
        assertEquals(1, control.submittedCount)
    }

    @Test
    fun `the password is zeroed on the refused path too`() {
        val (handler, _, auth) = harness(AuthFailure(AuthFailureReason.NAME_LOCKED))
        val password = charArrayOf('p', 'a', 's')
        handler.submit("ada", password, false)
        assertTrue(password.contentEquals(charArrayOf('\u0000', '\u0000', '\u0000')))
        assertNotNull(auth.recorded)
    }
}
