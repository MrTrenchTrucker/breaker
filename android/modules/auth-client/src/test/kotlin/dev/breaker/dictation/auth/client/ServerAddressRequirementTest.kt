package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session is only returned, and only signed out, at the server address it was made with. */
class ServerAddressRequirementTest {
    private val ada = AuthSession("ada", "ada", "tok-out", UserRole.USER, setOf(TokenScope.TRANSCRIBE))

    private fun rigWithStoredSession(address: String): Rig {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.base = address
        return rig
    }

    @Test
    fun `a stored session is returned when the current address is the same text`() {
        val rig = rigWithStoredSession(BASE_URL)
        assertEquals(ada, rig.service().currentSession())
    }

    @Test
    fun `a stored session is returned when the current address differs only by case and a trailing slash`() {
        val rig = rigWithStoredSession("HTTPS://Box.Example/")
        assertEquals(ada, rig.service().currentSession())
    }

    @Test
    fun `no session is returned when the current address is another server`() {
        val others = listOf(
            "https://other.example",
            "https://box.example:8443",
            "https://box.example/sub",
            "http://box.example",
        )
        for (address in others) {
            val rig = rigWithStoredSession(address)
            assertNull("address " + address, rig.service().currentSession())
            assertFalse("address " + address, rig.vault.calls.contains("clear"))
            assertFalse("address " + address, rig.vault.calls.contains("save"))
            assertEquals("address " + address, ADA_RECORD, rig.vault.stored)
            assertEquals("address " + address, 0, rig.http.requests.size)
        }
    }

    @Test
    fun `no session is returned when the current address is cleared`() {
        val rig = rigWithStoredSession("")
        assertNull(rig.service().currentSession())
        assertFalse(rig.vault.calls.contains("clear"))
        assertFalse(rig.vault.calls.contains("save"))
        assertEquals(ADA_RECORD, rig.vault.stored)
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `sign-out at a changed address sends nothing and still clears the vault`() {
        val rig = rigWithStoredSession("https://other.example")
        rig.service().logout()
        assertEquals(0, rig.http.requests.size)
        assertNull(rig.vault.stored)
        assertTrue(rig.vault.calls.contains("clear"))
    }

    @Test
    fun `sign-out at a cleared address sends nothing and still clears the vault`() {
        val rig = rigWithStoredSession("")
        rig.service().logout()
        assertEquals(0, rig.http.requests.size)
        assertNull(rig.vault.stored)
        assertTrue(rig.vault.calls.contains("clear"))
    }

    @Test
    fun `sign-out at the same address sends the stored bearer once to that address`() {
        for (address in listOf(BASE_URL, "HTTPS://BOX.EXAMPLE/")) {
            val rig = rigWithStoredSession(address)
            rig.http.answer(200)
            rig.service().logout()
            val request = rig.http.requests.single()
            assertEquals("address " + address, "POST", request.method)
            assertEquals("address " + address, address.removeSuffix("/") + "/v1/auth/logout", request.url)
            assertEquals(
                "address " + address,
                listOf("Authorization" to "Bearer tok-out"),
                request.headers,
            )
            assertNull("address " + address, rig.vault.stored)
        }
    }

    @Test
    fun `a login stores the address exactly as typed`() {
        val typed = "https://Box.Example:8443/sub"
        val rig = Rig()
        rig.base = typed
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        val stored = SessionRecord.decode(rig.vault.stored!!)!!
        assertEquals(typed, stored.server)
        assertEquals(typed + "/v1/auth/login", rig.http.requests[1].url)
    }

    @Test
    fun `a login stores a trailing slash and the case exactly as typed`() {
        val typed = "https://Box.Example/"
        val rig = Rig()
        rig.base = typed
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(typed, SessionRecord.decode(rig.vault.stored!!)!!.server)
        assertEquals("https://Box.Example/v1/auth/salt", rig.http.requests[0].url)
        assertEquals("https://Box.Example/v1/auth/login", rig.http.requests[1].url)
    }

    @Test
    fun `a login over a plain http address works and stores that address`() {
        val typed = "http://box.example"
        val rig = Rig()
        rig.base = typed
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(2, rig.http.requests.size)
        for (request in rig.http.requests) {
            assertTrue(request.url, request.url.startsWith("http://box.example/v1/auth/"))
        }
        assertEquals(typed, SessionRecord.decode(rig.vault.stored!!)!!.server)
    }

    @Test
    fun `a base ending in two slashes is refused and nothing is sent`() {
        val rig = Rig()
        rig.base = "https://box.example//"
        val loginFailure = assertThrows(AuthFailure::class.java) {
            rig.service().login("ada", testPassword())
        }
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, loginFailure.reason)
        val registerFailure = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", testPassword())
        }
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, registerFailure.reason)
        assertEquals(0, rig.http.requests.size)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }

    @Test
    fun `a base holding a backslash is refused and nothing is sent`() {
        val rig = Rig()
        rig.base = "https://box.example\\sub"
        val loginFailure = assertThrows(AuthFailure::class.java) {
            rig.service().login("ada", testPassword())
        }
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, loginFailure.reason)
        val registerFailure = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", testPassword())
        }
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, registerFailure.reason)
        assertEquals(0, rig.http.requests.size)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }

    @Test
    fun `a record that is too long to store revokes the token and rethrows`() {
        val longBearer = "b".repeat(17000)
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        rig.answerSaltOk()
        rig.answerLoginOk(longBearer)
        rig.http.answer(200)
        rig.http.answer(200)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            rig.service().login("ada", testPassword())
        }
        assertEquals("the session record is too long", failure.message)
        assertEquals(4, rig.http.requests.size)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals("Bearer tok-out", headerValue(rig.http.requests[2], "Authorization"))
        assertEquals(LOGOUT_URL, rig.http.requests[3].url)
        assertEquals("Bearer " + longBearer, headerValue(rig.http.requests[3], "Authorization"))
        assertNull(rig.vault.stored)
        assertFalse(rig.vault.calls.contains("save"))
    }
}
