package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRulesTest {
    private val salt = "/v1/auth/salt"

    private fun endpoint(base: String): String? = AccountRules.endpoint(base, salt)

    @Test
    fun `an https base joins the account path after it`() {
        assertEquals("https://box.example/v1/auth/salt", endpoint("https://box.example"))
    }

    @Test
    fun `the https scheme is accepted in any letter case`() {
        assertTrue("an upper-case scheme was refused", endpoint("HTTPS://box.example") != null)
        assertTrue("a mixed-case scheme was refused", endpoint("Https://box.example") != null)
    }

    @Test
    fun `a plain http base is refused in any letter case`() {
        assertNull("a lower-case http base was accepted", endpoint("http://box.example"))
        assertNull("an upper-case http base was accepted", endpoint("HTTP://box.example"))
    }

    @Test
    fun `a look-alike non-ASCII letter in the scheme is refused`() {
        // The long-s letter upper-cases to S, so a case-folding compare would accept this spelling of https.
        assertNull("the long-s spelling of the scheme was accepted", endpoint("http\u017F://box.example"))
    }

    @Test
    fun `a blank or empty base is refused`() {
        assertNull("an empty base was accepted", endpoint(""))
        assertNull("a blank base was accepted", endpoint("   "))
    }

    @Test
    fun `a base with a space after the scheme is refused`() {
        assertNull("a space after the scheme was accepted", endpoint("https://box example"))
    }

    @Test
    fun `a base with a control character after the scheme is refused`() {
        assertNull("a control character after the scheme was accepted", endpoint("https://box\u0007.example"))
    }

    @Test
    fun `a base with a tab or a line break after the scheme is refused`() {
        assertNull("a tab after the scheme was accepted", endpoint("https://box\tx"))
        assertNull("a line break after the scheme was accepted", endpoint("https://box\nx"))
    }

    @Test
    fun `trailing slashes on the base never change the url before the path`() {
        val expected = "https://box.example/v1/auth/salt"
        assertEquals("no trailing slash", expected, endpoint("https://box.example"))
        assertEquals("one trailing slash", expected, endpoint("https://box.example/"))
        assertEquals("two trailing slashes", expected, endpoint("https://box.example//"))
    }

    @Test
    fun `a base with two trailing slashes never gives a double slash before the path`() {
        val url = endpoint("https://box.example//") ?: ""
        assertEquals("the url changed", "https://box.example/v1/auth/salt", url)
        assertTrue("the url has a double slash before the path", !url.contains("//v1"))
    }

    @Test
    fun `a base with an empty host is refused whatever its slashes`() {
        assertNull("a base with three slashes and no host was accepted", endpoint("https:///"))
        assertNull("a base with only slashes after the scheme was accepted", endpoint("https://///"))
        assertNull("a base with nothing after the scheme was accepted", endpoint("https://"))
    }

    @Test
    fun `an address with an empty authority is refused`() {
        assertNull("an empty authority was accepted", endpoint("https:///x"))
    }

    @Test
    fun `an address with a leading colon in the authority is refused`() {
        assertNull("a leading colon in the authority was accepted", endpoint("https://:443"))
    }

    @Test
    fun `an address with an at sign in the authority is refused`() {
        assertNull("an at sign in the authority was accepted", endpoint("https://user@host"))
    }

    @Test
    fun `an address with a question mark is refused`() {
        assertNull("a question mark was accepted", endpoint("https://host?x"))
    }

    @Test
    fun `an address with a hash is refused`() {
        assertNull("a hash was accepted", endpoint("https://host#x"))
    }

    @Test
    fun `an address with a port or a sub-path is accepted`() {
        assertEquals("a port was refused or changed", "https://host:8443/v1/auth/salt", endpoint("https://host:8443"))
        assertEquals("a sub-path was refused or changed", "https://host/sub/v1/auth/salt", endpoint("https://host/sub/"))
    }
}
