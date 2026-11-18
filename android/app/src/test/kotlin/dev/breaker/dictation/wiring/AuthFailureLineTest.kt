package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthFailure
import dev.breaker.dictation.auth.client.AuthFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason-to-shown-line channel: a locked name and a closed registration have a line, and the line
 * each carries is the failure's own fixed sentence; every other reason has none.
 */
class AuthFailureLineTest {

    @Test
    fun `a locked name and a closed registration are the only reasons with a line and carry their own sentence`() {
        for (reason in AuthFailureReason.values()) {
            val failure = AuthFailure(reason, 90)
            val line = shownLineFor(failure)
            if (reason == AuthFailureReason.NAME_LOCKED || reason == AuthFailureReason.REGISTRATION_CLOSED) {
                assertNotNull("android_app: a locked name or a closed registration must carry a line", line)
                assertEquals("android_app: the line is the failure's own sentence", failure.message, line)
            } else {
                assertNull("android_app: reason " + reason + " must carry no line", line)
            }
        }
    }
}
