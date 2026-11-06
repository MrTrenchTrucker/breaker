package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The URL of the hand-off exchange: the same address rule as every other account call. */
class ExchangeUrlTest {
    @Test
    fun `the url is the base joined with the exchange path`() {
        assertEquals(EXCHANGE_URL, exchangeUrl(BASE_URL))
        assertEquals("/v1/auth/handoff/exchange", AccountRules.EXCHANGE_PATH)
    }

    @Test
    fun `one trailing slash on the base is dropped`() {
        assertEquals(EXCHANGE_URL, exchangeUrl("https://box.example/"))
    }

    @Test
    fun `a port and a path under the base are kept`() {
        assertEquals(
            "http://box.example:8080/api/v1/auth/handoff/exchange",
            exchangeUrl("http://box.example:8080/api"),
        )
    }

    @Test
    fun `the scheme letters may be upper case and the base is otherwise kept as given`() {
        assertEquals("HTTPS://Box.Example/v1/auth/handoff/exchange", exchangeUrl("HTTPS://Box.Example"))
    }

    @Test
    fun `every refused base gives null and the case is named`() {
        val cases = mapOf(
            "empty" to "",
            "no scheme" to "box.example",
            "other scheme" to "ftp://box.example",
            "scheme only" to "https://",
            "no host" to "https:///x",
            "colon first" to "https://:8080",
            "user info" to "https://user@box.example",
            "query" to "https://box.example?a=b",
            "hash" to "https://box.example#a",
            "backslash" to "https://box.example\\x",
            "space" to "https://box. example",
            "newline" to "https://box.example\n",
            "two trailing slashes" to "https://box.example//",
        )
        for ((name, address) in cases) {
            assertNull("case " + name, exchangeUrl(address))
        }
    }
}
