package dev.breaker.dictation.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address rule of the settings screen, checked with literal texts only.
 *
 * Every refused text is paired with a control: the same text made valid, which must be
 * accepted and stored exactly as written. Every boundary is tested at both edges.
 */
class ServerAddressEntryTest {
    private fun refusedWithControl(refused: String, control: String) {
        assertNull("refused: $refused", ServerAddressEntry.accepted(refused))
        assertEquals("control for $refused", control, ServerAddressEntry.accepted(control))
    }

    private fun storedAs(typed: String, expected: String) {
        assertEquals("stored for $typed", expected, ServerAddressEntry.accepted(typed))
    }

    @Test
    fun `a plain https address is stored as typed`() {
        storedAs("https://box.example", "https://box.example")
    }

    @Test
    fun `an http address with an address and a port is stored as typed`() {
        storedAs("http://192.0.2.20:8080", "http://192.0.2.20:8080")
    }

    @Test
    fun `an address with a path is stored as typed`() {
        storedAs("https://box.example/api", "https://box.example/api")
    }

    @Test
    fun `a single trailing slash after the host is stored as typed`() {
        storedAs("https://box.example/", "https://box.example/")
    }

    @Test
    fun `a trailing slash after a path segment is stored as typed`() {
        storedAs("https://box.example/api/", "https://box.example/api/")
    }

    @Test
    fun `the scheme and the host keep their letter case`() {
        storedAs("HTTP://Box.Example", "HTTP://Box.Example")
    }

    @Test
    fun `an underscore in the host is accepted`() {
        storedAs("http://box_1.example:65535", "http://box_1.example:65535")
    }

    @Test
    fun `surrounding spaces are cut and the rest is stored as typed`() {
        storedAs(" https://box.example ", "https://box.example")
    }

    @Test
    fun `surrounding tabs and line breaks are cut as well`() {
        storedAs("\thttps://box.example\n", "https://box.example")
    }

    @Test
    fun `an empty text clears the address`() {
        storedAs("", "")
    }

    @Test
    fun `a text of spaces only clears the address`() {
        storedAs("   ", "")
    }

    @Test
    fun `a text of tabs and line breaks only clears the address`() {
        storedAs("\t \n", "")
    }

    @Test
    fun `a text with no scheme is refused`() {
        refusedWithControl("box.example", "https://box.example")
    }

    @Test
    fun `a scheme other than http or https is refused`() {
        refusedWithControl("ftp://box.example", "https://box.example")
    }

    @Test
    fun `a scheme separator with a single slash is refused`() {
        refusedWithControl("https:/box.example", "https://box.example")
    }

    @Test
    fun `an empty scheme is refused`() {
        refusedWithControl("://box.example", "http://box.example")
    }

    @Test
    fun `a scheme with no host is refused`() {
        refusedWithControl("https://", "https://box.example")
    }

    @Test
    fun `an address with no host before the port is refused`() {
        refusedWithControl("https://:8080", "https://box.example:8080")
    }

    @Test
    fun `a colon with no port after it is refused`() {
        refusedWithControl("https://box.example:", "https://box.example:8080")
    }

    @Test
    fun `a port of zero is refused and a port of one is accepted`() {
        refusedWithControl("https://box.example:0", "https://box.example:1")
        storedAs("https://box.example:1", "https://box.example:1")
    }

    @Test
    fun `a port of 65536 is refused and a port of 65535 is accepted`() {
        refusedWithControl("https://box.example:65536", "https://box.example:65535")
        storedAs("https://box.example:65535", "https://box.example:65535")
    }

    @Test
    fun `a port of six digits is refused`() {
        refusedWithControl("https://box.example:123456", "https://box.example:12345")
    }

    @Test
    fun `a port with a letter in it is refused`() {
        refusedWithControl("https://box.example:80a", "https://box.example:80")
    }

    @Test
    fun `a second colon in the host part is refused`() {
        refusedWithControl("https://box.example:8080:9090", "https://box.example:9090")
    }

    @Test
    fun `a user name before the host is refused`() {
        refusedWithControl("https://u@box.example", "https://box.example")
    }

    @Test
    fun `an at sign in the path is refused`() {
        refusedWithControl("https://box.example/a@b", "https://box.example/a-b")
    }

    @Test
    fun `a query after the host is refused`() {
        refusedWithControl("https://box.example?x", "https://box.example/x")
    }

    @Test
    fun `a fragment after the host is refused`() {
        refusedWithControl("https://box.example#x", "https://box.example/x")
    }

    @Test
    fun `an IPv6 literal in brackets is refused`() {
        refusedWithControl("http://[::1]", "http://localhost")
    }

    @Test
    fun `a doubled slash at the end of the path is refused`() {
        refusedWithControl("https://box.example//", "https://box.example/")
    }

    @Test
    fun `an empty segment in the middle of the path is refused`() {
        refusedWithControl("https://box.example/a//b", "https://box.example/a/b")
    }

    @Test
    fun `a single dot segment in the path is refused`() {
        refusedWithControl("https://box.example/./a", "https://box.example/a")
    }

    @Test
    fun `a double dot segment in the path is refused`() {
        refusedWithControl("https://box.example/../a", "https://box.example/a")
    }

    @Test
    fun `a double dot segment at the end of the path is refused`() {
        refusedWithControl("https://box.example/..", "https://box.example/a")
    }

    @Test
    fun `a slash with no host after the scheme is refused`() {
        refusedWithControl("https:///", "https://box.example/")
    }

    @Test
    fun `a space inside the host is refused`() {
        refusedWithControl("https://bo x.example", "https://box.example")
    }

    @Test
    fun `a space inside the path is refused`() {
        refusedWithControl("https://box.example/a b", "https://box.example/a-b")
    }

    @Test
    fun `a tab inside the address is refused`() {
        refusedWithControl("https://box\tlocal", "https://box.example")
    }

    @Test
    fun `a control character inside the address is refused`() {
        refusedWithControl("https://box\u0001.example", "https://box.example")
    }

    @Test
    fun `a character outside ASCII in the host is refused`() {
        refusedWithControl("https://b\u00F8x.example", "https://box.example")
    }

    @Test
    fun `a host character outside the allowed set is refused`() {
        refusedWithControl("https://box!.example", "https://box.example")
    }

    @Test
    fun `a query inside the path is refused`() {
        refusedWithControl("https://box.example/a?x", "https://box.example/a")
    }

    @Test
    fun `a fragment inside the path is refused`() {
        refusedWithControl("https://box.example/a#x", "https://box.example/a")
    }

    @Test
    fun `a control character inside the path is refused`() {
        refusedWithControl("https://box.example/a\u0001", "https://box.example/a")
    }

    @Test
    fun `a character outside ASCII inside the path is refused`() {
        refusedWithControl("https://box.example/a\u00F8", "https://box.example/a")
    }

    @Test
    fun `an address of 2000 characters is accepted and 2001 are refused`() {
        val base = "https://box.example/"
        val longest = base + "a".repeat(1980)
        assertEquals(2000, longest.length)
        storedAs(longest, longest)
        assertNull("2001 characters", ServerAddressEntry.accepted(longest + "a"))
    }

    @Test
    fun `the stored text is the trimmed text and nothing else is changed`() {
        storedAs("  HTTP://Box.Example/Api/  ", "HTTP://Box.Example/Api/")
    }
}
