package dev.breaker.dictation.auth.client

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secrets stay out of everything the service sends or throws: the password, the auth verifier,
 * the key bytes and the session token. The only place the token may appear is the bearer header of
 * the logout request, which is the one request that carries it on purpose.
 */
class ServerNoLeakTest {
    private val bearerText = "tok-secret-88"
    private val verifierText = base64Of(FAKE_VERIFIER_BYTES)
    private val keyText = base64Of(FAKE_KEK_BYTES)

    /** The requests of a successful register and logout, then a failed login. */
    private fun recordedRequests(): List<AuthHttpRequest> {
        val requests = mutableListOf<AuthHttpRequest>()
        val registered = Rig()
        registered.http.answer(201)
        registered.answerLoginOk(bearer = bearerText)
        registered.http.answer(200)
        val service = registered.service()
        service.register("ada", testPassword())
        service.logout()
        requests.addAll(registered.http.requests)
        val refused = Rig()
        refused.answerSaltOk()
        refused.http.answer(401)
        runCatching { refused.service().login("ada", testPassword()) }
        requests.addAll(refused.http.requests)
        return requests
    }

    /** The message of the failure [block] throws, or an empty text when it throws nothing. */
    private fun messageOf(block: () -> Unit): String = runCatching(block).exceptionOrNull()?.message.orEmpty()

    @Test
    fun `no recorded request carries the password`() {
        val requests = recordedRequests()
        assertTrue(requests.isNotEmpty())
        for (request in requests) {
            assertFalse(request.url.contains(TEST_PHRASE_TEXT))
            assertFalse(bodyText(request).contains(TEST_PHRASE_TEXT))
            for ((_, value) in request.headers) {
                assertFalse(value.contains(TEST_PHRASE_TEXT))
            }
        }
    }

    @Test
    fun `no recorded url or header carries the verifier or the token or the key bytes`() {
        for (request in recordedRequests()) {
            assertFalse(request.url.contains(bearerText))
            assertFalse(request.url.contains(verifierText))
            assertFalse(request.url.contains(keyText))
            for ((name, value) in request.headers) {
                assertFalse(value.contains(verifierText))
                assertFalse(value.contains(keyText))
                val bearerOfLogout = name == "Authorization" && request.url == LOGOUT_URL
                if (!bearerOfLogout) {
                    assertFalse(value.contains(bearerText))
                }
            }
        }
    }

    @Test
    fun `the verifier text appears only in the register and login bodies`() {
        val requests = recordedRequests()
        val carrying = requests.filter { bodyText(it).contains(verifierText) }
        assertTrue("the verifier must reach the register or the login body", carrying.isNotEmpty())
        for (request in carrying) {
            assertTrue(request.url == REGISTER_URL || request.url == LOGIN_URL)
        }
        for (request in requests) {
            assertFalse(bodyText(request).contains(keyText))
            assertFalse(bodyText(request).contains(bearerText))
        }
    }

    @Test
    fun `no thrown failure message carries a secret for any failure reason`() {
        val messages = mutableListOf<String>()
        messages.add(messageOf {
            val rig = Rig()
            rig.base = "ftp://box.example"
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf { Rig().service().register("ada", "short".toCharArray()) })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(404)
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.answerSaltOk()
            rig.http.answer(401)
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(409)
            rig.service().register("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.failWith(IOException("fake connection refused"))
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(500)
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(200, "not json")
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(200, saltReplyBody(memoryKib = 1))
            rig.service().login("ada", testPassword())
        })
        messages.add(messageOf {
            val rig = Rig()
            rig.http.answer(200, saltReplyBody(salt = ByteArray(15) { 0x03.toByte() }))
            rig.service().login("ada", testPassword())
        })
        assertEquals(10, messages.size)
        assertTrue("every scenario must throw", messages.all { it.isNotEmpty() })
        for (message in messages) {
            for (secret in listOf(TEST_PHRASE_TEXT, verifierText, keyText, bearerText)) {
                assertFalse("a failure message carries a secret", message.contains(secret))
            }
        }
    }
}
