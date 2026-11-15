package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthFailure
import dev.breaker.dictation.auth.client.AuthFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason-to-shown-line channel: only a locked name has a line, and the line it carries is the
 * failure's own fixed sentence; every other reason has none.
 */
class AuthFailureLineTest {

    @Test
    fun `a locked name is the only reason with a line and carries its own sentence`() {
        for (reason in AuthFailureReason.values()) {
            val failure = AuthFailure(reason, 90)
            val line = shownLineFor(failure)
            if (reason == AuthFailureReason.NAME_LOCKED) {
                assertNotNull("android_app: a locked name must carry a line", line)
                assertEquals("android_app: the line is the failure's own sentence", failure.message, line)
            } else {
                assertNull("android_app: reason " + reason + " must carry no line", line)
            }
        }
    }
}
