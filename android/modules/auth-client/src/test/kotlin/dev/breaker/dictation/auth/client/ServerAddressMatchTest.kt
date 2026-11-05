package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressMatchTest {
    private val base = "https://h.example"

    @Test
    fun `an identical address is the same server`() {
        assertTrue("an identical address did not match", ServerAddressMatch.same(base, base))
    }

    @Test
    fun `a stored address with one trailing slash matches the current one without`() {
        assertTrue("a stored trailing slash did not match", ServerAddressMatch.same("$base/", base))
    }

    @Test
    fun `a current address with one trailing slash matches the stored one without`() {
        assertTrue("a current trailing slash did not match", ServerAddressMatch.same(base, "$base/"))
    }

    @Test
    fun `a scheme in capitals is the same server`() {
        assertTrue("a capital scheme did not match", ServerAddressMatch.same(base, "HTTPS://h.example"))
    }

    @Test
    fun `a host in mixed case is the same server`() {
        assertTrue("a mixed case host did not match", ServerAddressMatch.same(base, "https://H.Example"))
    }

    @Test
    fun `two trailing slashes against none are another server`() {
        assertFalse("two slashes matched none", ServerAddressMatch.same("$base//", base))
        assertFalse("none matched two slashes", ServerAddressMatch.same(base, "$base//"))
    }

    @Test
    fun `two trailing slashes against one are another server`() {
        assertFalse("two slashes matched one", ServerAddressMatch.same("$base//", "$base/"))
        assertFalse("one slash matched two", ServerAddressMatch.same("$base/", "$base//"))
    }

    @Test
    fun `another host is another server`() {
        assertFalse("another host matched", ServerAddressMatch.same(base, "https://other.example"))
    }

    @Test
    fun `another port is another server`() {
        assertFalse("another port matched", ServerAddressMatch.same("$base:8443", "$base:9443"))
    }

    @Test
    fun `an address with the default port written out is another server`() {
        assertFalse("a written port matched none", ServerAddressMatch.same("https://h", "https://h:443"))
        assertFalse("none matched a written port", ServerAddressMatch.same("https://h:443", "https://h"))
    }

    @Test
    fun `another path is another server`() {
        assertFalse("another path matched", ServerAddressMatch.same("$base/a", "$base/b"))
    }

    @Test
    fun `a path in another case is another server`() {
        assertFalse("a path in another case matched", ServerAddressMatch.same("$base/Path", "$base/path"))
    }

    @Test
    fun `another query or fragment is another server`() {
        assertFalse("another query matched", ServerAddressMatch.same("$base?a=1", "$base?a=2"))
        assertFalse("another fragment matched", ServerAddressMatch.same("$base#a", "$base#b"))
    }

    @Test
    fun `http against https is another server`() {
        assertFalse("another scheme matched", ServerAddressMatch.same(base, "http://h.example"))
    }

    @Test
    fun `an empty stored address matches nothing`() {
        assertFalse("an empty stored address matched", ServerAddressMatch.same("", base))
    }

    @Test
    fun `an empty current address matches nothing`() {
        assertFalse("an empty current address matched", ServerAddressMatch.same(base, ""))
    }

    @Test
    fun `two empty addresses do not match`() {
        assertFalse("two empty addresses matched", ServerAddressMatch.same("", ""))
    }

    @Test
    fun `an address without a scheme separator never matches even when equal`() {
        assertFalse("an equal text without a scheme matched", ServerAddressMatch.same("h.example", "h.example"))
    }

    @Test
    fun `an address with an empty scheme never matches`() {
        assertFalse("an empty scheme matched", ServerAddressMatch.same("://h.example", "://h.example"))
    }

    @Test
    fun `an address with an empty authority never matches`() {
        assertFalse("an empty authority matched", ServerAddressMatch.same("https:///x", "https:///x"))
    }

    @Test
    fun `an address with user info never matches even when equal`() {
        assertFalse("user info matched", ServerAddressMatch.same("https://u@h.example", "https://u@h.example"))
    }

    @Test
    fun `a leading or trailing space on one side is another server`() {
        assertFalse("a trailing space matched", ServerAddressMatch.same(base, "$base "))
        assertFalse("a leading space matched", ServerAddressMatch.same(" $base", base))
    }

    @Test
    fun `an address with an empty host and a port never matches even when equal`() {
        assertFalse("an empty host with port 443 matched", ServerAddressMatch.same("https://:443", "https://:443"))
        assertFalse("an empty host with port 8443 matched", ServerAddressMatch.same("https://:8443", "https://:8443"))
    }

    @Test
    fun `an address with a backslash never matches even when equal`() {
        val text = "https://good\\evil"
        assertFalse("a backslash address matched itself", ServerAddressMatch.same(text, text))
        assertFalse("a backslash address matched the host before it", ServerAddressMatch.same(text, "https://good"))
    }

    @Test
    fun `an address with a space inside never matches even when equal`() {
        assertFalse("a space address matched itself", ServerAddressMatch.same("https://h x", "https://h x"))
    }

    @Test
    fun `an address with a tab or a line break inside never matches even when equal`() {
        assertFalse("a tab address matched itself", ServerAddressMatch.same("https://h\tx", "https://h\tx"))
        assertFalse("a line break address matched itself", ServerAddressMatch.same("https://h\nx", "https://h\nx"))
    }

    @Test
    fun `an address with a control character inside never matches even when equal`() {
        assertFalse("a control address matched itself", ServerAddressMatch.same("https://h\u0007x", "https://h\u0007x"))
    }

    @Test
    fun `a space inside one side only is another server`() {
        assertFalse("a space on the current side matched", ServerAddressMatch.same(base, "https://h .example"))
        assertFalse("a space on the stored side matched", ServerAddressMatch.same("https://h .example", base))
    }

    @Test
    fun `dropOneSlash drops exactly one trailing slash`() {
        assertEquals("one slash was not dropped", "https://h", ServerAddressMatch.dropOneSlash("https://h/"))
        assertEquals("two slashes lost more than one", "https://h/", ServerAddressMatch.dropOneSlash("https://h//"))
        assertEquals("no slash changed the text", "https://h", ServerAddressMatch.dropOneSlash("https://h"))
    }
}
