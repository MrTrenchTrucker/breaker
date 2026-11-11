package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Only a 429 carries the server's Retry-After seconds: every other refusal leaves the number null. */
class ServerOnlyTooManyCarriesRetryAfterTest {
    private val statuses = listOf(400, 401, 403, 404, 409, 422, 500)
    private val asked = listOf("Retry-After" to "30")

    @Test
    fun `a refusal on the salt request other than 429 carries no number`() {
        for (status in statuses) {
            val rig = Rig()
            rig.http.answer(status, "", headers = asked)
            val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
            assertNull("android_auth_client: salt status " + status, thrown.retryAfterSeconds)
        }
    }

    @Test
    fun `a refusal on the login request other than 429 carries no number`() {
        for (status in statuses) {
            val rig = Rig()
            rig.answerSaltOk()
            rig.http.answer(status, "", headers = asked)
            val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
            assertNull("android_auth_client: login status " + status, thrown.retryAfterSeconds)
        }
    }

    @Test
    fun `a refusal on the register request other than 429 carries no number`() {
        for (status in statuses) {
            val rig = Rig()
            rig.http.answer(status, "", headers = asked)
            val thrown = assertThrows(AuthFailure::class.java) { rig.service().register("ada", testPassword()) }
            assertNull("android_auth_client: register status " + status, thrown.retryAfterSeconds)
        }
    }

    @Test
    fun `a 404 on salt with a Retry-After header is no such account with no number`() {
        val rig = Rig()
        rig.http.answer(404, "", headers = asked)
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(AuthFailureReason.NO_SUCH_ACCOUNT, thrown.reason)
        assertNull("android_auth_client: a 404 carries no number", thrown.retryAfterSeconds)
    }

    @Test
    fun `a 409 on register with a Retry-After header is username taken with no number`() {
        val rig = Rig()
        rig.http.answer(409, "", headers = asked)
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().register("ada", testPassword()) }
        assertEquals(AuthFailureReason.USERNAME_TAKEN, thrown.reason)
        assertNull("android_auth_client: a 409 carries no number", thrown.retryAfterSeconds)
    }
}
