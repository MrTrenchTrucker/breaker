package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A bad username is refused before any request is sent, with its own sentence, and the password array is still wiped. */
class ServerUsernameRefusalTest {
    private val sentence = "A username may use only letters, digits, dots, hyphens and underscores, up to 64 characters."

    @Test
    fun `register with a space in the username is refused with its own reason and no request`() {
        val rig = Rig()
        val failure = assertThrows(AuthFailure::class.java) { rig.service().register("a b", testPassword()) }
        assertEquals(AuthFailureReason.BAD_USERNAME, failure.reason)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `login with a bang in the username is refused with its own reason and no request`() {
        val rig = Rig()
        val failure = assertThrows(AuthFailure::class.java) { rig.service().login("a!", testPassword()) }
        assertEquals(AuthFailureReason.BAD_USERNAME, failure.reason)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `the refusal message is the plain sentence of the reason`() {
        val rig = Rig()
        val failure = assertThrows(AuthFailure::class.java) { rig.service().login("a b", testPassword()) }
        assertEquals(sentence, failure.message)
    }

    @Test
    fun `the password array is zeroed after a refused username`() {
        val rig = Rig()
        val password = testPassword()
        val size = password.size
        assertThrows(AuthFailure::class.java) { rig.service().login("a!", password) }
        assertEquals(size, password.size)
        assertTrue("the array handed over must be all zeros", password.all { it == '\u0000' })
    }
}
