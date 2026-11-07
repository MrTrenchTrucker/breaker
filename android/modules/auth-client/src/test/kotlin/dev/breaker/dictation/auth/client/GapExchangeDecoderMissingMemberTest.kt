package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Each member the exchange body must hold, or ExchangeDecoder refuses with MISSING_KEY. */
class GapExchangeDecoderMissingMemberTest {

    private val SESSION_TEXT = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val SEALED = "aGVsbG8="

    private fun bodyOf(token: String, role: String, keyVersion: String,
                       sealed: String, username: String, accountId: String): String {
        return "{\"token\":\"" + token + "\"," +
            "\"role\":\"" + role + "\"," +
            "\"key_version\":" + keyVersion + "," +
            "\"sealed_package\":\"" + sealed + "\"," +
            "\"username\":\"" + username + "\"," +
            "\"account_id\":" + accountId + "}"
    }

    private val goodBody = bodyOf(SESSION_TEXT, "user", "0", SEALED, "alice", "1")

    private fun bodyWithout(member: String): String {
        val pairs = goodBody.substring(1, goodBody.length - 1).split(",")
        return "{" + pairs.filter { !it.startsWith("\"$member\"") }
            .joinToString(",") + "}"
    }

    private fun refusedFamily(body: String): String {
        return assertThrows(AuthDecodeException::class.java) {
            ExchangeDecoder.parseExchange(body)
        }.family
    }

    @Test
    fun `a missing token is refused as missing key`() {
        assertEquals("missing token carries MISSING_KEY",
            "MISSING_KEY", refusedFamily(bodyWithout("token")))
    }

    @Test
    fun `a missing role is refused as missing key`() {
        assertEquals("missing role carries MISSING_KEY",
            "MISSING_KEY", refusedFamily(bodyWithout("role")))
    }

    @Test
    fun `a missing sealed package is refused as missing key`() {
        assertEquals("missing sealed_package carries MISSING_KEY",
            "MISSING_KEY", refusedFamily(bodyWithout("sealed_package")))
    }

    @Test
    fun `a missing username is refused as missing key`() {
        assertEquals("missing username carries MISSING_KEY",
            "MISSING_KEY", refusedFamily(bodyWithout("username")))
    }

    @Test
    fun `a member present is accepted`() {
        val answer = ExchangeDecoder.parseExchange(goodBody)
        assertNotNull("the session text decodes", answer)
        assertEquals("the session text is 43 chars", 43, answer.session.token.length)
    }
}
