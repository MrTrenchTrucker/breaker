package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scheme-only tests for [ServerEndpoint.addressProblem] -- the https look-alike guard.
 *
 * The scheme word must be plain ASCII "https". A long-s look-alike such as
 * "http\u017F://" (U+017F uppercases to S, so the old regionMatches guard accepted
 * it) must be refused with ADDRESS_NOT_HTTPS, while real https addresses in any
 * ASCII case are accepted.
 */
class ServerEndpointSchemeTest {

    @Test
    fun `a long-s https look-alike is refused with the https sentence`() {
        // "http\u017F://" -- the \u017F (long s) looks like 's', so this spells
        // "https://" to the eye. The ASCII guard rejects it.
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("http\u017F://example.test"),
        )
    }

    @Test
    fun `a long-s look-alike with no host is refused`() {
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("http\u017F://"),
        )
    }

    @Test
    fun `a real https address is accepted whatever the case of its scheme`() {
        assertNull(ServerEndpoint.addressProblem("https://example.test"))
        assertNull(ServerEndpoint.addressProblem("HTTPS://example.test"))
        assertNull(ServerEndpoint.addressProblem("Https://example.test:8443"))
    }

    @Test
    fun `a plain http address is refused with the https sentence`() {
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("http://example.test"),
        )
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("HTTP://example.test"),
        )
    }

    @Test
    fun `an address with no scheme is refused`() {
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("example.test"),
        )
    }

    @Test
    fun `an address with a single slash after https is refused`() {
        assertEquals(
            FailureSentences.ADDRESS_NOT_HTTPS,
            ServerEndpoint.addressProblem("https:/example.test"),
        )
    }

    @Test
    fun `a non-ASCII host is outside the scheme guard and is accepted`() {
        assertNull(ServerEndpoint.addressProblem("https://\u00FC.example/"))
    }
}
