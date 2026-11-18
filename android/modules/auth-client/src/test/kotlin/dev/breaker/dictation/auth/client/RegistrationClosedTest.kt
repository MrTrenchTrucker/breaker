package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A register refused with 403 is its own reason; the neighbouring statuses keep theirs. */
class RegistrationClosedTest {
    private fun registerReason(rig: Rig): AuthFailureReason =
        assertThrows(AuthFailure::class.java) { rig.service().register("ada", testPassword()) }.reason

    @Test
    fun `a 403 on register gives registration closed with its fixed sentence`() {
        val rig = Rig()
        rig.http.answer(403)
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().register("ada", testPassword()) }
        assertEquals(AuthFailureReason.REGISTRATION_CLOSED, thrown.reason)
        assertEquals(AuthFailure(AuthFailureReason.REGISTRATION_CLOSED).message, thrown.message)
        assertEquals(1, rig.http.requests.size)
        assertEquals(REGISTER_URL, rig.http.requests[0].url)
    }

    @Test
    fun `a 400 on register still gives server error`() {
        val rig = Rig()
        rig.http.answer(400)
        assertEquals(AuthFailureReason.SERVER_ERROR, registerReason(rig))
        assertEquals(1, rig.http.requests.size)
    }

    @Test
    fun `a 409 on register still gives username taken`() {
        val rig = Rig()
        rig.http.answer(409)
        assertEquals(AuthFailureReason.USERNAME_TAKEN, registerReason(rig))
    }

    @Test
    fun `a 429 on register still gives rate limited`() {
        val rig = Rig()
        rig.http.answer(429)
        assertEquals(AuthFailureReason.RATE_LIMITED, registerReason(rig))
    }
}
