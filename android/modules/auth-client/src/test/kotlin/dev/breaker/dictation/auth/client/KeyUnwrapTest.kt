package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A login whose reply holds the wrapped keys: they are opened, checked and stored, or nothing is stored. */
class KeyUnwrapTest {
    private fun queueKeyed(kr: KeyRig, body: String) {
        kr.rig.answerSaltOk()
        kr.http.answer(200, body)
    }

    private fun keyedBody(
        dek: ByteArray = keyWrappedDek(),
        box: ByteArray = keyWrappedBox(),
        pub: ByteArray = keyPublic(),
    ): String = loginReplyWithKeysBody("tok-k", dek, box, pub, 3)

    private fun flipped(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    /** Salt, login and one logout with the given token were sent, and nothing was stored. */
    private fun assertRefusedClean(kr: KeyRig, case: String, token: String) {
        assertEquals(case + ": requests", listOf(SALT_URL, LOGIN_URL, LOGOUT_URL), kr.urls())
        assertEquals(case + ": logouts", listOf<String?>("Bearer " + token), kr.logoutBearers())
        assertEquals(case + ": session vault calls", emptyList<String>(), kr.sessions.calls)
        assertNull(case + ": session record", kr.sessions.stored)
        assertNull(case + ": stored keys", kr.credentials.readKeys())
        assertFalse(case + ": session.save was logged", kr.log.contains("session.save"))
    }

    @Test
    fun `a login with keys unwraps verifies and stores them and sends no keys POST`() {
        val kr = KeyRig()
        queueKeyed(kr, keyedLoginBody("tok-k", 3))
        val session = kr.login()
        assertEquals(listOf(SALT_URL, LOGIN_URL), kr.urls())
        val expected = listOf(
            "http POST " + SALT_URL, "http POST " + LOGIN_URL, "unwrapDek", "unwrapBoxPrivateKey",
            "verifyBoxPublicKey", "keys.saveKeys", "session.save",
        )
        assertEquals("keyed login call log", expected, kr.log)
        assertKeysStored(kr, 3)
        assertEquals("tok-k", session.token)
    }

    @Test
    fun `a wrong box public key is PUBLIC_KEY_MISMATCH with nothing stored and one logout`() {
        val kr = KeyRig()
        queueKeyed(kr, keyedBody(pub = keyPattern(32, 0x71)))
        val thrown = kr.failure()
        // this case passes the unwrap steps and is refused by the public key check only
        assertEquals(AuthFailureReason.PUBLIC_KEY_MISMATCH, thrown.reason)
        assertEquals("The stored account key does not match its public key.", thrown.message)
        assertTrue("verifyBoxPublicKey was not reached: " + kr.log, kr.log.contains("verifyBoxPublicKey"))
        assertFalse("keys were stored after a mismatch", kr.log.contains("keys.saveKeys"))
        assertRefusedClean(kr, "public key mismatch", "tok-k")
        val message = thrown.message ?: ""
        val secrets = listOf(keyPattern(32, 0xA0), keyPattern(32, 0x30), keyWrappedDek(), keyWrappedBox())
        for (secret in secrets) {
            assertFalse("the message holds key text", message.contains(base64Of(secret)))
        }
    }

    @Test
    fun `a wrapped data key with one byte changed is KEYS_UNREADABLE with nothing stored`() {
        val kr = KeyRig()
        queueKeyed(kr, keyedBody(dek = flipped(keyWrappedDek(), 40)))
        val thrown = kr.failure()
        // this case is refused by the crypto port's unwrapDek; the box steps are never reached
        assertEquals(AuthFailureReason.KEYS_UNREADABLE, thrown.reason)
        assertEquals("The account keys could not be opened.", thrown.message)
        assertTrue("unwrapDek was not reached: " + kr.log, kr.log.contains("unwrapDek"))
        assertFalse("the box key was opened", kr.log.contains("unwrapBoxPrivateKey"))
        assertRefusedClean(kr, "changed data key", "tok-k")
    }

    @Test
    fun `a wrapped box key with one byte changed is KEYS_UNREADABLE with nothing stored`() {
        val kr = KeyRig()
        queueKeyed(kr, keyedBody(box = flipped(keyWrappedBox(), 40)))
        val thrown = kr.failure()
        // this case is refused by the account key port's unwrapBoxPrivateKey; the public key is never checked
        assertEquals(AuthFailureReason.KEYS_UNREADABLE, thrown.reason)
        assertTrue("unwrapBoxPrivateKey was not reached: " + kr.log, kr.log.contains("unwrapBoxPrivateKey"))
        assertFalse("the public key was checked", kr.log.contains("verifyBoxPublicKey"))
        assertRefusedClean(kr, "changed box key", "tok-k")
    }

    @Test
    fun `a wrapped data key that is not base64 is KEYS_UNREADABLE`() {
        val kr = KeyRig()
        val boxText = base64Of(keyWrappedBox())
        queueKeyed(kr, loginReplyWithKeyTexts("tok-k", "!!!!", boxText, base64Of(keyPublic()), 3))
        val thrown = kr.failure()
        // this case is refused by the base64 decode in the flow; no port is called
        assertEquals(AuthFailureReason.KEYS_UNREADABLE, thrown.reason)
        assertFalse("a port was called: " + kr.log, kr.log.contains("unwrapDek"))
        assertRefusedClean(kr, "not base64", "tok-k")
    }

    @Test
    fun `a valid key text with a space or newline or bang is KEYS_UNREADABLE and nothing stored`() {
        val members = listOf("data key", "box key", "box public key")
        // the second junk is a backslash and an n, so the JSON reply decodes it to a newline
        val junks = listOf(" ", "\\n", "!")
        for (member in members.indices) {
            for (junk in junks) {
                val case = members[member] + " with junk '" + junk + "'"
                val texts = mutableListOf(
                    base64Of(keyWrappedDek()), base64Of(keyWrappedBox()), base64Of(keyPublic()),
                )
                texts[member] = texts[member].substring(0, 8) + junk + texts[member].substring(8)
                val kr = KeyRig()
                queueKeyed(kr, loginReplyWithKeyTexts("tok-k", texts[0], texts[1], texts[2], 3))
                val thrown = kr.failure()
                assertEquals(case, AuthFailureReason.KEYS_UNREADABLE, thrown.reason)
                val ports = listOf("unwrapDek", "unwrapBoxPrivateKey", "verifyBoxPublicKey", "keys.saveKeys")
                for (port in ports) {
                    assertFalse(case + ": " + port + " was called: " + kr.log, kr.log.contains(port))
                }
                assertRefusedClean(kr, case, "tok-k")
            }
        }
    }

    @Test
    fun `a box public key of four bangs is KEYS_UNREADABLE and not PUBLIC_KEY_MISMATCH`() {
        val kr = KeyRig()
        val dekText = base64Of(keyWrappedDek())
        val boxText = base64Of(keyWrappedBox())
        queueKeyed(kr, loginReplyWithKeyTexts("tok-k", dekText, boxText, "!!!!", 3))
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.KEYS_UNREADABLE, thrown.reason)
        assertFalse("the public key was checked: " + kr.log, kr.log.contains("verifyBoxPublicKey"))
        assertFalse("the data key was opened: " + kr.log, kr.log.contains("unwrapDek"))
        assertRefusedClean(kr, "four bangs", "tok-k")
    }

