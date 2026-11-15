package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The locked-name answer of login: a 423 is NAME_LOCKED and carries no seconds, even when the reply
 * has a Retry-After header, because the phone ignores the wait on purpose. Only login decodes 423,
 * and the refusal stores no session and touches no key port.
 */
class ServerLoginNameLockedTest {
    /** Queues a good salt, then a 423 login reply with [headers], and returns the failure login throws. */
    private fun nameLockedFailure(headers: List<Pair<String, String>>): AuthFailure {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(423, "", headers = headers)
        return assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
    }

    @Test
    fun `a 423 on login gives NAME_LOCKED and the phone ignores Retry-After`() {
        val thrown = nameLockedFailure(listOf("Retry-After" to "90"))
        assertEquals(AuthFailureReason.NAME_LOCKED, thrown.reason)
        assertNull(thrown.retryAfterSeconds)
    }

    @Test
    fun `a 401 on login is still BAD_CREDENTIALS and carries no seconds`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(401, "", headers = listOf("Retry-After" to "90"))
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(AuthFailureReason.BAD_CREDENTIALS, thrown.reason)
        assertNull(thrown.retryAfterSeconds)
    }

    @Test
    fun `a 429 on login is still RATE_LIMITED and carries its seconds`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(429, "", headers = listOf("Retry-After" to "90"))
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(AuthFailureReason.RATE_LIMITED, thrown.reason)
        assertEquals(90, thrown.retryAfterSeconds)
    }

    /** Login only: a 423 on the salt request stays a server error, with no seconds. */
    @Test
    fun `a 423 on salt is still SERVER_ERROR and carries no seconds`() {
        val rig = Rig()
        rig.http.answer(423, "", headers = listOf("Retry-After" to "90"))
        val thrown = assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
        assertNull(thrown.retryAfterSeconds)
    }

    /** A 423 leaves the stored session and every key port untouched; only the verifier derivation ran. */
    @Test
    fun `a 423 on login stores no session and touches no key port`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.http.answer(423, "", headers = listOf("Retry-After" to "90"))
        val thrown = assertThrows(AuthFailure::class.java) { kr.service().login("ada", testPassword()) }
        assertEquals(AuthFailureReason.NAME_LOCKED, thrown.reason)
        assertNull(thrown.retryAfterSeconds)
        assertEquals(listOf(SALT_URL, LOGIN_URL), kr.urls())
        assertEquals(emptyList<String>(), kr.sessions.calls)
        assertEquals(emptyList<String>(), kr.credentials.calls)
        assertEquals(0, kr.accountKeys.deks.size)
        assertEquals(0, kr.accountKeys.pairs.size)
        assertEquals(0, kr.crypto.wrappedSeen.size)
        assertEquals(1, kr.derive.calls.size)
        assertWiped(kr.derive)
    }
}
