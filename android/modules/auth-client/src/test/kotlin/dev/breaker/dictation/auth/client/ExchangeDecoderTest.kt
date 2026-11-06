package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader of the exchange answer: what it accepts, and the family of each refusal. */
class ExchangeDecoderTest {
    private fun refusalOf(body: String): AuthDecodeException =
        assertThrows(AuthDecodeException::class.java) { ExchangeDecoder.parseExchange(body) }

    private fun assertFamily(family: String, body: String, label: String) {
        assertEquals("case " + label, family, refusalOf(body).family)
    }

    private fun withMember(name: String, value: String): String {
        val members = goodMembers()
        members[name] = value
        return bodyOf(members)
    }

    @Test
    fun `a good answer is read into the session the key version and the package`() {
        val answer = ExchangeDecoder.parseExchange(bodyOf(goodMembers()))
        assertEquals(goodSession(), answer.session)
        assertEquals(GOOD_KEY_VERSION, answer.keyVersion)
        assertTrue(sealedPackageBytes().contentEquals(answer.sealedBytes))
        assertEquals(43, GOOD_SESSION_TEXT.length)
    }

    @Test
    fun `the admin role gives both scopes and the user id is the username`() {
        val answer = ExchangeDecoder.parseExchange(withMember("role", quoted("admin")))
        val expected = AuthSession(
            USERNAME_TEXT, USERNAME_TEXT, GOOD_SESSION_TEXT, UserRole.ADMIN,
            setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN),
        )
        assertEquals(expected, answer.session)
    }

    @Test
    fun `the key version bounds zero and the largest Int are accepted`() {
        assertEquals(0, ExchangeDecoder.parseExchange(withMember("key_version", "0")).keyVersion)
        val largest = ExchangeDecoder.parseExchange(withMember("key_version", Int.MAX_VALUE.toString()))
        assertEquals(Int.MAX_VALUE, largest.keyVersion)
    }

    @Test
    fun `an account id of any whole number is read and not kept`() {
        for (value in listOf("0", "-5", "7")) {
            val answer = ExchangeDecoder.parseExchange(withMember("account_id", value))
            assertEquals("case " + value, goodSession(), answer.session)
        }
    }

    @Test
    fun `extra members are ignored`() {
        val members = goodMembers()
        members["surprise"] = quoted("x")
        members["nested"] = "{\"a\":{\"b\":1}}"
        val answer = ExchangeDecoder.parseExchange(bodyOf(members))
        assertEquals(goodSession(), answer.session)
        assertEquals(GOOD_KEY_VERSION, answer.keyVersion)
    }

    @Test
    fun `a body that is not JSON is refused as a type error`() {
        for (body in listOf("", "not json", "{", "nul", "{\"token\":")) {
            assertFamily("TYPE", body, "body " + body.length)
        }
    }

    @Test
    fun `an array or a scalar body is refused as a type error`() {
        for (body in listOf("[]", "[{}]", "\"x\"", "5", "null", "true")) {
            assertFamily("TYPE", body, "body " + body)
        }
    }

    @Test
    fun `an empty object is refused for its missing member`() {
        assertFamily("MISSING_KEY", "{}", "empty object")
    }

    @Test
    fun `a duplicate member is refused`() {
        val body = bodyOf(goodMembers()).replaceFirst("{", "{\"role\":\"user\",")
        assertFamily("TYPE", body, "duplicate role")
    }

    @Test
    fun `text after the object is refused`() {
        assertFamily("TYPE", bodyOf(goodMembers()) + "x", "trailing text")
    }

    @Test
    fun `an object nested too deep is refused`() {
        val members = goodMembers()
        members["deep"] = "{\"a\":{\"b\":{\"c\":{}}}}"
        assertFamily("TYPE", bodyOf(members), "deep nesting")
    }

    @Test
    fun `each of the six members is required`() {
        for (name in goodMembers().keys) {
            val members = goodMembers()
            members.remove(name)
            assertFamily("MISSING_KEY", bodyOf(members), "missing " + name)
        }
    }

    @Test
    fun `each of the six members refuses a value of the wrong type`() {
        val stringMembers = listOf("token", "role", "sealed_package", "username")
        for (name in stringMembers) {
            for (wrong in listOf("5", "true", "null", "[]", "{}")) {
                assertFamily("TYPE", withMember(name, wrong), name + " as " + wrong)
            }
        }
        val numberMembers = listOf("key_version", "account_id")
        for (name in numberMembers) {
            for (wrong in listOf(quoted("5"), "true", "null", "[]", "{}", "1.5")) {
                assertFamily("TYPE", withMember(name, wrong), name + " as " + wrong)
            }
        }
    }

    @Test
    fun `a role other than user or admin is refused`() {
        for (role in listOf("root", "", "guest")) {
            assertFamily("BAD_VALUE", withMember("role", quoted(role)), "role " + role.length)
        }
    }

    @Test
    fun `the role is case sensitive`() {
        for (role in listOf("User", "ADMIN", "Admin", "USER")) {
            assertFamily("BAD_VALUE", withMember("role", quoted(role)), "role " + role)
        }
    }

    @Test
    fun `a key version below zero or above the largest Int is refused`() {
        val over = (Int.MAX_VALUE.toLong() + 1L).toString()
        assertFamily("BAD_VALUE", withMember("key_version", "-1"), "minus one")
        assertFamily("BAD_VALUE", withMember("key_version", over), "one over the largest Int")
    }

    @Test
    fun `a key version too large for a Long is refused as a type error`() {
        assertFamily("TYPE", withMember("key_version", "9".repeat(25)), "huge number")
    }

    @Test
    fun `a package that is not standard base64 is refused`() {
        for (text in listOf("!!!!", "ab-_", "not base64")) {
            assertFamily("BAD_BASE64", withMember("sealed_package", quoted(text)), "package " + text.length)
        }
    }

    @Test
    fun `an empty package is refused`() {
        assertFamily("BAD_VALUE", withMember("sealed_package", quoted("")), "empty package")
    }

    @Test
    fun `a session text that is not 43 URL-safe characters is refused`() {
        val body = GOOD_SESSION_TEXT
        val cases = mapOf(
            "42 characters" to body.dropLast(1),
            "44 characters" to body + "R",
            "empty" to "",
            "a space" to " " + body.drop(1),
            "a plus" to "+" + body.drop(1),
            "a slash" to "/" + body.drop(1),
            "a dot" to "." + body.drop(1),
            "a non-ASCII letter" to "\u00e9" + body.drop(1),
        )
        for ((name, text) in cases) {
            assertFamily("BAD_VALUE", withMember("token", quoted(text)), name)
        }
    }

    private fun assertSessionTextAccepted(sessionText: String, label: String) {
        assertEquals("case " + label, 43, sessionText.length)
        val body = withMember("token", quoted(sessionText))
        assertEquals("case " + label, sessionText, ExchangeDecoder.parseExchange(body).session.token)
        assertEquals("case " + label, sessionText, ExchangeDecoder.tokenShapeIfPresent(body))
    }

    @Test
    fun `a session text with hyphens underscores and digits is accepted by both readers`() {
        assertSessionTextAccepted(URL_SAFE_SESSION_TEXT, "all three kinds")
    }

    @Test
    fun `a session text with a digit among letters is accepted`() {
        assertSessionTextAccepted(GOOD_SESSION_TEXT.replace('c', '7'), "digit only")
    }

    @Test
    fun `a session text with a hyphen among letters is accepted`() {
        assertSessionTextAccepted(GOOD_SESSION_TEXT.replace('c', '-'), "hyphen only")
    }

    @Test
    fun `a session text with an underscore among letters is accepted`() {
        assertSessionTextAccepted(GOOD_SESSION_TEXT.replace('c', '_'), "underscore only")
    }

    @Test
    fun `a plus or a dot among URL-safe characters is refused by both readers`() {
        val cases = mapOf(
            "a plus" to URL_SAFE_SESSION_TEXT.replace('k', '+'),
            "a dot" to URL_SAFE_SESSION_TEXT.replace('k', '.'),
        )
        for ((name, text) in cases) {
            assertEquals("case " + name, 43, text.length)
            val body = withMember("token", quoted(text))
            assertFamily("BAD_VALUE", body, name)
            assertNull("case " + name, ExchangeDecoder.tokenShapeIfPresent(body))
        }
    }

    @Test
    fun `a blank username is refused`() {
        for (name in listOf("", " ", "   ")) {
            assertFamily("BAD_VALUE", withMember("username", quoted(name)), "blank " + name.length)
        }
    }

    @Test
    fun `a refusal text carries no value from the body`() {
        val bodies = listOf(
            withMember("role", quoted("rootish-role")),
            withMember("key_version", "-1"),
            withMember("sealed_package", quoted("")),
            withMember("sealed_package", quoted("!!!!")),
            withMember("username", quoted(" ")),
            withMember("token", quoted("z".repeat(42))),
            withMember("account_id", quoted("5")),
        )
        val packageText = base64Of(sealedPackageBytes())
        val values = listOf(GOOD_SESSION_TEXT, USERNAME_TEXT, packageText, "rootish", "zzzz")
        for (body in bodies) {
            val text = refusalOf(body).toString()
            for (value in values) {
                assertFalse("a refusal text holds a value from the body", text.contains(value))
            }
        }
    }

    @Test
    fun `the printed form of an answer shows nothing`() {
        val answer = ExchangeDecoder.parseExchange(bodyOf(goodMembers()))
        assertEquals("ExchangeAnswer(redacted)", answer.toString())
    }

    @Test
    fun `the session text is found in a good answer`() {
        assertEquals(GOOD_SESSION_TEXT, ExchangeDecoder.tokenShapeIfPresent(bodyOf(goodMembers())))
    }

    @Test
    fun `the session text is found even when the other members are wrong`() {
        val members = goodMembers()
        members["role"] = "5"
        members.remove("username")
        assertEquals(GOOD_SESSION_TEXT, ExchangeDecoder.tokenShapeIfPresent(bodyOf(members)))
        val onlyOne = "{\"token\":\"" + GOOD_SESSION_TEXT + "\"}"
        assertEquals(GOOD_SESSION_TEXT, ExchangeDecoder.tokenShapeIfPresent(onlyOne))
    }

    @Test
    fun `no session text is found in anything else and nothing is thrown`() {
        val body = GOOD_SESSION_TEXT
        val members = goodMembers()
        members.remove("token")
        val cases = mapOf(
            "42 characters" to withMember("token", quoted(body.dropLast(1))),
            "44 characters" to withMember("token", quoted(body + "R")),
            "a space" to withMember("token", quoted(" " + body.drop(1))),
            "a number" to withMember("token", "5"),
            "null" to withMember("token", "null"),
            "missing" to bodyOf(members),
            "empty text" to "",
            "not json" to "not json",
            "array" to "[" + quoted(body) + "]",
            "empty object" to "{}",
            "cut short" to "{\"token\":",
            "duplicate member" to "{\"token\":\"" + body + "\",\"token\":\"" + body + "\"}",
            "trailing text" to bodyOf(goodMembers()) + "x",
            "control character" to "\u0000",
        )
        for ((name, text) in cases) {
            assertNull("case " + name, ExchangeDecoder.tokenShapeIfPresent(text))
        }
    }
}
