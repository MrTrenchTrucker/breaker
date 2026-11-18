package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Register with a one-time install code. The service opts into the core three-argument overload,
 * the code is carried as a parameter from that overload through the ops into the one
 * [dev.breaker.shared.api.RegisterRequest] the register path builds, and the password is wiped on
 * every path. The code is a credential: it is a neutral dummy, it is never a property of any
 * class, and the client does not validate it — the server decides.
 */
class ServerRegisterInstallCodeTest {
    /** A neutral dummy install code of 43 URL-safe characters; the server decides its validity. */
    private val installCode = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    private fun registerBody(code: String?): String {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        rig.service().register("ada", testPassword(), code)
        return bodyText(rig.http.requests[0])
    }

    @Test
    fun `a register with an install code is accepted and signs in`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk(bearer = "tok-code")
        val session = rig.service().register("ada", testPassword(), installCode)
        assertEquals("tok-code", session.token)
        assertEquals(2, rig.http.requests.size)
        assertEquals(REGISTER_URL, rig.http.requests[0].url)
        assertEquals(LOGIN_URL, rig.http.requests[1].url)
    }

    @Test
    fun `a register with a null install code sends the same register body as the two argument register`() {
        val plain = Rig()
        plain.http.answer(201)
        plain.answerLoginOk()
        plain.service().register("ada", testPassword())
        val withNull = Rig()
        withNull.http.answer(201)
        withNull.answerLoginOk()
        withNull.service().register("ada", testPassword(), null)
        assertEquals(bodyText(plain.http.requests[0]), bodyText(withNull.http.requests[0]))
    }

    @Test
    fun `a null install code sends no install_code member`() {
        val expected = "{\"username\":\"ada\",\"salt\":\"" + base64Of(FAKE_SALT_BYTES) +
            "\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1}," +
            "\"kdf_version\":1,\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"}"
        val body = registerBody(null)
        assertEquals(expected, body)
        assertFalse("a null code must not add an install_code member", body.contains("install_code"))
    }

    @Test
    fun `the register body carries the install code after the verifier`() {
        val expected = "{\"username\":\"ada\",\"salt\":\"" + base64Of(FAKE_SALT_BYTES) +
            "\",\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1}," +
            "\"kdf_version\":1,\"auth_verifier\":\"" + base64Of(FAKE_VERIFIER_BYTES) + "\"," +
            "\"install_code\":\"" + installCode + "\"}"
        assertEquals(expected, registerBody(installCode))
    }

    @Test
    fun `after a successful register with an install code the password array is all zeros`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        val password = testPassword()
        rig.service().register("ada", password, installCode)
        assertFalse("the password was not wiped", password.any { it != '\u0000' })
    }

    @Test
    fun `after a refused register with an install code the password array is all zeros`() {
        val rig = Rig()
        rig.http.answer(409)
        val password = testPassword()
        val failure = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", password, installCode)
        }
        assertEquals(AuthFailureReason.USERNAME_TAKEN, failure.reason)
        assertFalse("the password was not wiped after the refusal", password.any { it != '\u0000' })
    }

    @Test
    fun `a weak password with an install code is refused before any request and the array is wiped`() {
        val rig = Rig()
        val short = "abcdefghijk".toCharArray()
        val failure = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", short, installCode)
        }
        assertEquals(AuthFailureReason.WEAK_PASSWORD, failure.reason)
        assertEquals(0, rig.http.requests.size)
        assertFalse("the password was not wiped", short.any { it != '\u0000' })
    }

    @Test
    fun `a code with a quote and a backslash is accepted without client side validation`() {
        val rig = Rig()
        rig.http.answer(201)
        rig.answerLoginOk()
        val odd = "abc\"def\\ghi"
        val session = rig.service().register("ada", testPassword(), odd)
        assertEquals("tok-1", session.token)
        assertEquals(2, rig.http.requests.size)
    }

    @Test
    fun `a logout made from inside the register request with a code on the same thread completes`() {
        val rig = Rig()
        rig.vault.stored = ADA_RECORD
        val service = rig.service()
        rig.http.beforeSend = { request ->
            if (request.url == REGISTER_URL) service.logout()
        }
        // The fake pops answers nested-first: the logout made inside the register request takes the
        // first answer, so the 200 for it is queued before the 201 for the register itself.
        rig.http.answer(200)
        rig.http.answer(201)
        rig.answerLoginOk(bearer = "tok-code")
        val session = service.register("ada", testPassword(), installCode)
        assertEquals("tok-code", session.token)
        assertEquals(
            "the three argument register must hold the one reentrant lock",
            listOf(REGISTER_URL, LOGOUT_URL, LOGIN_URL),
            rig.http.requests.map { it.url },
        )
        assertEquals(SessionRecord.encode(session, BASE_URL), rig.vault.stored)
    }

    @Test
    fun `the install code is a parameter only and no class holds it as a property`() {
        val holders = listOf(ServerAuthService::class.java, ServerAuthServiceOps::class.java)
        for (type in holders) {
            val held = type.declaredFields.filter { it.name.contains("installCode", ignoreCase = true) }
            assertTrue("${type.simpleName} holds the install code as a field: ${held.map { it.name }}", held.isEmpty())
        }
    }

    @Test
    fun `the property scan fires on a planted field`() {
        class Plant(val installCode: String?)
        val names = Plant::class.java.declaredFields.map { it.name }
        assertTrue("the scan missed a planted installCode field: $names", names.any { it.contains("installCode", true) })
    }

    @Test
    fun `a register with an install code that fails before the network stores nothing`() {
        val rig = Rig()
        rig.base = "ftp://box.example"
        val password = testPassword()
        val failure = assertThrows(AuthFailure::class.java) {
            rig.service().register("ada", password, installCode)
        }
        assertEquals(AuthFailureReason.BAD_SERVER_ADDRESS, failure.reason)
        assertEquals(0, rig.http.requests.size)
        assertNull(rig.vault.stored)
        assertFalse("the password was not wiped", password.any { it != '\u0000' })
    }
}
