package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFailureTest {
    private val sentences = listOf(
        AuthFailureReason.BAD_CREDENTIALS to "The username or password was not accepted.",
        AuthFailureReason.NO_SUCH_ACCOUNT to "No account with that username exists.",
        AuthFailureReason.USERNAME_TAKEN to "That username is already taken.",
        AuthFailureReason.WEAK_PASSWORD to "The password is too short.",
        AuthFailureReason.SERVER_UNREACHABLE to "The server could not be reached.",
        AuthFailureReason.SERVER_ERROR to "The server could not complete the request.",
        AuthFailureReason.UNREADABLE_REPLY to "The server's reply could not be read.",
        AuthFailureReason.BAD_SERVER_ADDRESS to "The server address must start with https://.",
    )

    @Test
    fun `each failure reason carries the fixed sentence written for it`() {
        for (case in sentences) {
            assertEquals("the sentence for ${case.first.name} changed", case.second, AuthFailure(case.first).message)
        }
    }

    @Test
    fun `the failure has exactly eight reasons, one for each account outcome`() {
        assertEquals(
            "the failure reasons changed",
            listOf(
                "BAD_CREDENTIALS",
                "NO_SUCH_ACCOUNT",
                "USERNAME_TAKEN",
                "WEAK_PASSWORD",
                "SERVER_UNREACHABLE",
                "SERVER_ERROR",
                "UNREADABLE_REPLY",
                "BAD_SERVER_ADDRESS",
            ),
            AuthFailureReason.entries.map { it.name },
        )
    }

    @Test
    fun `a failure has no cause attached`() {
        for (case in sentences) {
            assertNull("a cause was attached to ${case.first.name}", AuthFailure(case.first).cause)
        }
    }

    @Test
    fun `a failure text never repeats the reason name or any input`() {
        for (case in sentences) {
            val text = AuthFailure(case.first).message ?: ""
            assertTrue("the failure text repeated ${case.first.name}", !text.contains(case.first.name))
        }
    }

    @Test
    fun `a failure keeps the reason it was built with`() {
        for (case in sentences) {
            assertEquals("the failure lost its reason", case.first, AuthFailure(case.first).reason)
        }
    }
}
