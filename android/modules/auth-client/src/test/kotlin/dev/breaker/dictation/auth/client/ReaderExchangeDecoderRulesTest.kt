package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for ExchangeDecoder.parseExchange and tokenShapeIfPresent, read from the KDoc and the contract. */
class ReaderExchangeDecoderRulesTest {
    private val goodSessionText = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val sealedPkg = "ZXhhbXBsZSBzZWFsZWQgcGFja2FnZSwgbm90IGEgcmVhbCBrZXkAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val goodBody = "{\"token\":\"" + goodSessionText +
            "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
            "\",\"username\":\"alice\",\"account_id\":1}"

    private fun parseOk(body: String) = try {
        ReaderExchangeDecoderAccess.parseExchange(body)
    } catch (e: Exception) {
        throw AssertionError("parseExchange must not refuse: $e")
    }

    @Test
    fun `a well formed exchange answer is accepted`() {
        val answer = parseOk(goodBody)
        assertEquals("the session user id is the username", "alice", answer.session.userId)
        assertEquals("the session username is the username", "alice", answer.session.username)
        assertEquals("the token is the session token", goodSessionText, answer.session.token)
        assertEquals("the role is user", UserRole.USER, answer.session.role)
        assertEquals("key version zero is accepted", 0, answer.keyVersion)
        assertNotNull("the sealed package is kept", answer.sealedBytes)
    }

    @Test
    fun `a non object body is refused`() {
        assertRefused("[1,2,3]")
        assertRefused("\"not an object\"")
        assertRefused("42")
        assertRefused("true")
        assertRefused("null")
    }

    @Test
    fun `trailing text after the one object makes the body unreadable`() {
        assertRefused(goodBody + " trailing")
    }

    @Test
    fun `a top level array is refused`() {
        assertRefused("[]")
    }

    @Test
    fun `a top level scalar is refused`() {
        assertRefused("7")
        assertRefused("\"string\"")
    }

    @Test
    fun `a nested object too deep is refused and one level less is accepted`() {
        val head = "{\"token\":\"" + goodSessionText + "\",\"role\":\"user\",\"key_version\":0," +
            "\"sealed_package\":\"" + sealedPkg + "\",\"username\":\"alice\",\"account_id\":1,"
        // The top object is level 1 and the reader caps containers at four levels, so the
        // member deep may hold two more levels below itself: a, then b.
        parseOk(head + "\"deep\":{\"a\":{\"b\":{\"x\":1}}}}")
        assertRefused(head + "\"deep\":{\"a\":{\"b\":{\"c\":{\"x\":1}}}}}")
    }

    @Test
    fun `the same member written twice makes the body unreadable`() {
        assertRefused("{\"token\":\"" + goodSessionText + "\",\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"alice\",\"account_id\":1}")
    }

    @Test
    fun `the token at 43 characters is accepted`() {
        assertEquals(43, goodSessionText.length)
        assertNotNull(tokenShape(goodBody))
    }

    @Test
    fun `the token at 42 characters is refused`() {
        val body = goodBody.replace(goodSessionText, goodSessionText.dropLast(1))
        assertNull(tokenShape(body))
    }

    @Test
    fun `the token at 44 characters is refused`() {
        val body = goodBody.replace(goodSessionText, goodSessionText + "x")
        assertNull(tokenShape(body))
    }

    @Test
    fun `the alphabet accepts dash and underscore`() {
        // 43 URL-safe characters including dash and underscore
        val tok = "ABCDEFGHIJKLMNOPQRSTUVWXYZ-_0123456789abcde"
        assertEquals(43, tok.length)
        assertNotNull(tokenShape(goodBody.replace(goodSessionText, tok)))
    }

    @Test
    fun `the alphabet refuses plus slash equals and space`() {
        val plus = goodBody.replace(goodSessionText, "ABCDEFGHIJKLMNOPQRSTUVWXYZ+0123456789abcdef")
        val slashEq = goodBody.replace(goodSessionText, "ABCDEFGHIJKLMNOPQRSTUVWXYZ=0123456789abcdef")
        val space = goodBody.replace(goodSessionText, "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789abcdef")
        assertNull(tokenShape(plus))
        assertNull(tokenShape(slashEq))
        assertNull(tokenShape(space))
    }

    @Test
    fun `key version minus one is refused`() {
        assertRefused(bodyWith("key_version", -1))
    }

    @Test
    fun `key version zero is accepted`() {
        assertEquals(0, parseOk(bodyWith("key_version", 0)).keyVersion)
    }

    @Test
    fun `key version 2147483647 is accepted`() {
        assertEquals(2147483647, parseOk(bodyWith("key_version", 2147483647)).keyVersion)
    }

    @Test
    fun `key version 2147483648 is refused`() {
        assertRefused(bodyWith("key_version", 2147483648))
    }

    @Test
    fun `key version a non integer is refused`() {
        assertRefused(bodyWith("key_version", "\"not an integer\""))
    }

    @Test
    fun `sealed package empty is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" +
                        "\",\"username\":\"alice\",\"account_id\":1}")
    }

    @Test
    fun `sealed package not base64 is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" +
                        "not valid base64!!\",\"username\":\"alice\",\"account_id\":1" +
                        "}")
    }

    @Test
    fun `sealed package valid is accepted`() {
        assertNotNull(parseOk(goodBody).sealedBytes)
    }

    @Test
    fun `username blank is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"\",\"account_id\":1}")
    }

    @Test
    fun `username made of spaces is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"   \",\"account_id\":1}")
    }

    @Test
    fun `account id missing is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"alice\"}")
    }

    @Test
    fun `account id a string is refused`() {
        assertRefused("{\"token\":\"" + goodSessionText +
                "\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"alice\",\"account_id\":\"one\"}")
    }

    @Test
    fun `account id present as an integer is accepted`() {
        val answer = parseOk(bodyWith("account_id", 7))
        assertEquals("the session was accepted despite an account id", 0, answer.keyVersion)
    }

    @Test
    fun `role User capitalized is refused`() {
        assertRefused(bodyWith("role", "\"User\""))
    }

    @Test
    fun `role empty is refused`() {
        assertRefused(bodyWith("role", "\"\""))
    }

    @Test
    fun `the refusal message carries no value from the body`() {
        val refusedMessage = refusalMessage(
            "{\"token\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                    "a\",\"role\":\"user\",\"key_version\":0,\"sealed_package\":" +
                    "\"ZZZ\",\"username\":\"alice\",\"account_id\":1}"
        )
        assertTrue("a wrong-length token must be refused", refusedMessage.isNotEmpty())
        assertFalse(
            "the refusal message must not contain the token text",
            refusedMessage.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
    }

    @Test
    fun `tokenShapeIfPresent returns the token when well formed`() {
        assertEquals(goodSessionText, tokenShape(goodBody))
    }

    @Test
    fun `tokenShapeIfPresent returns null when token absent`() {
        assertNull(tokenShape("{\"role\":\"user\",\"key_version\":0,\"sealed_package\":\"" + sealedPkg +
                "\",\"username\":\"alice\",\"account_id\":1}"))
    }

    @Test
    fun `tokenShapeIfPresent returns null for wrong shape`() {
        assertNull(tokenShape("{\"token\":\"too short\",\"role\":\"user\",\"key_version\":0" +
                ",\"sealed_package\":\"" +
                sealedPkg + "\",\"username\":\"alice\",\"account_id\":1}"))
    }

    @Test
    fun `tokenShapeIfPresent returns null for not an object`() {
        assertNull(tokenShape("not an object"))
    }

    @Test
    fun `tokenShapeIfPresent returns null for garbage text and never throws`() {
        assertNull(tokenShape("{ broken json"))
        assertNull(tokenShape(""))
    }

    private fun tokenShape(body: String): String? = try {
        ReaderExchangeDecoderAccess.tokenShapeIfPresent(body)
    } catch (e: Exception) {
        null
    }

    private fun assertRefused(body: String) {
        var refused = false
        try {
            ReaderExchangeDecoderAccess.parseExchange(body)
        } catch (e: Exception) {
            refused = true
            assertFalse("the refusal message carries no value from the body", e.message!!.contains(goodSessionText))
        }
        assertTrue("parseExchange must refuse: " + body, refused)
    }

    private fun refusalMessage(body: String): String {
        return try {
            ReaderExchangeDecoderAccess.parseExchange(body)
            ""
        } catch (e: Exception) {
            e.message ?: ""
        }
    }

    private fun bodyWith(key: String, value: Any): String {
        val members = mutableMapOf<String, String>()
        members["token"] = "\"" + goodSessionText + "\""
        members["role"] = "\"user\""
        members["key_version"] = "0"
        members["sealed_package"] = "\"" + sealedPkg + "\""
        members["username"] = "\"alice\""
        members["account_id"] = "1"
        members[key] = when (value) {
            is String -> value
            else -> value.toString()
        }
        return "{" + members.entries.joinToString(",") { (k, v) -> "\"" + k + "\":" + v } + "}"
    }
}

/** Same package access to internal members of ExchangeDecoder. */
internal object ReaderExchangeDecoderAccess {
    fun parseExchange(body: String) = ExchangeDecoder.parseExchange(body)
    fun tokenShapeIfPresent(body: String): String? = ExchangeDecoder.tokenShapeIfPresent(body)
}
