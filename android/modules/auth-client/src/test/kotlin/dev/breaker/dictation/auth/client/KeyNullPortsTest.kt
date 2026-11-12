package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** With no key ports a login behaves as it did before the key step existed. */
class KeyNullPortsTest {
    /** The fakes are built but not wired: nothing may reach them. */
    private fun assertUntouched(kr: KeyRig, case: String) {
        assertEquals(case + ": credential vault calls", emptyList<String>(), kr.credentials.calls)
        assertEquals(case + ": data keys made", 0, kr.accountKeys.deks.size)
        assertEquals(case + ": box key pairs made", 0, kr.accountKeys.pairs.size)
        assertEquals(case + ": crypto fake opened", 0, kr.crypto.wrappedSeen.size)
    }

    @Test
    fun `a keyless login with no ports sends salt and login only and saves the session`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        val session = kr.service(withPorts = false).login("ada", testPassword())
        assertEquals(listOf(SALT_URL, LOGIN_URL), kr.urls())
        val expected = listOf("http POST " + SALT_URL, "http POST " + LOGIN_URL, "session.save")
        assertEquals("keyless call log", expected, kr.log)
        assertEquals(listOf("load", "save"), kr.sessions.calls)
        assertNotNull(kr.sessions.stored)
        assertEquals("tok-1", session.token)
        assertEquals("derive count observed " + kr.derive.calls.size, 1, kr.derive.calls.size)
        assertUntouched(kr, "keyless")
    }

    @Test
    fun `a login reply with keys and no ports is not unwrapped`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.http.answer(200, keyedLoginBody("tok-k", 3))
        val session = kr.service(withPorts = false).login("ada", testPassword())
        assertEquals(listOf(SALT_URL, LOGIN_URL), kr.urls())
        val expected = listOf("http POST " + SALT_URL, "http POST " + LOGIN_URL, "session.save")
        assertEquals("keyed call log", expected, kr.log)
        assertEquals(listOf("load", "save"), kr.sessions.calls)
        assertEquals("tok-k", session.token)
        assertEquals("derive count observed " + kr.derive.calls.size, 1, kr.derive.calls.size)
        assertUntouched(kr, "keyed")
    }
}
