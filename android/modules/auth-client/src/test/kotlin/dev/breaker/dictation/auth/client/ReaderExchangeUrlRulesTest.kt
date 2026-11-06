package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for ExchangeUrl, read from the AccountRules KDoc and the openapi contract. */
class ReaderExchangeUrlRulesTest {
    private val exchangePath = "/v1/auth/handoff/exchange"

    @Test
    fun `an https base is accepted and the exchange path is added`() {
        assertEquals("https://box.example" + exchangePath, exchangeUrl("https://box.example"))
    }

    @Test
    fun `an http base is accepted and the exchange path is added`() {
        assertEquals("http://box.example" + exchangePath, exchangeUrl("http://box.example"))
    }

    @Test
    fun `one trailing slash is dropped from the base before the path is added`() {
        assertEquals("https://box.example" + exchangePath, exchangeUrl("https://box.example/"))
    }

    @Test
    fun `two trailing slashes are refused`() {
        assertNull(exchangeUrl("https://box.example//"))
    }

    @Test
    fun `an address with a numeric host a port and a path keeps all three before the exchange path`() {
        val url = exchangeUrl("http://10.0.0.5:9000/breaker")
        assertEquals("http://10.0.0.5:9000/breaker" + exchangePath, url)
    }

    @Test
    fun `accept control a scheme other than http or https is refused https is the control`() {
        assertNull(exchangeUrl("ftp://box.example"))
        assertNull(exchangeUrl("ws://box.example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control a backslash in the base is refused the plain host is the control`() {
        assertNull(exchangeUrl("https://box\\example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control a question mark in the base is refused the plain host is the control`() {
        assertNull(exchangeUrl("https://box.example?x=1"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control a hash in the base is refused the plain host is the control`() {
        assertNull(exchangeUrl("https://box.example#x"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control an empty authority is refused a host is the control`() {
        assertNull(exchangeUrl("https:///box.example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control an authority starting with a colon is refused a host is the control`() {
        assertNull(exchangeUrl("https://:8080"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control an at sign in the authority is refused a host is the control`() {
        assertNull(exchangeUrl("https://user@box.example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control whitespace after the scheme is refused the plain host is the control`() {
        assertNull(exchangeUrl("https:// box.example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }

    @Test
    fun `accept control a control character after the scheme is refused the plain host is the control`() {
        assertNull(exchangeUrl("https://box\u0001.example"))
        assertNotNull(exchangeUrl("https://box.example"))
    }
}
