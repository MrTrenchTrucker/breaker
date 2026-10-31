package dev.breaker.dictation.stt.ondevice

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheme-only tests for [isHttpsScheme] and [DownloadLimits.isAllowed] -- the https
 * look-alike guard.
 *
 * The scheme word must be plain ASCII "https" (length 5, case-insensitive). A
 * long-s look-alike such as "http\u017F" (U+017F uppercases to S) must be refused,
 * while "HTTPS" and "https" are accepted.
 *
 * On the BASE code (before the isHttpsScheme helper existed) these tests do not
 * compile; after the fix they are GREEN.
 */
class DownloadLimitsSchemeTest {

    private val limits = DownloadLimits()

    // ---------- isHttpsScheme directly ----------

    @Test
    fun `the long-s https look-alike scheme is refused`() {
        // \u017F (long s) uppercases to S, so a case-insensitive equals would
        // wrongly accept it. isHttpsScheme rejects it outright.
        assertFalse("isHttpsScheme must refuse a long-s look-alike", isHttpsScheme("http\u017F"))
    }

    @Test
    fun `a real https scheme is accepted in any ASCII case`() {
        assertTrue("isHttpsScheme accepts lowercase", isHttpsScheme("https"))
        assertTrue("isHttpsScheme accepts uppercase", isHttpsScheme("HTTPS"))
        assertTrue("isHttpsScheme accepts mixed case", isHttpsScheme("Https"))
    }

    @Test
    fun `a scheme that is not exactly https is refused`() {
        assertFalse("isHttpsScheme refuses http", isHttpsScheme("http"))
        assertFalse("isHttpsScheme refuses httpss", isHttpsScheme("httpss"))
        assertFalse("isHttpsScheme refuses ftp", isHttpsScheme("ftp"))
    }

    // ---------- isAllowed through DownloadLimits (mirrors DownloadPolicyContractTest) ----------

    @Test
    fun `an upper-case HTTPS scheme is accepted as the first request`() {
        assertTrue(
            "policy: upper-case HTTPS on the first hop",
            limits.isAllowed(URI("HTTPS://api.github.com/x"), 0),
        )
    }

    @Test
    fun `a plain http scheme on a first-hop host is refused`() {
        assertFalse(
            "policy: http must be refused even on an allowed host",
            limits.isAllowed(URI("http://api.github.com/x"), 0),
        )
    }
}
