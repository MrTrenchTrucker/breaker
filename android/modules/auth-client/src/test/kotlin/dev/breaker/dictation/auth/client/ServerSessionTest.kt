package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** The stored session: reading it back, and what a failing vault leaves behind. */
class ServerSessionTest {
    private val previousRecord =
        "{\"user_id\":\"old\",\"username\":\"old\",\"token\":\"tok-old\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://box.example\"}"

    @Test
    fun `current session is null when nothing is stored and sends no request`() {
        val rig = Rig()
        assertNull(rig.service().currentSession())
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `current session returns the stored session`() {
        val rig = Rig()
        rig.vault.stored =
            "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-9\",\"role\":\"admin\",\"scopes\":[\"transcribe\",\"admin\"],\"server\":\"https://box.example\"}"
        assertEquals(
            AuthSession("ada", "ada", "tok-9", UserRole.ADMIN, setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN)),
            rig.service().currentSession(),
        )
    }

    @Test
    fun `current session is null for a corrupt record`() {
        for (text in listOf("", "{}", "not a record")) {
            val rig = Rig()
            rig.vault.stored = text
            assertNull(rig.service().currentSession())
        }
    }

    @Test
    fun `an unreadable vault gives no session`() {
        val vault = FailingVault(FakeSessionVault())
        val failure = IllegalStateException("fake read refused")
        vault.failLoad = failure
        val rig = Rig()
        assertNull(rig.service(store = vault).currentSession())
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a vault save failure after login sends a logout with that token and rethrows`() {
        val rig = Rig()
        val refusal = FakeVaultRefusal()
        rig.vault.saveFailure = refusal
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-1")
        rig.http.failWith(IOException("fake connection refused"))
        val thrown = assertThrows(FakeVaultRefusal::class.java) { rig.service().login("ada", testPassword()) }
        assertSame(refusal, thrown)
        assertEquals(3, rig.http.requests.size)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals("Bearer tok-1", headerValue(rig.http.requests[2], "Authorization"))
        assertNull(rig.vault.stored)
    }

    @Test
    fun `a vault save failure clears the vault`() {
        val rig = Rig()
        rig.vault.saveFailure = FakeVaultRefusal()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.http.answer(200)
        assertThrows(FakeVaultRefusal::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(listOf("load", "save", "clear"), rig.vault.calls)
    }

    @Test
    fun `a vault save failure clears an existing stored session`() {
        val rig = Rig()
        rig.vault.stored = previousRecord
        rig.vault.saveFailure = FakeVaultRefusal()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.http.answer(200)
        rig.http.answer(200)
        assertThrows(FakeVaultRefusal::class.java) { rig.service().login("ada", testPassword()) }
        assertNull(rig.vault.stored)
        assertEquals(4, rig.http.requests.size)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals("Bearer tok-old", headerValue(rig.http.requests[2], "Authorization"))
        assertEquals(LOGOUT_URL, rig.http.requests[3].url)
        assertEquals("Bearer tok-1", headerValue(rig.http.requests[3], "Authorization"))
    }

    @Test
    fun `a failed login leaves an existing stored session untouched`() {
        val rig = Rig()
        rig.vault.stored = previousRecord
        rig.answerSaltOk()
        rig.http.answer(401)
        assertThrows(AuthFailure::class.java) { rig.service().login("ada", testPassword()) }
        assertEquals(previousRecord, rig.vault.stored)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }
}
