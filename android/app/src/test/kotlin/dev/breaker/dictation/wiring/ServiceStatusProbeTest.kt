package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.shared.api.ServiceStatus
import dev.breaker.shared.api.ServiceStatusReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The real asker over a fake transport: the request it builds and the answer it returns. */
class ServiceStatusProbeTest {

    private val base = "https://server.test"

    private fun sessionOf(base: String = "https://server.test", token: String = "token-a"): AddressedSession =
        AddressedSession(base, AuthSession("u1", "user", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE)))

    private fun probe(
        transport: FakeStatusTransport,
        session: () -> AddressedSession? = { sessionOf() },
    ): ServiceStatusProbe = ServiceStatusProbe(transport, session)

    private fun body(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `the ask is a GET to the status path with the bearer and the three limits`() {
        val transport = FakeStatusTransport()
        probe(transport).ask()
        val call = transport.requests.single()
        assertEquals(CallMethod.GET, call.method)
        assertEquals("https://server.test/v1/status", call.url)
        assertEquals(listOf("Authorization" to "Bearer token-a"), call.headers)
        assertNull(call.body)
        assertEquals(1_500, call.limits.connectTimeoutMs)
        assertEquals(5_000, call.limits.readTimeoutMs)
        assertEquals(4_096L, call.limits.maxReplyBytes)
    }

    @Test
    fun `a 200 with a valid body is answered with the services`() {
        val transport = FakeStatusTransport().apply {
            reply = BytesReply(200, body("""{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false}]}"""))
        }
        val answer = probe(transport).ask()
        assertEquals(
            ServiceStatusAnswer.Answered(
                ServiceStatusReply(listOf(ServiceStatus("sync-api", true), ServiceStatus("whisper-server", false))),
            ),
            answer,
        )
    }

    @Test
    fun `a 200 whose body does not parse is a failure and not a throw`() {
        val transport = FakeStatusTransport().apply { reply = BytesReply(200, body("not a status reply")) }
        assertEquals(ServiceStatusAnswer.Failed, probe(transport).ask())
    }

    @Test
    fun `a status that is not 200 is a failure even with a valid body`() {
        val transport = FakeStatusTransport().apply {
            reply = BytesReply(503, body("""{"services":[{"name":"sync-api","up":true}]}"""))
        }
        assertEquals(ServiceStatusAnswer.Failed, probe(transport).ask())
    }

    @Test
    fun `a connection problem is a failure and not a throw`() {
        val transport = FakeStatusTransport().apply { failure = IOException("unreachable") }
        assertEquals(ServiceStatusAnswer.Failed, probe(transport).ask())
    }

    @Test
    fun `a reply over its cap is a refusal of the module, and a failure`() {
        val transport = FakeStatusTransport().apply {
            failure = HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)
        }
        assertEquals(ServiceStatusAnswer.Failed, probe(transport).ask())
    }

    @Test
    fun `no addressed session asks nothing`() {
        val transport = FakeStatusTransport()
        assertEquals(ServiceStatusAnswer.Failed, probe(transport, session = { null }).ask())
        assertTrue("no call is built without an addressed session", transport.requests.isEmpty())
    }

    @Test
    fun `a blank address asks nothing`() {
        val transport = FakeStatusTransport()
        assertEquals(ServiceStatusAnswer.Failed, probe(transport, session = { sessionOf(base = "   ") }).ask())
        assertTrue("no call is built for a blank address", transport.requests.isEmpty())
    }

    @Test
    fun `a trailing slash on the address is not doubled`() {
        val transport = FakeStatusTransport()
        probe(transport, session = { sessionOf(base = "https://server.test/") }).ask()
        assertEquals("https://server.test/v1/status", transport.requests.single().url)
    }
}
