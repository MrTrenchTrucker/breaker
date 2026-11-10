package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.sync.PullTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The pull wiring test: PullChainWiring.pullTransport builds PullHttpTransport from the same
 * pieces the sync uploader uses (JdkHttpTransport over settingsStore.load().serverUrl, the pull
 * CallLimits, one addressed session per call). BreakerApp.kt itself never names PullHttpTransport,
 * so the pull-transport gate test stays green.
 */
class PullChainWiringTest {

    companion object {
        private const val TOKEN_A = "token-a"
        private const val TOKEN_B = "token-b"
        private const val BASE = "https://server.test"
        private const val PATH = "/v1/transcriptions"
    }

    private fun session(base: String = BASE, token: String = TOKEN_A): AddressedSession =
        AddressedSession(base, AuthSession("u1", "user", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    /** A fake transport: records every request and answers with a canned reply or a failure. No network. */
    private class FakeTransport : HttpTransport {
        var requests: MutableList<CallRequest> = mutableListOf()
        var reply: BytesReply = BytesReply(200, ByteArray(0))
        var sendThrows: IOException? = null

        override fun send(request: CallRequest): BytesReply {
            requests.add(request)
            val failure = sendThrows
            if (failure != null) throw failure
            return reply
        }

        override fun open(request: CallRequest): dev.breaker.dictation.http.StreamReply =
            throw UnsupportedOperationException("open is not used by the pull wiring")
    }

    private fun settingsStore(serverUrl: String): SettingsStore = object : SettingsStore {
        override fun load() = AppSettings(serverUrl = serverUrl)
        override fun save(settings: AppSettings) {}
    }

    private fun wired(transport: FakeTransport, base: String = BASE,
                      supplier: () -> AddressedSession? = { session(base) }): PullHttpTransport =
        pullTransport(settingsStore(base), supplier, transport) as PullHttpTransport

    private fun answered(result: PullTransportResult): PullTransportResult.Answered {
        assertTrue("android_app: expected Answered, got a different result", result is PullTransportResult.Answered)
        return result as PullTransportResult.Answered
    }

    @Test
    fun `the wiring builds a pull transport over the addressed session`() {
        val transport = FakeTransport()
        val subject = wired(transport, base = "https://custom.test")
        val result = answered(subject.fetchPath(PATH, "since=c1"))
        assertEquals("the server answers pass through", 200, result.status)
        assertEquals("the request url is the addressed base plus the pull path and query",
            "https://custom.test/v1/transcriptions?since=c1", transport.requests.single().url)
    }

    @Test
    fun `the wiring applies the pull limits`() {
        val transport = FakeTransport()
        val subject = wired(transport)
        subject.fetchPath(PATH, "")
        val request = transport.requests.single()
        assertEquals("the pull uses GET with no body", CallMethod.GET, request.method)
        assertEquals("the pull url is the stored server plus the pull path", BASE + PATH, request.url)
        assertEquals("connect timeout matches the uploader's", 10_000, request.limits.connectTimeoutMs)
        assertEquals("read timeout matches the uploader's", 30_000, request.limits.readTimeoutMs)
        assertEquals("reply cap is the pull page cap", 5_242_880L, request.limits.maxReplyBytes)
    }

    @Test
    fun `the bearer header is read once per call through the wiring`() {
        val transport = FakeTransport()
        var token = TOKEN_A
        val subject = wired(transport, supplier = { session(token = token) })
        subject.fetchPath(PATH, "")
        token = TOKEN_B
        subject.fetchPath(PATH, "")
        assertEquals(
            "each call carries its own token",
            listOf(listOf("Authorization" to "Bearer token-a"), listOf("Authorization" to "Bearer token-b")),
            transport.requests.map { it.headers },
        )
    }

    @Test
    fun `no session through the wiring means no request and Unreachable`() {
        val transport = FakeTransport()
        val subject = wired(transport, supplier = { null })
        assertSame("android_app: no session", PullTransportResult.Unreachable, subject.fetchPath(PATH, ""))
        assertTrue("android_app: nothing was sent", transport.requests.isEmpty())
    }
}
