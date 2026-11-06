package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A successful sign-in: the order of the calls, the bodies they carry, the derivation and what is kept. */
class ServerLoginTest {
    @Test
    fun `login calls the salt request then the derivation then the login request`() {
        val rig = Rig()
        val derivationsSeen = mutableListOf<Int>()
        rig.http.beforeSend = { derivationsSeen.add(rig.keys.calls.size) }
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(listOf(0, 1), derivationsSeen)
        assertEquals(listOf(SALT_URL, LOGIN_URL), rig.http.requests.map { it.url })
    }

    @Test
    fun `the salt request posts the username as the body`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        val salt = rig.http.requests[0]
        assertEquals("POST", salt.method)
        assertEquals("{\"username\":\"ada\"}", bodyText(salt))
    }

    @Test
    fun `the salt request sends only the json content type header`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(listOf("Content-Type" to "application/json"), rig.http.requests[0].headers)
    }

    @Test
    fun `the login request carries the username and the verifier as base64`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        val login = rig.http.requests[1]
        assertEquals("POST", login.method)
        assertEquals(LOGIN_URL, login.url)
        assertEquals(listOf("Content-Type" to "application/json"), login.headers)
        assertEquals(
            "{\"username\":\"ada\",\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"}",
            bodyText(login),
        )
    }

    @Test
    fun `login derives once from the salt and the parameters the reply gives`() {
        val rig = Rig()
        rig.http.answer(200, saltReplyBody(memoryKib = 131072, iterations = 4, parallelism = 2))
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(1, rig.keys.calls.size)
        val call = rig.keys.calls[0]
        assertEquals(TEST_PHRASE_TEXT, call.password)
        assertArrayEquals(FAKE_SALT_BYTES, call.salt)
        assertEquals(KdfParams(131072, 4, 2, 32, 1), call.params)
    }

    @Test
    fun `a base address with one trailing slash sends one clean path to each endpoint`() {
        val rig = Rig()
        rig.base = "https://box.example/"
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(listOf(SALT_URL, LOGIN_URL), rig.http.requests.map { it.url })
    }

    @Test
    fun `the base address is read once for a whole login`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertEquals(1, rig.baseReads)
    }

    @Test
    fun `the admin role text gives the admin role and both scopes`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk(role = "admin")
        val session = rig.service().login("ada", testPassword())
        assertEquals(UserRole.ADMIN, session.role)
        assertEquals(setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN), session.scopes)
    }

    @Test
    fun `the user role text gives the user role and the transcribe scope only`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk(role = "user")
        val session = rig.service().login("ada", testPassword())
        assertEquals(UserRole.USER, session.role)
        assertEquals(setOf(TokenScope.TRANSCRIBE), session.scopes)
    }

    @Test
    fun `a login session uses the username as user id and the token from the reply`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-ada")
        val session = rig.service().login("ada", testPassword())
        assertEquals(
            AuthSession("ada", "ada", "tok-ada", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            session,
        )
    }

    @Test
    fun `the vault receives the encoded session only after the whole login succeeds`() {
        val rig = Rig()
        val vaultSeen = mutableListOf<List<String>>()
        rig.http.beforeSend = { vaultSeen.add(rig.vault.calls.toList()) }
        rig.answerSaltOk()
        rig.answerLoginOk(bearer = "tok-ada")
        rig.service().login("ada", testPassword())
        assertEquals(listOf(emptyList<String>(), emptyList<String>()), vaultSeen)
        assertEquals(listOf("load", "save"), rig.vault.calls)
        assertEquals(
            "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-ada\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://box.example\"}",
            rig.vault.stored,
        )
    }

    @Test
    fun `login keeps only the token and the role from the reply`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.http.answer(
            200,
            "{\"token\":\"tok-ada\",\"role\":\"user\",\"key_version\":4," +
                "\"wrapped_dek\":\"AAAA\",\"wrapped_box_privkey\":\"BBBB\",\"box_pubkey\":\"CCCC\"," +
                "\"prev_wrapped_dek\":\"DDDD\",\"reset_at\":\"2026-01-01T00:00:00Z\"," +
                "\"rekeyed_at\":\"2026-01-02T00:00:00Z\"}",
        )
        rig.service().login("ada", testPassword())
        assertEquals(
            "{\"user_id\":\"ada\",\"username\":\"ada\",\"token\":\"tok-ada\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://box.example\"}",
            rig.vault.stored,
        )
    }

    @Test
    fun `the derived keys are wiped after a successful login`() {
        val rig = Rig()
        rig.answerSaltOk()
        rig.answerLoginOk()
        rig.service().login("ada", testPassword())
        assertWiped(rig.keys)
    }
}
