package dev.breaker.dictation.auth.client

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure windows of the key step. A fake that throws at the point stands for a crash at that
 * point: what the phone holds afterwards is read from the fakes, and the next login must recover.
 */
class KeyFailureWindowTest {
    private fun memberOf(json: String, name: String): String =
        Regex("\"" + name + "\":\"([^\"]*)\"").find(json)!!.groupValues[1]

    /** Salt, login, keys POST and one logout went out, and nothing is stored on the phone. */
    private fun assertFailedClean(kr: KeyRig, case: String) {
        assertEquals(case + ": requests", listOf(SALT_URL, LOGIN_URL, KEY_KEYS_URL, LOGOUT_URL), kr.urls())
        assertEquals(case + ": logouts", listOf<String?>("Bearer tok-1"), kr.logoutBearers())
        assertEquals(case + ": credential vault calls", emptyList<String>(), kr.credentials.calls)
        assertEquals(case + ": session vault calls", emptyList<String>(), kr.sessions.calls)
        assertNull(case + ": stored keys", kr.credentials.readKeys())
        assertNull(case + ": session record", kr.sessions.stored)
    }

    private fun queueKeysAnswer(
        kr: KeyRig,
        status: Int,
        body: String = "",
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.answer(status, body, headers)
    }

    @Test
    fun `a failed key store after the keys POST logs out and the next login recovers the keys`() {
        val kr = KeyRig()
        kr.credentials.saveFailure = FakeVaultRefusal()
        kr.queueBootstrap("tok-1", 1)
        kr.http.answer(200)
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.KEY_STORE_FAILED, thrown.reason)
        assertEquals(emptyList<String>(), kr.sessions.calls)
        assertNull(kr.sessions.stored)
        assertEquals(listOf<String?>("Bearer tok-1"), kr.logoutBearers())
        assertEquals(LOGOUT_URL, kr.http.requests.last().url)
        val posted = bodyText(kr.http.requests[2])
        val dekText = memberOf(posted, "wrapped_dek")
        assertArrayEquals(keyWrappedDek(), Base64.getDecoder().decode(dekText))
        // the process came back: the store works and the server now holds what the first attempt uploaded
        kr.credentials.saveFailure = null
        kr.rig.answerSaltOk()
        val boxText = memberOf(posted, "wrapped_box_privkey")
        val pubText = memberOf(posted, "box_pubkey")
        kr.http.answer(200, loginReplyWithKeyTexts("tok-2", dekText, boxText, pubText, 1))
        val session = kr.login()
        assertEquals("tok-2", session.token)
        assertKeysStored(kr, 1)
        assertNotNull(kr.sessions.stored)
        assertEquals("two logins, one derivation each", 2, kr.derive.calls.size)
    }

    @Test
    fun `a failed session save after the keys are stored clears both stores and rethrows its own failure`() {
        val kr = KeyRig()
        val failure = FakeVaultRefusal()
        kr.sessions.saveFailure = failure
        kr.queueBootstrap("tok-1", 1)
        kr.http.answer(200)
        val thrown = assertThrows(FakeVaultRefusal::class.java) { kr.login() }
        assertSame(failure, thrown)
        assertEquals(listOf("saveKeys", "wipeAll"), kr.credentials.calls)
        assertEquals("wipeAll calls", 1, kr.credentials.calls.count { it == "wipeAll" })
        assertNull("stored keys after the wipe", kr.credentials.readKeys())
        assertEquals(listOf("load", "save", "clear"), kr.sessions.calls)
        assertNull(kr.sessions.stored)
        assertEquals(listOf<String?>("Bearer tok-1"), kr.logoutBearers())
        assertEquals(LOGOUT_URL, kr.http.requests.last().url)
    }

    @Test
    fun `a failing wipe is added to the session save failure and does not replace it`() {
        val kr = KeyRig()
        val failure = FakeVaultRefusal()
        val wipeFailure = IllegalStateException("the fake key store refused the wipe")
        kr.sessions.saveFailure = failure
        kr.credentials.wipeFailure = wipeFailure
        kr.queueBootstrap("tok-1", 1)
        kr.http.answer(200)
        val thrown = assertThrows(FakeVaultRefusal::class.java) { kr.login() }
        assertSame(failure, thrown)
        assertTrue("the wipe failure was not suppressed", thrown.suppressed.any { it === wipeFailure })
        assertEquals("wipeAll calls", 1, kr.credentials.calls.count { it == "wipeAll" })
    }

    @Test
    fun `an unreachable server on the keys POST is SERVER_UNREACHABLE with nothing stored`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.failWith(IOException("fake connection refused"))
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.SERVER_UNREACHABLE, thrown.reason)
        assertFailedClean(kr, "keys POST IOException")
    }

    @Test
    fun `a 409 on the keys POST then a login with keys ends logged in with the second token`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.answer(409)
        kr.http.answer(200, keyedLoginBody("tok-2", 3))
        kr.http.answer(200)
        val session = kr.login()
        assertEquals("tok-2", session.token)
        val expectedUrls = listOf(SALT_URL, LOGIN_URL, KEY_KEYS_URL, LOGIN_URL, LOGOUT_URL)
        assertEquals(expectedUrls, kr.urls())
        assertEquals(listOf<String?>("Bearer tok-1"), kr.logoutBearers())
        assertEquals("keys POSTs", 1, kr.urls().count { it == KEY_KEYS_URL })
        assertEquals(bodyText(kr.http.requests[1]), bodyText(kr.http.requests[3]))
        assertTrue("the unwrap path did not run: " + kr.log, kr.log.contains("unwrapDek"))
        assertKeysStored(kr, 3)
        assertEquals("derive count", 1, kr.derive.calls.size)
        assertNotNull(kr.sessions.stored)
    }

    @Test
    fun `a 409 on the keys POST then a login with no keys is KEY_UPLOAD_REFUSED and never loops`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.answer(409)
        kr.rig.answerLoginOk("tok-2")
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.KEY_UPLOAD_REFUSED, thrown.reason)
        val expectedUrls = listOf(SALT_URL, LOGIN_URL, KEY_KEYS_URL, LOGIN_URL, LOGOUT_URL, LOGOUT_URL)
        assertEquals(expectedUrls, kr.urls())
        // the first token is dropped when the second login arrives; the second when the flow fails
        assertEquals(listOf<String?>("Bearer tok-1", "Bearer tok-2"), kr.logoutBearers())
        assertEquals("credential vault calls", emptyList<String>(), kr.credentials.calls)
        assertEquals("session vault calls", emptyList<String>(), kr.sessions.calls)
        assertNull(kr.credentials.readKeys())
    }

    @Test
    fun `a refused keys POST is KEY_UPLOAD_REFUSED for 400 401 403 and 413`() {
        for (status in listOf(400, 401, 403, 413)) {
            val kr = KeyRig()
            queueKeysAnswer(kr, status)
            val thrown = kr.failure()
            assertEquals("status " + status, AuthFailureReason.KEY_UPLOAD_REFUSED, thrown.reason)
            assertFailedClean(kr, "status " + status)
        }
    }

    @Test
    fun `a 429 on the keys POST is RATE_LIMITED with the retry seconds`() {
        val kr = KeyRig()
        queueKeysAnswer(kr, 429, headers = listOf("Retry-After" to "30"))
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.RATE_LIMITED, thrown.reason)
        assertEquals(30, thrown.retryAfterSeconds)
        assertFailedClean(kr, "status 429")
    }

    @Test
    fun `a 500 on the keys POST is SERVER_ERROR`() {
        val kr = KeyRig()
        queueKeysAnswer(kr, 500)
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.SERVER_ERROR, thrown.reason)
        assertFailedClean(kr, "status 500")
    }

    @Test
    fun `a 200 keys reply that is not a single positive key version is UNREADABLE_REPLY`() {
        val bodies = listOf(
            "{}", "{\"key_version\":1,\"x\":2}", "{\"key_version\":0}", "{\"key_version\":-1}",
            "{\"key_version\":2147483648}", "{\"key_version\":\"1\"}",
        )
        for (body in bodies) {
            val kr = KeyRig()
            queueKeysAnswer(kr, 200, body)
            val thrown = kr.failure()
            assertEquals("body " + body, AuthFailureReason.UNREADABLE_REPLY, thrown.reason)
            assertFailedClean(kr, "body " + body)
        }
    }

    @Test
    fun `a refused set up step is KEY_UPLOAD_REFUSED with no keys POST and nothing stored`() {
        val steps = listOf("generateDataKey", "generateBoxKeyPair", "wrapDek", "wrapBoxPrivateKey")
        for ((index, name) in steps.withIndex()) {
            val kr = KeyRig()
            kr.accountKeys.failOn = name
            kr.queueBootstrap()
            val thrown = kr.failure()
            assertEquals(name + ": reason", AuthFailureReason.KEY_UPLOAD_REFUSED, thrown.reason)
            val keyCalls = kr.log.filter { it in steps }
            assertEquals(name + ": key fake calls", steps.subList(0, index + 1), keyCalls)
            assertFalse(name + ": a keys POST was sent", kr.urls().contains(KEY_KEYS_URL))
            assertEquals(name + ": credential vault calls", emptyList<String>(), kr.credentials.calls)
            assertNull(name + ": session record", kr.sessions.stored)
            assertFalse(name + ": session.save was logged", kr.log.contains("session.save"))
            assertNull(name + ": stored keys", kr.credentials.readKeys())
        }
    }
}
