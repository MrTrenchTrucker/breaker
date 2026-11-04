package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in link builder: the exact shape of the link the website page accepts, every
 * refusal with all the other parts valid, the order of the checks, and the rule for a wiped
 * attempt.
 */
class SignInLinkTest {
    private val challengeText = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    // 32 bytes of 0xFF: a low-entropy key whose base64url text holds "_", so a standard (non url-safe)
    // encoder would write "/" and fail the exact link checks.
    private val keyText = "__________________________________________8"
    private val tail = "/signin.html#challenge=$challengeText&app_key=$keyText"

    private fun attempt(): SignInAttempt = SignInAttempt(
        ByteArray(43) { 'A'.code.toByte() },
        SignInPrivateKey(ByteArray(32) { 0x11.toByte() }),
        java.util.Base64.getUrlDecoder().decode(keyText),
        challengeText,
    )

    /** The link for a server that must be accepted. */
    private fun urlOf(server: String): String {
        val result = buildSignInLink(server, attempt())
        assertTrue("the server $server must be accepted", result is SignInLinkResult.Link)
        return (result as SignInLinkResult.Link).url
    }

    /** The refusal reason for a server that must be refused. */
    private fun reasonOf(server: String, given: SignInAttempt = attempt()): SignInLinkRefusal {
        val result = buildSignInLink(server, given)
        assertTrue("the server $server must be refused", result is SignInLinkResult.Refused)
        return (result as SignInLinkResult.Refused).reason
    }

    @Test
    fun `the link has the two names in the fragment and no query`() {
        val url = urlOf("http://h:8080")

        assertEquals("the link is not the exact shape", "http://h:8080$tail", url)
        assertEquals("the fragment must hold exactly two pairs", 2, url.substringAfter('#').split('&').size)
        assertFalse("the link has a query", url.contains('?'))
    }

    @Test
    fun `a trailing slash gives the same link as no slash`() {
        assertEquals("http://h:8080$tail", urlOf("http://h:8080/"))
        assertEquals("http://h:8080$tail", urlOf("http://h:8080///"))
        assertEquals("http://h:8080$tail", urlOf("http://h:8080"))
    }

    @Test
    fun `https with a path prefix keeps the prefix and drops the trailing slash`() {
        assertEquals("https://h/breaker$tail", urlOf("https://h/breaker"))
        assertEquals("https://h/breaker$tail", urlOf("https://h/breaker/"))
        assertEquals("https://h/breaker$tail", urlOf("https://h/breaker//"))
        assertEquals("https://h/a/b$tail", urlOf("https://h/a/b/"))
    }

    @Test
    fun `the scheme is lower-cased and the host case is kept`() {
        assertEquals("https://H$tail", urlOf("HTTPS://H"))
        assertEquals("http://Host.Example$tail", urlOf("Http://Host.Example"))
    }

    @Test
    fun `http and https with a plain host are both accepted`() {
        assertEquals("http://h$tail", urlOf("http://h"))
        assertEquals("https://h$tail", urlOf("https://h"))
    }

    @Test
    fun `a host with a dash and an underscore and a port of one or five digits is accepted`() {
        assertEquals("http://my-host_1.local:65535$tail", urlOf("http://my-host_1.local:65535"))
        assertEquals("http://h:1$tail", urlOf("http://h:1"))
    }

    @Test
    fun `outer whitespace is trimmed`() {
        assertEquals("http://h$tail", urlOf("  http://h  "))
    }

    @Test
    fun `an empty server or one of spaces only is refused as no server`() {
        assertEquals(SignInLinkRefusal.NO_SERVER, reasonOf(""))
        assertEquals(SignInLinkRefusal.NO_SERVER, reasonOf("   "))
    }

    @Test
    fun `a scheme other than http or https is refused as not http`() {
        for (server in listOf("ftp://h", "breaker://h", "httpx://h", "h:8080", "http:h")) {
            assertEquals("the server $server must be refused as not http", SignInLinkRefusal.NOT_HTTP, reasonOf(server))
        }
    }

    @Test
    fun `a bad address is refused with every other part valid`() {
        val servers = listOf(
            "http://", "http:///path", "http://h!x", "http://h x", "http://h\tx", "http://h\u0001",
            "http://[::1]:8080", "http://h:", "http://h:123456", "http://h:80a", "http://:8080",
            "http://h/a//b", "http://h/a/./b", "http://h/a/../b", "http://h/..", "http://h/.",
        )
        for (server in servers) {
            assertEquals("the server $server must be refused as a bad address", SignInLinkRefusal.BAD_ADDRESS, reasonOf(server))
        }
    }

    @Test
    fun `a query or a fragment is refused with every other part valid`() {
        val servers = listOf("http://h?x=1", "http://h#x", "http://h/a?b", "http://h/a#b", "https://h/breaker?x", "http://h:8080/?")
        for (server in servers) {
            assertEquals("the server $server must be refused for a query or fragment", SignInLinkRefusal.HAS_QUERY_OR_FRAGMENT, reasonOf(server))
        }
    }

    @Test
    fun `user info is refused with every other part valid`() {
        val servers = listOf("http://u@h", "http://u:p@h:8080", "http://@h", "https://u@h/breaker/")
        for (server in servers) {
            assertEquals("the server $server must be refused for user info", SignInLinkRefusal.HAS_USER_INFO, reasonOf(server))
        }
    }

    @Test
    fun `the first check that fails is the one reported`() {
        assertEquals(SignInLinkRefusal.HAS_QUERY_OR_FRAGMENT, reasonOf("http://u@h?x"))
        assertEquals(SignInLinkRefusal.NOT_HTTP, reasonOf("ftp://u@h"))
        assertEquals(SignInLinkRefusal.HAS_USER_INFO, reasonOf("http://u@[::1]"))
    }

    @Test
    fun `a wiped attempt makes no link and throws`() {
        val given = attempt()
        given.wipe()

        val caught = assertThrows(IllegalStateException::class.java) { buildSignInLink("http://h:8080", given) }

        assertEquals("The sign-in attempt was wiped", caught.message)
    }

    @Test
    fun `a bad server with a wiped attempt is still refused and does not throw`() {
        val given = attempt()
        given.wipe()

        assertEquals(SignInLinkRefusal.NO_SERVER, reasonOf("", given))
    }

    @Test
    fun `the result prints a fixed text and two links with the same url are different objects`() {
        val link = buildSignInLink("http://h:8080", attempt())

        assertEquals("SignInLinkResult.Link(redacted)", link.toString())
        assertFalse("the link text shows the challenge", link.toString().contains(challengeText))
        assertEquals("SignInLinkResult.Refused(NO_SERVER)", buildSignInLink("", attempt()).toString())
        assertNotEquals(
            "two links with the same url compare equal",
            buildSignInLink("http://h:8080", attempt()),
            buildSignInLink("http://h:8080", attempt()),
        )
    }
}
