package dev.breaker.dictation.updater

import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestFetchTest {

    private fun ok(text: String = manifestText()) = FakeTransport { BytesReply(200, bytesOf(text)) }

    private fun fetch(transport: FakeTransport, address: String = "https://host.test"): FetchOutcome =
        ManifestFetch(transport) { address }.fetch()

    private fun assertFailed(reason: String, outcome: FetchOutcome) {
        assertTrue("android_updater: a failed outcome", outcome is FetchOutcome.Failed)
        assertEquals("android_updater: the fixed sentence", reason, (outcome as FetchOutcome.Failed).reason)
    }

    @Test
    fun `a 200 with a manifest gives the manifest`() {
        val outcome = fetch(ok())
        assertTrue("android_updater: manifest outcome", outcome is FetchOutcome.Manifest)
        assertEquals("android_updater: version code", 2L, (outcome as FetchOutcome.Manifest).manifest.versionCode)
    }

    @Test
    fun `the call is a GET of the route on the configured address with no body`() {
        val transport = ok()
        fetch(transport, "http://breaker.example.test:8080")
        assertEquals("android_updater: one request", 1, transport.sent.size)
        val request = transport.sent[0]
        assertEquals("android_updater: GET", CallMethod.GET, request.method)
        assertEquals("android_updater: url", "http://breaker.example.test:8080/v1/updates/latest.json", request.url)
        assertNull("android_updater: no body", request.body)
    }

    @Test
    fun `one trailing slash of the address is dropped and a path in the address is kept`() {
        val transport = ok()
        fetch(transport, "https://host.test/breaker/")
        fetch(transport, "https://host.test")
        assertEquals("android_updater: path kept, slash dropped", "https://host.test/breaker/v1/updates/latest.json", transport.sent[0].url)
        assertEquals("android_updater: bare host", "https://host.test/v1/updates/latest.json", transport.sent[1].url)
    }

    @Test
    fun `the only header is Accept so no token or key goes with the public route`() {
        val transport = ok()
        fetch(transport)
        assertEquals("android_updater: headers", listOf("Accept" to "application/json"), transport.sent[0].headers)
    }

    @Test
    fun `the call names its timeouts and a small reply cap`() {
        val transport = ok()
        fetch(transport)
        val limits = transport.sent[0].limits
        assertEquals("android_updater: connect timeout", 5_000, limits.connectTimeoutMs)
        assertEquals("android_updater: read timeout", 10_000, limits.readTimeoutMs)
        assertEquals("android_updater: reply cap", 262_144L, limits.maxReplyBytes)
    }

    @Test
    fun `a blank address makes no call and fails with the no address sentence`() {
        for (address in listOf("", "   ", "\n")) {
            val transport = ok()
            assertFailed(CheckReasons.NO_ADDRESS, fetch(transport, address))
            assertEquals("android_updater: no call for '$address'", 0, transport.sent.size)
        }
    }

    @Test
    fun `an address provider that throws fails with the no address sentence and makes no call`() {
        val transport = ok()
        val outcome = ManifestFetch(transport) { throw IllegalStateException("LEAK-MARKER") }.fetch()
        assertFailed(CheckReasons.NO_ADDRESS, outcome)
        assertEquals("android_updater: no call", 0, transport.sent.size)
    }

    @Test
    fun `the fixed 404 answer means no release yet`() {
        val outcome = fetch(FakeTransport { BytesReply(404, bytesOf(NOT_FOUND_TEXT)) })
        assertTrue("android_updater: no release", outcome === FetchOutcome.NoRelease)
    }

    @Test
    fun `a 404 that is not the server's own answer is a failure`() {
        for (body in listOf("<html>Not Found</html>", "", "{\"error\":\"other\"}", "{\"message\":\"No release yet\"}")) {
            assertFailed(CheckReasons.SERVER_ERROR, fetch(FakeTransport { BytesReply(404, bytesOf(body)) }))
        }
    }

    @Test
    fun `any other status is a failure even with a valid manifest in the body`() {
        for (status in listOf(204, 301, 302, 307, 400, 401, 403, 500, 503)) {
            assertFailed(CheckReasons.SERVER_ERROR, fetch(FakeTransport { BytesReply(status, bytesOf(manifestText())) }))
        }
    }

    @Test
    fun `a 200 that is not a manifest fails with the bad answer sentence`() {
        for (body in listOf("", "not json", "{}", manifestText(versionCode = "0"), manifestText(notes = "7"))) {
            assertFailed(CheckReasons.BAD_ANSWER, fetch(FakeTransport { BytesReply(200, bytesOf(body)) }))
        }
    }

    @Test
    fun `a connection problem fails with the unreachable sentence`() {
        assertFailed(CheckReasons.UNREACHABLE, fetch(FakeTransport { throw IOException("LEAK-MARKER https://secret.test") }))
    }

    @Test
    fun `a refusal by the http module fails with the not allowed sentence`() {
        for (reason in HttpRefusalReason.values()) {
            assertFailed(CheckReasons.NOT_ALLOWED, fetch(FakeTransport { throw HttpRefusal(reason) }))
        }
    }

    @Test
    fun `any other exception fails with the unfinished sentence and does not escape`() {
        assertFailed(CheckReasons.UNFINISHED, fetch(FakeTransport { throw IllegalStateException("LEAK-MARKER") }))
    }

    @Test
    fun `no failure sentence holds the address, the body or the exception text`() {
        val outcomes = listOf(
            fetch(FakeTransport { throw IOException("LEAK-MARKER") }, "https://secret.test"),
            fetch(FakeTransport { BytesReply(500, bytesOf("LEAK-MARKER")) }, "https://secret.test"),
            fetch(FakeTransport { BytesReply(200, bytesOf("LEAK-MARKER")) }, "https://secret.test"),
            fetch(FakeTransport { BytesReply(404, bytesOf("LEAK-MARKER")) }, "https://secret.test"),
        )
        for (outcome in outcomes) {
            val reason = (outcome as FetchOutcome.Failed).reason
            assertFalse("android_updater: leak in '$reason'", reason.contains("LEAK-MARKER") || reason.contains("secret.test"))
        }
    }
}