    @Test
    fun `a failing key store is KEY_STORE_FAILED with no session saved and one logout`() {
        val kr = KeyRig()
        kr.credentials.saveFailure = FakeVaultRefusal()
        queueKeyed(kr, keyedLoginBody("tok-k", 3))
        val thrown = kr.failure()
        assertEquals(AuthFailureReason.KEY_STORE_FAILED, thrown.reason)
        assertEquals("The account keys could not be stored on this phone.", thrown.message)
        assertEquals(listOf("saveKeys"), kr.credentials.calls)
        assertFalse("the session was saved", kr.log.contains("session.save"))
        assertEquals(emptyList<String>(), kr.sessions.calls)
        assertEquals(listOf(SALT_URL, LOGIN_URL, LOGOUT_URL), kr.urls())
        assertEquals(listOf<String?>("Bearer tok-k"), kr.logoutBearers())
    }

    @Test
    fun `the previous key the reset time and the re-key time of a reply are ignored`() {
        val plain = KeyRig()
        queueKeyed(plain, keyedLoginBody("tok-k", 3))
        val plainSession = plain.login()
        val extras = KeyRig()
        val withExtras = loginReplyWithKeysBody(
            "tok-k", keyWrappedDek(), keyWrappedBox(), keyPublic(), 3,
            ",\"prev_wrapped_dek\":\"AAAA\",\"reset_at\":\"2026-01-01T00:00:00Z\"," +
                "\"rekeyed_at\":\"2026-01-02T00:00:00Z\"",
        )
        queueKeyed(extras, withExtras)
        val extraSession = extras.login()
        assertEquals("call log with the three fields set", plain.log, extras.log)
        assertEquals(plainSession, extraSession)
        assertKeysStored(extras, 3)
    }
}
