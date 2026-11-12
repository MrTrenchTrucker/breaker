package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A login or register whose reply holds no keys: keys made, uploaded and stored, then the session. */
class KeyBootstrapTest {
    private val madeAndUploaded = listOf(
        "generateDataKey", "generateBoxKeyPair", "wrapDek", "wrapBoxPrivateKey",
        "http POST " + KEY_KEYS_URL, "keys.saveKeys", "session.save",
    )

    private fun adaSession(token: String): AuthSession =
        AuthSession("ada", "ada", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE))

    @Test
    fun `a keyless login runs the exact call order and saves the keys before the session`() {
        val kr = KeyRig()
        kr.queueBootstrap()
        kr.login()
        val expected = listOf("http POST " + SALT_URL, "http POST " + LOGIN_URL) + madeAndUploaded
        assertEquals("keyless login call log", expected, kr.log)
        val keysPost = kr.log.indexOf("http POST " + KEY_KEYS_URL)
        val saveKeys = kr.log.indexOf("keys.saveKeys")
        val saveSession = kr.log.indexOf("session.save")
        assertTrue("saveKeys must come after the keys POST: " + kr.log, keysPost in 0 until saveKeys)
        assertTrue("session.save must come after saveKeys: " + kr.log, saveKeys in 0 until saveSession)
    }

    @Test
    fun `the keys POST carries the new token and the three wrapped members and its body is zeroed`() {
        val kr = KeyRig()
        var live: ByteArray? = null
        kr.onSend = { request -> if (request.url == KEY_KEYS_URL) live = request.body }
        kr.queueBootstrap(bearer = "tok-new")
        kr.login()
        val post = kr.http.requests[2]
        assertEquals("POST", post.method)
        assertEquals(KEY_KEYS_URL, post.url)
        assertEquals("Bearer tok-new", headerValue(post, "Authorization"))
        assertEquals("application/json", headerValue(post, "Content-Type"))
        val expected = "{\"wrapped_dek\":\"" + base64Of(keyWrappedDek()) +
            "\",\"wrapped_box_privkey\":\"" + base64Of(keyWrappedBox()) +
            "\",\"box_pubkey\":\"" + base64Of(keyPublic()) + "\"}"
        assertEquals("keys POST body", expected, bodyText(post))
        assertFalse("the recorded copy of the body must not be all zero", allZero(post.body!!))
        val captured = live
        assertNotNull("the keys POST body was never captured", captured)
        assertTrue("the array handed to the port must be zero after the call", allZero(captured!!))
    }

    @Test
    fun `the keys are stored with the version the keys reply gives as one`() {
        val kr = KeyRig()
        kr.queueBootstrap(version = 1)
        kr.login()
        assertKeysStored(kr, 1)
    }

    @Test
    fun `the keys are stored with the version the keys reply gives as seven`() {
        val kr = KeyRig()
        kr.queueBootstrap(version = 7)
        kr.login()
        assertKeysStored(kr, 7)
    }

    @Test
    fun `a register runs the same steps with the salt of the salt source`() {
        val kr = KeyRig()
        kr.rig.salt = ByteArray(16) { 0x0B.toByte() }
        kr.http.answer(201)
        kr.rig.answerLoginOk("tok-r")
        kr.http.answer(200, keysReplyBody(1))
        val session = kr.service().register("ada", testPassword())
        val expected = listOf("http POST " + REGISTER_URL, "http POST " + LOGIN_URL) + madeAndUploaded
        assertEquals("register call log", expected, kr.log)
        assertEquals(1, kr.accountKeys.wrappedDeks.size)
        assertArrayEquals(ByteArray(16) { 0x0B.toByte() }, kr.accountKeys.wrappedDeks[0].salt)
        assertEquals("register derive count", 1, kr.derive.calls.size)
        assertEquals(adaSession("tok-r"), session)
        assertKeysStored(kr, 1)
    }

    @Test
    fun `the session a bootstrap returns equals the one a keyless login without key ports returns`() {
        val kr = KeyRig()
        kr.queueBootstrap("tok-1")
        val withKeys = kr.login()
        val plain = Rig()
        plain.answerSaltOk()
        plain.answerLoginOk("tok-1")
        val without = plain.service().login("ada", testPassword())
        assertEquals(without, withKeys)
        assertEquals(adaSession("tok-1"), withKeys)
    }
}
