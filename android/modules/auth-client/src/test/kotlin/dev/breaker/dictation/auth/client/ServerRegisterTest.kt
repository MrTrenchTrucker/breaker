package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefused
import dev.breaker.dictation.core.model.KdfRefusal
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Account creation: the checks before the network, the register body, and the login that follows. */
class ServerRegisterTest {
    private fun registerReason(
        rig: Rig,
        username: String = "ada",
        typed: CharArray = TEST_PHRASE_TEXT.toCharArray(),
    ): AuthFailureReason =
        assertThrows(AuthFailure::class.java) { rig.service().register(username, typed) }.reason

    @Test
    fun `a bad server address stops register before any request is sent`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, registerReason(rig))
        assertEquals(0, rig.http.requests.size)
        assertEquals(0, rig.keys.calls.size)
    }

    @Test
    fun `the address check runs before the blank username check in register`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, registerReason(rig, username = ""))
    }

    @Test
    fun `a blank username is refused before any request in register`() {
        for (name in listOf("", "   ")) {
            val rig = Rig()
            assertEquals(AuthFailureReason.BAD_USERNAME, registerReason(rig, username = name))
            assertEquals(0, rig.http.requests.size)
        }
    }

    @Test
    fun `a password shorter than twelve characters is refused before any request`() {
        val rig = Rig()
        assertEquals(AuthFailureReason.WEAK_PASSWORD, registerReason(rig, typed = "abcdefghijk".toCharArray()))
        assertEquals(0, rig.http.requests.size)
        assertEquals(0, rig.keys.calls.size)
    }

    @Test
    fun `a password of eleven code points is refused even when it has twelve UTF-16 units`() {
        val typed = "a".repeat(10) + "\uD83D\uDE00"
        assertEquals(12, typed.length)
        assertEquals(11, typed.codePointCount(0, typed.length))
        val rig = Rig()
        assertEquals(AuthFailureReason.WEAK_PASSWORD, registerReason(rig, typed = typed.toCharArray()))
        assertEquals(0, rig.http.requests.size)
    }

    @Test
    fun `a password of twelve code points is accepted for register`() {
        val typed = "a".repeat(11) + "\uD83D\uDE00"
        assertEquals(12, typed.codePointCount(0, typed.length))
        val rig = Rig()
        rig.http.answer(409)
        assertEquals(AuthFailureReason.USERNAME_TAKEN, registerReason(rig, typed = typed.toCharArray()))
        assertEquals(REGISTER_URL, rig.http.requests[0].url)
    }

    @Test
    fun `register derives once with the fixed version one parameters`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        rig.service().register("ada", testPassword())
        assertEquals(1, rig.keys.calls.size)
        val call = rig.keys.calls[0]
        assertEquals(TEST_PHRASE_TEXT, call.password)
        assertArrayEquals(FAKE_SALT_BYTES, call.salt)
        assertEquals(KdfParams(65536, 3, 1, 32, 1), call.params)
        assertWiped(rig.keys)
    }

    @Test
    fun `an injected salt that is not sixteen bytes is refused before any request`() {
        for (size in listOf(15, 17)) {
            val rig = Rig()
            rig.salt = ByteArray(size) { 0x04.toByte() }
            val refusal = assertThrows(KdfRefused::class.java) { rig.service().register("ada", testPassword()) }
            assertEquals(KdfRefusal.BAD_SALT_LENGTH, refusal.reason)
            assertEquals(0, rig.http.requests.size)
            assertEquals(0, rig.keys.calls.size)
        }
    }

    @Test
    fun `the register request carries the salt and the verifier in schema order`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        rig.service().register("ada", testPassword())
        val register = rig.http.requests[0]
        assertEquals("POST", register.method)
        assertEquals(REGISTER_URL, register.url)
        assertEquals(listOf("Content-Type" to "application/json"), register.headers)
        assertEquals(
            "{\"username\":\"ada\",\"salt\":\"" + base64Of(FAKE_SALT_BYTES) +
                "\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1}," +
                "\"kdf_version\":1,\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"}",
            bodyText(register),
        )
    }

    @Test
    fun `a 409 on register gives username taken`() {
        val rig = Rig()
        rig.http.answer(409)
        assertEquals(AuthFailureReason.USERNAME_TAKEN, registerReason(rig))
        assertEquals(1, rig.http.requests.size)
        assertEquals(emptyList<String>(), rig.vault.calls)
    }

    @Test
    fun `a reply other than created on register gives server error`() {
        for (status in listOf(200, 500)) {
            val rig = Rig()
            rig.http.answer(status)
            assertEquals(AuthFailureReason.SERVER_ERROR, registerReason(rig))
        }
    }

    @Test
    fun `an unreachable server on register gives server unreachable`() {
        val rig = Rig()
        rig.http.failWith(IOException("fake connection refused"))
        assertEquals(AuthFailureReason.SERVER_UNREACHABLE, registerReason(rig))
    }

    @Test
    fun `register logs in with the same verifier and derives only once`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        rig.service().register("ada", testPassword())
        assertEquals(1, rig.keys.calls.size)
        val login = rig.http.requests[1]
        assertEquals(LOGIN_URL, login.url)
        assertEquals(
            "{\"username\":\"ada\",\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"}",
            bodyText(login),
        )
    }

    @Test
    fun `a created reply to register is not parsed and still goes on to login`() {
        for (body in listOf("", "not json")) {
            val rig = Rig()
            rig.http.answer(201, body)
            rig.answerLoginOk()
            rig.service().register("ada", testPassword())
            assertEquals(2, rig.http.requests.size)
            assertEquals(LOGIN_URL, rig.http.requests[1].url)
        }
    }

    @Test
    fun `a register whose login fails stores nothing and throws the login failure`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.http.answer(401)
        assertEquals(AuthFailureReason.BAD_CREDENTIALS, registerReason(rig))
        assertNull(rig.vault.stored)
        assertEquals(emptyList<String>(), rig.vault.calls)
        assertWiped(rig.keys)
    }

    @Test
    fun `a vault save failure after register sends a logout with that token and rethrows`() {
        val rig = Rig()
        val refusal = FakeVaultRefusal()
        rig.vault.saveFailure = refusal
        rig.http.answer(201)
        rig.answerLoginOk(bearer = "tok-reg")
        rig.http.answer(200)
        val thrown = assertThrows(FakeVaultRefusal::class.java) { rig.service().register("ada", testPassword()) }
        assertSame(refusal, thrown)
        assertEquals(3, rig.http.requests.size)
        assertEquals(LOGOUT_URL, rig.http.requests[2].url)
        assertEquals("Bearer tok-reg", headerValue(rig.http.requests[2], "Authorization"))
        assertNull(rig.vault.stored)
    }
}
