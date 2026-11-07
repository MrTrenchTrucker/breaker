package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** sealed_package must be standard base64: plus and slash accepted; dash and
 * underscore refused as BAD_BASE64. */
class GapExchangeDecoderStandardBase64Test {

    private val SESSION_TEXT = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"

    private fun bodyOf(sealed: String): String {
        return "{\"token\":\"" + SESSION_TEXT + "\"," +
            "\"role\":\"user\",\"key_version\":0," +
            "\"sealed_package\":\"" + sealed + "\"," +
            "\"username\":\"alice\",\"account_id\":1}"
    }

    @Test
    fun `a sealed package with plus and slash is accepted as standard base64`() {
        // hello>world and hello?world: standard base64 writes the sixth character as plus and slash
        assertNotNull("a plus decodes", ExchangeDecoder.parseExchange(bodyOf("aGVsbG8+d29ybGQ=")))
        assertNotNull("a slash decodes", ExchangeDecoder.parseExchange(bodyOf("aGVsbG8/d29ybGQ=")))
    }

    @Test
    fun `a sealed package with dash hyphen is refused as bad base64`() {
        assertEquals("dash is not standard base64, family is BAD_BASE64",
            "BAD_BASE64",
            assertThrows(AuthDecodeException::class.java) {
                ExchangeDecoder.parseExchange(bodyOf("aGVsbG8-d29ybGQ="))
            }.family)
    }

    @Test
    fun `a sealed package with underscore is refused as bad base64`() {
        assertEquals("underscore is not standard base64, family is BAD_BASE64",
            "BAD_BASE64",
            assertThrows(AuthDecodeException::class.java) {
                ExchangeDecoder.parseExchange(bodyOf("aGVsbG8_d29ybGQ="))
            }.family)
    }

    @Test
    fun `a missing key version is refused as missing key`() {
        val without = bodyOf("aGVsbG8=").replace("\"key_version\":0,", "")
        assertEquals("missing key version carries MISSING_KEY",
            "MISSING_KEY",
            assertThrows(AuthDecodeException::class.java) {
                ExchangeDecoder.parseExchange(without)
            }.family)
    }
}
