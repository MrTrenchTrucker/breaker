package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthChangePasswordRouteBodyTest : AuthChangePasswordRouteTestBase() {

    /** The seven byte members with their decoded sizes. */
    private val byteSizes = listOf(
        "old_auth_verifier" to 32,
        "new_salt" to 16,
        "new_wrapped_dek" to 60,
        "new_wrapped_box_privkey" to 60,
        "new_box_pubkey" to 32,
        "new_auth_verifier" to 32,
        "prev_wrapped_dek" to 60,
    )

    private fun bytesOfSize(size: Int): ByteArray = ByteArray(size) { i -> (i + 9).toByte() }

    /** The text of a byte member in the valid body. */
    private fun validText(name: String): String =
        validMembers().first { (key, _) -> key == name }.second.trim('"')

    /** Sends every case from one keyed account: each answer is 400 with the fixed body, nothing is written or counted. */
    private fun assertAllRefused(cases: List<Pair<String, String>>) {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val before = snapshotOf(id)
        for ((label, body) in cases) {
            assertAnswer(label, postChange(session, body), HttpStatusCode.BadRequest, badRequestBody)
        }
        assertUnchanged(id, before, "the refused bodies")
        assertEquals("sync-api: a refused body must not be counted", 0L, wrongCountOf("alice"))
    }

    private fun assertWrongSizes(name: String, sizes: List<Int>) {
        assertAllRefused(sizes.map { size -> "$name with $size bytes" to withText(name, textOf(bytesOfSize(size))) })
    }

    @Test
    fun `an old_auth_verifier of 31 or 33 bytes has the right text length and still gets 400`() {
        assertEquals("sync-api: the 31 byte text must have the length of a valid one", 44, textOf(bytesOfSize(31)).length)
        assertWrongSizes("old_auth_verifier", listOf(31, 33))
    }

    @Test
    fun `a new_salt of 15 or 17 bytes gets 400 and nothing is written`() {
        assertEquals("sync-api: the 17 byte text must have the length of a valid one", 24, textOf(bytesOfSize(17)).length)
        assertWrongSizes("new_salt", listOf(15, 17))
    }

    @Test
    fun `a new_wrapped_dek of 59 or 61 bytes gets 400 and nothing is written`() {
        assertEquals("sync-api: the 59 byte text must have the length of a valid one", 80, textOf(bytesOfSize(59)).length)
        assertWrongSizes("new_wrapped_dek", listOf(59, 61))
    }

    @Test
    fun `a new_wrapped_box_privkey of 59 or 61 bytes gets 400 and nothing is written`() {
        assertWrongSizes("new_wrapped_box_privkey", listOf(59, 61))
    }

    @Test
    fun `a new_box_pubkey of 31 or 33 bytes gets 400 and nothing is written`() {
        assertWrongSizes("new_box_pubkey", listOf(31, 33))
    }

    @Test
    fun `a new_auth_verifier of 31 or 33 bytes gets 400 and nothing is written`() {
        assertWrongSizes("new_auth_verifier", listOf(31, 33))
    }

    @Test
    fun `a prev_wrapped_dek of 59 or 61 bytes gets 400 and nothing is written`() {
        assertWrongSizes("prev_wrapped_dek", listOf(59, 61))
    }

    @Test
    fun `a 32 byte member of 43 valid characters and one illegal 44th character gets 400`() {
        val cases = ArrayList<Pair<String, String>>()
        for (name in listOf("old_auth_verifier", "new_auth_verifier", "new_box_pubkey")) {
            val head = validText(name).take(43)
            assertEquals("sync-api: the valid part of $name has 43 characters", 43, head.length)
            // A lenient decoder skips the illegal 44th character and reads 43 characters as unpadded
            // base64 of 32 bytes; only a 44-character value tells it apart from the strict decoder.
            for ((tailLabel, tail) in listOf("newline" to "\\n", "space" to " ", "exclamation mark" to "!")) {
                cases.add("$name with a $tailLabel as 44th character" to withText(name, head + tail))
            }
        }
        assertAllRefused(cases)
    }

    @Test
    fun `url-safe base64 of the right length gets 400 for each byte member`() {
        val cases = ArrayList<Pair<String, String>>()
        for ((name, size) in byteSizes) {
            val text = Base64.getUrlEncoder().encodeToString(ByteArray(size) { (-5).toByte() })
            assertTrue("sync-api: the test text must use url-safe characters: $text", text.contains("-") && text.contains("_"))
            cases.add("url-safe $name" to withText(name, text))
        }
        assertAllRefused(cases)
    }

    @Test
    fun `whitespace inside a value gets 400 whether it is a space or a JSON newline`() {
        val cases = ArrayList<Pair<String, String>>()
        for ((name, _) in byteSizes) {
            val text = validText(name)
            cases.add("$name with a space" to withText(name, text.substring(0, 10) + " " + text.substring(11)))
            cases.add("$name with a newline" to withText(name, text.substring(0, 10) + "\\n" + text.substring(11)))
        }
        assertAllRefused(cases)
    }

    @Test
    fun `unpadded and wrongly padded values get 400`() {
        val cases = ArrayList<Pair<String, String>>()
        for (name in listOf("old_auth_verifier", "new_salt", "new_box_pubkey", "new_auth_verifier")) {
            val text = validText(name)
            val bare = text.trimEnd('=')
            assertTrue("sync-api: the valid text of $name ends in padding", bare.length < text.length)
            cases.add("unpadded $name" to withText(name, bare))
            cases.add("$name padded with letters" to withText(name, bare + "A".repeat(text.length - bare.length)))
        }
        assertAllRefused(cases)
    }

    @Test
    fun `values that are not base64 get 400 for each byte member`() {
        assertAllRefused(byteSizes.map { (name, _) -> "stars for $name" to withText(name, "*".repeat(validText(name).length)) })
    }

    @Test
    fun `kdf values outside the bounds and a kdf version other than 1 get 400`() {
        assertAllRefused(
            listOf(
                "memory below the floor" to withKdf(65535, 4, 2),
                "memory above the ceiling" to withKdf(262145, 4, 2),
                "iterations 2" to withKdf(131072, 2, 2),
                "iterations 11" to withKdf(131072, 11, 2),
                "parallelism 0" to withKdf(131072, 4, 0),
                "parallelism 5" to withKdf(131072, 4, 5),
                "kdf version 2" to withRaw("new_kdf_version", "2"),
                "kdf version 0" to withRaw("new_kdf_version", "0"),
            ),
        )
    }

    @Test
    fun `malformed JSON and bodies that are not an object get 400`() {
        assertAllRefused(
            listOf(
                "truncated" to """{"old_auth_verifier":""",
                "not json" to "{ not json",
                "array" to "[]",
                "string" to "\"text\"",
                "number" to "12345",
                "empty body" to "",
                "empty object" to "{}",
                "trailing comma" to validChangeBody().dropLast(1) + ",}",
                "comma first" to "{," + validChangeBody().drop(1),
                "missing colon" to validChangeBody().replaceFirst("\":", "\""),
            ),
        )
    }

    @Test
    fun `a duplicate member at the top or inside new_kdf_params gets 400`() {
        val twice = """{"memory_kib":131072,"memory_kib":131072,"iterations":4,"parallelism":2}"""
        assertAllRefused(
            listOf(
                "duplicate member" to objectOf(validMembers() + validMembers().first()),
                "duplicate new_kdf_params" to objectOf(validMembers() + ("new_kdf_params" to kdfRaw(131072, 4, 2))),
                "duplicate integer member" to objectOf(validMembers() + ("new_key_version" to "2")),
                "duplicate member inside new_kdf_params" to withRaw("new_kdf_params", twice),
            ),
        )
    }

    @Test
    fun `an extra member and each missing member get 400`() {
        val cases = ArrayList<Pair<String, String>>()
        cases.add("extra member" to objectOf(validMembers() + ("extra" to "\"x\"")))
        for ((name, _) in validMembers()) {
            cases.add("body without $name" to without(name))
        }
        cases.add("kdf without parallelism" to withRaw("new_kdf_params", """{"memory_kib":131072,"iterations":4}"""))
        cases.add("kdf without memory_kib" to withRaw("new_kdf_params", """{"iterations":4,"parallelism":2}"""))
        cases.add("kdf with an extra member" to withRaw("new_kdf_params", """{"memory_kib":131072,"iterations":4,"parallelism":2,"extra":1}"""))
        cases.add("empty kdf object" to withRaw("new_kdf_params", "{}"))
        assertAllRefused(cases)
    }

    @Test
    fun `new_kdf_params as an array a string a number or null gets 400`() {
        assertAllRefused(
            listOf("array" to "[]", "string" to "\"x\"", "number" to "12", "null" to "null", "boolean" to "true").map { (label, raw) ->
                "new_kdf_params as $label" to withRaw("new_kdf_params", raw)
            } + listOf(
                "memory_kib as a string" to withRaw("new_kdf_params", """{"memory_kib":"131072","iterations":4,"parallelism":2}"""),
                "iterations as null" to withRaw("new_kdf_params", """{"memory_kib":131072,"iterations":null,"parallelism":2}"""),
            ),
        )
    }

    @Test
    fun `each member with a value of the wrong type gets 400`() {
        val cases = ArrayList<Pair<String, String>>()
        for ((name, _) in byteSizes) {
            for (raw in listOf("null", "true", "[]", "{}", "12")) {
                cases.add("$name as $raw" to withRaw(name, raw))
            }
        }
        for (name in listOf("new_kdf_version", "new_key_version")) {
            for (raw in listOf("\"2\"", "null", "true", "[]", "{}")) {
                cases.add("$name as $raw" to withRaw(name, raw))
            }
        }
        assertAllRefused(cases)
    }

    @Test
    fun `integers with a sign a zero in front a fraction an exponent or too many digits get 400`() {
        val cases = ArrayList<Pair<String, String>>()
        for (name in listOf("new_key_version", "new_kdf_version")) {
            val n = if (name == "new_key_version") 2 else 1
            val raws = listOf("+$n", "-$n", "0$n", "00$n", "$n.0", "${n}e0", "${n}e+0", "-0")
            for (raw in raws) {
                cases.add("$name as $raw" to withRaw(name, raw))
            }
        }
        cases.add("key version of 11 digits" to withRaw("new_key_version", "1" + "0".repeat(10)))
        cases.add("key version above the largest int" to withRaw("new_key_version", (Int.MAX_VALUE.toLong() + 1L).toString()))
        cases.add("key version 0" to withRaw("new_key_version", "0"))
        cases.add("kdf memory with a sign" to withRaw("new_kdf_params", """{"memory_kib":+131072,"iterations":4,"parallelism":2}"""))
        cases.add("kdf iterations with a fraction" to withRaw("new_kdf_params", """{"memory_kib":131072,"iterations":4.0,"parallelism":2}"""))
        assertAllRefused(cases)
    }

    @Test
    fun `bytes after the object get 400`() {
        val body = validChangeBody()
        assertAllRefused(
            listOf(
                "a letter after" to body + "x",
                "a second object after" to body + "{}",
                "a comma after" to "$body,",
                "a form feed after" to body + "\u000C",
                "an em space after" to body + Char(0x2003),
                "a form feed before" to "\u000C" + body,
            ),
        )
    }

    @Test
    fun `a raw control character in a string and a bad escape get 400`() {
        val text = validText("new_salt")
        val half = text.length / 2
        assertAllRefused(
            listOf(
                "raw line feed in a value" to withText("new_salt", text.substring(0, half) + "\n" + text.substring(half)),
                "raw tab in a value" to withText("new_salt", text.substring(0, half) + "\t" + text.substring(half)),
                "raw control character 1 in a value" to withText("new_salt", text.substring(0, half) + "\u0001" + text.substring(half)),
                "unknown escape" to withText("new_salt", text.substring(0, half) + "\\q" + text.substring(half)),
                "short unicode escape" to withText("new_salt", text.substring(0, half) + "\\u12" + text.substring(half)),
                "bad unicode escape" to withText("new_salt", text.substring(0, half) + "\\uZZZZ" + text.substring(half)),
                "signed unicode escape" to withText("new_salt", text.substring(0, half) + "\\u+041" + text.substring(half)),
                "lone backslash at the end" to withText("new_salt", text + "\\"),
            ),
        )
    }

    @Test
    fun `a body with JSON whitespace on both sides is accepted`() {
        val id = keyedAccount("alice", 1)
        val reply = postChange(sessionToken(id), " \t\r\n" + validChangeBody() + "\n\r\t ")
        assertAnswer("body with whitespace on both sides", reply, HttpStatusCode.OK, """{"key_version":2}""")
    }

    @Test
    fun `a unicode escape in a value is decoded and the value is stored`() {
        val id = keyedAccount("alice", 1)
        val text = validText("new_salt")
        val escaped = "\\u%04x".format(text[0].code) + text.substring(1)
        val reply = postChange(sessionToken(id), withText("new_salt", escaped))
        assertAnswer("body with an escaped first character", reply, HttpStatusCode.OK, """{"key_version":2}""")
        assertArrayEquals("sync-api: stored salt", newSaltBytes, readCredentialColumns(temp, id).salt)
    }
}
