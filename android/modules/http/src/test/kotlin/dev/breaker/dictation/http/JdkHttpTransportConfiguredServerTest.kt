package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection

private const val SERVER = "http://server.test"
private const val SERVER_URL = "http://server.test/status"
private const val OTHER_HOST_URL = "http://other.test/status"
private const val OTHER_PORT_URL = "http://server.test:8080/status"
private const val SECURE_URL = "https://other.test/status"

/**
 * A client whose connection seam records every opening in [record] and hands out a fresh connection that
 * answers 200, and whose configured server is whatever [server] returns when it is asked.
 */
private fun configuredTransport(record: SeamRecord, server: () -> String): JdkHttpTransport {
    val seam: (String) -> HttpURLConnection = { url ->
        record.opened += 1
        record.urls.add(url)
        FakeHttpConnection(200, 2L, CountingBody("ok".toByteArray()))
    }
    return JdkHttpTransport(seam, server)
}

private fun getTo(url: String, headers: List<Pair<String, String>>): CallRequest =
    CallRequest(CallMethod.GET, url, headers, null, limitsOf(1_000L))

private fun getTo(url: String): CallRequest = getTo(url, emptyList())

/**
 * The client with a configured server. No test here reads a network: the seam answers from a script, and
 * the two tests that build the client with its real constructors never open a connection, because each
 * sends a request with an empty header name: the address is checked first, so the answer is NOT_HTTPS when
 * the rule did not permit the address and BAD_HEADER when it did, and a refusal comes before any connection.
 */
class JdkHttpTransportConfiguredServerTest {

    @Test
    fun `a send to the configured http address opens it once with that address`() {
        val record = SeamRecord()
        val reply = configuredTransport(record) { SERVER }.send(getTo(SERVER_URL))
        assertEquals(200, reply.status)
        assertEquals("ok", reply.body.decodeToString())
        assertEquals("http: the configured address was not opened exactly once", 1, record.opened)
        assertEquals("http: the configured address was opened with another address", listOf(SERVER_URL), record.urls)
    }

    @Test
    fun `an open to the configured http address opens it once with that address`() {
        val record = SeamRecord()
        val reply = configuredTransport(record) { SERVER }.open(getTo(SERVER_URL))
        assertEquals(200, reply.status)
        reply.close()
        assertEquals("http: the configured address was not opened exactly once", 1, record.opened)
        assertEquals("http: the configured address was opened with another address", listOf(SERVER_URL), record.urls)
    }

    @Test
    fun `a send and an open to another http host are refused NOT_HTTPS with no connection opened`() {
        val record = SeamRecord()
        val transport = configuredTransport(record) { SERVER }
        val sendRefusal = refusedWith { transport.send(getTo(OTHER_HOST_URL)) }
        val openRefusal = refusedWith { transport.open(getTo(OTHER_HOST_URL)) }
        assertEquals(HttpRefusalReason.NOT_HTTPS, sendRefusal?.reason)
        assertEquals(HttpRefusalReason.NOT_HTTPS, openRefusal?.reason)
        assertEquals("http: a connection was opened for another host", 0, record.opened)
    }

    @Test
    fun `a send and an open to the configured host on another port are refused NOT_HTTPS with no connection opened`() {
        val record = SeamRecord()
        val transport = configuredTransport(record) { SERVER }
        val sendRefusal = refusedWith { transport.send(getTo(OTHER_PORT_URL)) }
        val openRefusal = refusedWith { transport.open(getTo(OTHER_PORT_URL)) }
        assertEquals(HttpRefusalReason.NOT_HTTPS, sendRefusal?.reason)
        assertEquals(HttpRefusalReason.NOT_HTTPS, openRefusal?.reason)
        assertEquals("http: a connection was opened for another port", 0, record.opened)
    }

    @Test
    fun `the configured server is read again on every request so a changed value takes effect on the next call`() {
        var current = SERVER
        val record = SeamRecord()
        val transport = configuredTransport(record) { current }
        val first = transport.send(getTo(SERVER_URL))
        assertEquals(200, first.status)
        current = "http://other.test"
        val refusedNow = refusedWith { transport.send(getTo(SERVER_URL)) }
        assertEquals("http: the old configured server was still permitted after it changed", HttpRefusalReason.NOT_HTTPS, refusedNow?.reason)
        val second = transport.send(getTo(OTHER_HOST_URL))
        assertEquals("http: the new configured server was not permitted after it changed", 200, second.status)
        assertEquals(listOf(SERVER_URL, OTHER_HOST_URL), record.urls)
    }

