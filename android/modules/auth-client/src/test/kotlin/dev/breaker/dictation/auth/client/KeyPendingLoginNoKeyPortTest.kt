package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A login the server answers with 401 never reaches a key port, because the status is checked before
 * any key work. A failure here means key work now runs before the login reply is accepted.
 */
class KeyPendingLoginNoKeyPortTest {
    /** The count of key port calls in the log: not an http request and not the session save. */
    private fun keyCalls(kr: KeyRig): Int = kr.log.count { !it.startsWith("http ") && it != "session.save" }

    /**
     * A 401 login, with an empty body and with a body that looks keyed, touches no key fake and stores
     * nothing. A failure here means a key port was reached, or something was stored, before the status
     * of the login reply was checked.
     */
    @Test
    fun `a login answered 401 reaches no key port and stores nothing`() {
        val bodies = listOf("empty body" to "", "keyed body" to keyedLoginBody("tok-401", 3))
        for ((case, body) in bodies) {
            val kr = KeyRig()
            kr.rig.answerSaltOk()
            kr.http.answer(401, body)
            val failure = kr.failure()
            assertEquals(case + ": failure reason", AuthFailureReason.BAD_CREDENTIALS, failure.reason)
            val expected = listOf("http POST " + SALT_URL, "http POST " + LOGIN_URL)
            assertEquals(case + ": call log", expected, kr.log)
            assertEquals(case + ": urls", listOf(SALT_URL, LOGIN_URL), kr.urls())
            assertEquals(case + ": credential vault calls", emptyList<String>(), kr.credentials.calls)
            assertTrue(case + ": data keys made", kr.accountKeys.deks.isEmpty())
            assertTrue(case + ": box key pairs made", kr.accountKeys.pairs.isEmpty())
            assertTrue(case + ": data keys wrapped", kr.accountKeys.wrappedDeks.isEmpty())
            assertTrue(case + ": box keys wrapped", kr.accountKeys.wrappedBoxes.isEmpty())
            assertTrue(case + ": box keys opened", kr.accountKeys.unwrappedBoxes.isEmpty())
            assertTrue(case + ": box wraps seen", kr.accountKeys.boxWrapsSeen.isEmpty())
            assertTrue(case + ": public keys seen", kr.accountKeys.publicSeen.isEmpty())
            assertTrue(case + ": crypto wraps seen", kr.crypto.wrappedSeen.isEmpty())
            assertTrue(case + ": crypto keys made", kr.crypto.deks.isEmpty())
            assertNull(case + ": session stored", kr.sessions.stored)
            assertNull(case + ": keys stored", kr.credentials.readKeys())
        }
    }

    /**
     * The control: a login answered 200 with no keys does reach the key port and stores the keys. A
     * failure here means the fakes are unwired or unobservable, so the 401 test above proves nothing.
     */
    @Test
    fun `a login answered 200 without keys does reach the key port`() {
        val kr = KeyRig()
        kr.queueBootstrap()
        kr.login()
        assertTrue("generateDataKey is missing from " + kr.log, kr.log.contains("generateDataKey"))
        assertTrue("keys.saveKeys is missing from " + kr.log, kr.log.contains("keys.saveKeys"))
        assertEquals("data keys made", 1, kr.accountKeys.deks.size)
        assertTrue("vault calls " + kr.credentials.calls, kr.credentials.calls.contains("saveKeys"))
        assertKeysStored(kr, 1)
    }

    /**
     * The two arms are told apart by the same observable: the 401 arm makes no key port call and the
     * 200 arm makes some. A failure here means a 401 reached a key port, or the observable cannot see
     * a key port call.
     */
    @Test
    fun `a login answered 401 and a login answered 200 differ in the key port calls`() {
        val refused = KeyRig()
        refused.rig.answerSaltOk()
        refused.http.answer(401)
        refused.failure()
        val accepted = KeyRig()
        accepted.queueBootstrap()
        accepted.login()
        assertEquals("401 arm: key port calls, log " + refused.log, 0, keyCalls(refused))
        assertTrue("200 arm: no key port call, log " + accepted.log, keyCalls(accepted) > 0)
    }
}
