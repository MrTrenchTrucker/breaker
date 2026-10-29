package dev.breaker.server.syncapi.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The strict reader of one push body, tested without the HTTP layer. */
internal class PushRequestReaderTest {

    private val enc = "AQID"

    private fun parse(text: String): PushRequest? = parsePushRequest(utf8(text))

    @Test
    fun `a well formed push object is read into client id, key version and text`() {
        val parsed = parse(pushBody(CLIENT_ID, "3", enc))
        assertEquals(CLIENT_ID, parsed?.clientId)
        assertEquals(3, parsed?.keyVersion)
        assertEquals(enc, parsed?.encrypted)
    }

    @Test
    fun `keys may arrive in any order and JSON whitespace is allowed around them`() {
        val text = " {\n\t\"encrypted\" : \"$enc\" ,\r\n  \"key_version\":0,\"client_id\":\"$CLIENT_ID\" }\n"
        val parsed = parse(text)
        assertEquals(CLIENT_ID, parsed?.clientId)
        assertEquals(0, parsed?.keyVersion)
        assertEquals(enc, parsed?.encrypted)
    }

    @Test
    fun `a byte order mark before the object is refused`() {
        assertNull("a leading byte order mark", parse("\uFEFF" + pushBody(CLIENT_ID, "0", enc)))
    }

    @Test
    fun `bytes that are not valid UTF-8 are refused`() {
        val valid = utf8(pushBody(CLIENT_ID, "0", enc))
        assertNull("an invalid lead byte", parsePushRequest(byteArrayOf(0xFF.toByte()) + valid))
    }

    @Test
    fun `bytes after the closing brace are refused`() {
        assertNull("a letter after the object", parse(pushBody(CLIENT_ID, "0", enc) + " x"))
        assertNull("a second object", parse(pushBody(CLIENT_ID, "0", enc) + "{}"))
    }

    @Test
    fun `a duplicate key is refused`() {
        val text = """{"client_id":"$CLIENT_ID","client_id":"$CLIENT_ID","key_version":0,"encrypted":"$enc"}"""
        assertNull(parse(text))
    }

    @Test
    fun `an unknown key is refused`() {
        val text = """{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"$enc","extra":1}"""
        assertNull(parse(text))
    }

    @Test
    fun `a missing key is refused`() {
        assertNull("no key_version", parse("""{"client_id":"$CLIENT_ID","encrypted":"$enc"}"""))
        assertNull("no client_id", parse("""{"key_version":0,"encrypted":"$enc"}"""))
        assertNull("no encrypted", parse("""{"client_id":"$CLIENT_ID","key_version":0}"""))
    }

    @Test
    fun `key version with a sign, a fraction, an exponent or a leading zero is refused`() {
        for (version in listOf("-1", "-0", "+1", "1.0", "0.0", "1e0", "1E2", "01")) {
            assertNull("key_version $version", parse(pushBody(CLIENT_ID, version, enc)))
        }
    }

    @Test
    fun `key version zero and the largest Int are accepted and one above the largest Int is refused`() {
        assertEquals(0, parse(pushBody(CLIENT_ID, "0", enc))?.keyVersion)
        assertEquals(Int.MAX_VALUE, parse(pushBody(CLIENT_ID, "2147483647", enc))?.keyVersion)
        assertNull(parse(pushBody(CLIENT_ID, "2147483648", enc)))
    }

    @Test
    fun `a value of the wrong JSON type for its key is refused`() {
        assertNull("client id as a number", parse("""{"client_id":1,"key_version":0,"encrypted":"$enc"}"""))
        assertNull("key version as a string", parse("""{"client_id":"$CLIENT_ID","key_version":"0","encrypted":"$enc"}"""))
        assertNull("encrypted as a number", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":7}"""))
        assertNull("encrypted as null", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":null}"""))
        assertNull("key version as an object", parse("""{"client_id":"$CLIENT_ID","key_version":{},"encrypted":"$enc"}"""))
    }

    @Test
    fun `an empty encrypted text is refused`() {
        assertNull(parse(pushBody(CLIENT_ID, "0", "")))
    }

    @Test
    fun `a lone surrogate escape in a string is refused and a surrogate pair is accepted`() {
        assertNull("a lone high surrogate", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800"}"""))
        assertNull("a high surrogate then a non surrogate", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800A"}"""))
        assertNull("a lone low surrogate", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uDC00"}"""))
        val pair = parse("{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"\\uD83D\\uDE00\"}")
        assertEquals("a surrogate pair is one character", "\uD83D\uDE00", pair?.encrypted)
    }

    @Test
    fun `a high surrogate escape followed by something other than a low surrogate escape is refused`() {
        assertNull("a plain letter", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800A"}"""))
        assertNull("a non low escape", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800\u0041"}"""))
        assertNull("the letter u and digits without a backslash", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800uDC00"}"""))
        assertNull("the missing backslash before a low surrogate escape", parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"\uD800XuDC00"}"""))
    }

    @Test
    fun `an escaped slash in the encrypted text is read as the slash character`() {
        val parsed = parse("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"AB\/CD"}""")
        assertEquals("AB/CD", parsed?.encrypted)
    }

    @Test
    fun `an empty body, an array, a lone brace and a truncated object are refused without an exception`() {
        assertNull("an empty body", parse(""))
        assertNull("an array", parse("[]"))
        assertNull("a lone brace", parse("{"))
        assertNull("a truncated object", parse(pushBody(CLIENT_ID, "0", enc).dropLast(1)))
    }

    @Test
    fun `the text form of a parsed request names the client id and key version and no encrypted text`() {
        val parsed = parse(pushBody(CLIENT_ID, "0", "ZW5jcnlwdGVkLW1hcmtlcg=="))
        assertEquals("PushRequest(clientId=$CLIENT_ID, keyVersion=0)", parsed.toString())
        assertFalse(parsed.toString().contains("ZW5jcnlwdGVk"))
    }
}
