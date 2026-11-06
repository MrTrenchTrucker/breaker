package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reentrancy on one thread: a call made from inside the port, while the service is in the middle
 * of a call, completes instead of waiting on itself.
 */
class ServerLockTest {
    @Test
    fun `a logout made from inside the login request on the same thread completes`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        val service = rig.service()
        rig.http.beforeSend = { request ->
            if (request.url == LOGIN_URL) service.logout()
        }
        rig.answerSaltOk()
        rig.http.answer(200)
        rig.answerLoginOk(bearer = "tok-login")
        val session: AuthSession = service.login("ada", testPassword())
        assertEquals("tok-login", session.token)
        assertEquals(listOf(SALT_URL, LOGIN_URL, LOGOUT_URL), rig.http.requests.map { it.url })
        assertEquals(SessionRecord.encode(session, BASE_URL), rig.vault.stored)
    }
}
