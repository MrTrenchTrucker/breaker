package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire pieces of the key step: the keys request body, the keys reply, its statuses and the texts. */
class KeyWireTest {
    private fun replyOf(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        AuthHttpReply(status, body.toByteArray(Charsets.UTF_8), headers)

    private fun reasonOf(status: Int): AuthFailureReason =
        assertThrows(AuthFailure::class.java) { ServerAuthReplies.keys(replyOf(status)) }.reason

    @Test
    fun `encodeKeys writes the three members in order as padded base64`() {
        val dek = keyPattern(60, 1)
        val box = keyPattern(60, 100)
        val pub = keyPattern(32, 200)
        val text = String(RequestEncoder.encodeKeys(dek, box, pub), Charsets.UTF_8)
        val expected = "{\"wrapped_dek\":\"" + base64Of(dek) + "\",\"wrapped_box_privkey\":\"" +
            base64Of(box) + "\",\"box_pubkey\":\"" + base64Of(pub) + "\"}"
        assertEquals("keys request body", expected, text)
        assertTrue("the 32 byte member is not padded: " + text, text.endsWith("=\"}"))
        assertEquals("padded length of the 32 byte member", 44, base64Of(pub).length)
        val order = listOf("wrapped_dek", "wrapped_box_privkey", "box_pubkey").map { text.indexOf(it) }
        assertEquals("member order", order.sorted(), order)
        assertFalse("a member is missing: " + order, order.any { it < 0 })
    }

    @Test
    fun `parseKeysReply accepts one positive key version`() {
        assertEquals(1, AuthDecoder.parseKeysReply("{\"key_version\":1}"))
        assertEquals(Int.MAX_VALUE, AuthDecoder.parseKeysReply("{\"key_version\":2147483647}"))
    }

    @Test
    fun `parseKeysReply refuses every other body with an AuthDecodeException`() {
        val bodies = listOf(
            "{\"key_version\":0}", "{\"key_version\":-1}", "{\"key_version\":2147483648}",
            "{\"key_version\":\"1\"}", "{\"key_version\":1.5}", "[]", "{}",
            "{\"key_version\":1,\"x\":2}", "{\"x\":1}", "not json", "",
        )
        for (body in bodies) {
            assertThrows("body: " + body, AuthDecodeException::class.java) {
                AuthDecoder.parseKeysReply(body)
            }
        }
    }

    @Test
    fun `the keys reply status table`() {
        assertEquals(5, ServerAuthReplies.keys(replyOf(200, "{\"key_version\":5}")))
        assertNull("409 gives no version", ServerAuthReplies.keys(replyOf(409)))
        assertEquals(AuthFailureReason.RATE_LIMITED, reasonOf(429))
        for (status in listOf(400, 401, 403, 413)) {
            assertEquals("status " + status, AuthFailureReason.KEY_UPLOAD_REFUSED, reasonOf(status))
        }
        for (status in listOf(404, 500, 302)) {
            assertEquals("status " + status, AuthFailureReason.SERVER_ERROR, reasonOf(status))
        }
    }

    @Test
    fun `a 429 on the keys route carries its retry seconds`() {
        val reply = replyOf(429, headers = listOf("Retry-After" to "30"))
        val thrown = assertThrows(AuthFailure::class.java) { ServerAuthReplies.keys(reply) }
        assertEquals(AuthFailureReason.RATE_LIMITED, thrown.reason)
        assertEquals(30, thrown.retryAfterSeconds)
    }

    @Test
    fun `the four new reasons carry their exact sentences`() {
        val expected = mapOf(
            AuthFailureReason.KEYS_UNREADABLE to "The account keys could not be opened.",
            AuthFailureReason.PUBLIC_KEY_MISMATCH to "The stored account key does not match its public key.",
            AuthFailureReason.KEY_UPLOAD_REFUSED to "The account keys could not be set up.",
            AuthFailureReason.KEY_STORE_FAILED to "The account keys could not be stored on this phone.",
        )
        for ((reason, sentence) in expected) {
            val message = AuthFailure(reason).message ?: ""
            assertEquals("sentence of " + reason, sentence, message)
            assertFalse("a digit in the sentence of " + reason, message.any { it.isDigit() })
            val lower = message.lowercase()
            assertFalse("key version in the sentence of " + reason, lower.contains("key version"))
        }
    }

    @Test
    fun `all fourteen reasons have distinct non empty sentences`() {
        val reasons = AuthFailureReason.values()
        assertEquals("number of reasons", 14, reasons.size)
        val sentences = reasons.map { AuthFailure(it).message ?: "" }
        assertTrue("an empty sentence: " + sentences, sentences.all { it.isNotBlank() })
        assertEquals("distinct sentences", sentences.size, sentences.toSet().size)
    }

    @Test
    fun `the keys path is the server route`() {
        assertEquals("/v1/auth/keys", AccountRules.KEYS_PATH)
    }
}