    @Test
    fun `the configured server is read once per request for http and https alike and before the header check`() {
        var reads = 0
        val record = SeamRecord()
        val transport = configuredTransport(record) {
            reads += 1
            SERVER
        }
        assertEquals(0, reads)
        transport.send(getTo(SECURE_URL))
        assertEquals("http: an https send did not read the configured server once", 1, reads)
        transport.open(getTo(SERVER_URL)).close()
        assertEquals("http: an open did not read the configured server once", 2, reads)
        val badHeader = refusedWith { transport.send(getTo(SECURE_URL, listOf("" to "value"))) }
        assertEquals(HttpRefusalReason.BAD_HEADER, badHeader?.reason)
        assertEquals("http: the header check ran before the configured server was read", 3, reads)
        val otherHost = refusedWith { transport.send(getTo(OTHER_HOST_URL)) }
        assertEquals(HttpRefusalReason.NOT_HTTPS, otherHost?.reason)
        assertEquals("http: a refused request did not read the configured server once", 4, reads)
        assertEquals("http: more connections were opened than permitted requests", 2, record.opened)
    }

    @Test
    fun `an https address is called for any host with no configured server and with another one`() {
        val record = SeamRecord()
        val withNone = configuredTransport(record) { "" }.send(getTo(SECURE_URL))
        val withOther = configuredTransport(record) { SERVER }.send(getTo(SECURE_URL))
        assertEquals(200, withNone.status)
        assertEquals(200, withOther.status)
        assertEquals("http: an https address was not opened", 2, record.opened)
    }

    @Test
    fun `a configured https address never permits plain http to the same host`() {
        val record = SeamRecord()
        val transport = configuredTransport(record) { "https://server.test" }
        val refusal = refusedWith { transport.send(getTo(SERVER_URL)) }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertEquals("http: plain http was opened although the configured address is https", 0, record.opened)
        val secure = transport.send(getTo("https://server.test/status"))
        assertEquals(200, secure.status)
    }

    @Test
    fun `an empty or blank configured server permits no plain http address`() {
        val record = SeamRecord()
        for (configured in listOf("", "   ")) {
            val transport = configuredTransport(record) { configured }
            val refusal = refusedWith { transport.send(getTo(SERVER_URL)) }
            assertEquals("http: plain http was permitted with configured text '$configured'", HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        }
        assertEquals("http: a connection was opened with no usable configured server", 0, record.opened)
    }

    @Test
    fun `a permitted plain http address still goes through the header check before any connection`() {
        val record = SeamRecord()
        val transport = configuredTransport(record) { SERVER }
        val refusal = refusedWith { transport.send(getTo(SERVER_URL, listOf("" to "value"))) }
        assertEquals(HttpRefusalReason.BAD_HEADER, refusal?.reason)
        assertEquals("http: a connection was opened for a bad header", 0, record.opened)
    }

    @Test
    fun `the no argument client refuses a plain http address as NOT_HTTPS and never reaches the header check`() {
        // The header name is empty. The address is checked first: a no-argument client has no configured
        // server, so the answer is NOT_HTTPS. A no-argument client that had been given a server would
        // permit the address and answer BAD_HEADER instead. Either way nothing is opened.
        val transport = JdkHttpTransport()
        val sendRefusal = refusedWith { transport.send(getTo(SERVER_URL, listOf("" to "value"))) }
        val openRefusal = refusedWith { transport.open(getTo(SERVER_URL, listOf("" to "value"))) }
        assertEquals("http: the no argument client did not refuse a send as NOT_HTTPS", HttpRefusalReason.NOT_HTTPS, sendRefusal?.reason)
        assertEquals("http: the no argument client did not refuse an open as NOT_HTTPS", HttpRefusalReason.NOT_HTTPS, openRefusal?.reason)
    }

    @Test
    fun `the public constructor hands its configured server to the rule and the permitted address reaches the header check`() {
        // The header name is empty. The address named by the provider is permitted, so the answer is
        // BAD_HEADER; a constructor that dropped the provider would answer NOT_HTTPS. Another host stays
        // NOT_HTTPS. Either way nothing is opened.
        val transport = JdkHttpTransport { SERVER }
        val sendRefusal = refusedWith { transport.send(getTo(SERVER_URL, listOf("" to "value"))) }
        val openRefusal = refusedWith { transport.open(getTo(SERVER_URL, listOf("" to "value"))) }
        assertEquals("http: the public constructor did not pass its server on a send", HttpRefusalReason.BAD_HEADER, sendRefusal?.reason)
        assertEquals("http: the public constructor did not pass its server on an open", HttpRefusalReason.BAD_HEADER, openRefusal?.reason)
        val other = refusedWith { transport.send(getTo(OTHER_HOST_URL, listOf("" to "value"))) }
        assertEquals("http: another host was permitted by the public constructor", HttpRefusalReason.NOT_HTTPS, other?.reason)
    }
}
