package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private fun connectionOk(): FakeHttpConnection = FakeHttpConnection(200, 0L, CountingBody(ByteArray(0)))

private fun requestTo(url: String, headers: List<Pair<String, String>>): CallRequest =
    CallRequest(CallMethod.GET, url, headers, null, limitsOf(1_000L))

/** Which handler takes the failure of [call]: the refusal handler is written first, the connection handler second. */
private fun routed(call: () -> Unit): String =
    try {
        call()
        "no failure"
    } catch (refusal: HttpRefusal) {
        "refusal"
    } catch (lost: IOException) {
        "lost connection"
    }

class JdkRulesTest {

    @Test
    fun `an http address is refused NOT_HTTPS before any connection`() {
        val record = SeamRecord()
        val transport = transportOver(connectionOk(), record)
        val sendRefusal = refusedWith { transport.send(requestTo("http://example.test/x", emptyList())) }
        val openRefusal = refusedWith { transport.open(requestTo("http://example.test/x", emptyList())) }
        assertEquals(HttpRefusalReason.NOT_HTTPS, sendRefusal?.reason)
        assertEquals(HttpRefusalReason.NOT_HTTPS, openRefusal?.reason)
        assertEquals("a connection was opened for an http address", 0, record.opened)
    }

    @Test
    fun `the long s sample scheme is refused NOT_HTTPS`() {
        val record = SeamRecord()
        val refusal = refusedWith {
            transportOver(connectionOk(), record).send(requestTo("http\u017F://example.test/x", emptyList()))
        }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertEquals("a connection was opened for the sample", 0, record.opened)
    }

    @Test
    fun `a non ASCII host is refused NOT_HTTPS before any connection`() {
        val record = SeamRecord()
        val refusal = refusedWith {
            transportOver(connectionOk(), record).send(requestTo("https://ex\u00E4mple.test/x", emptyList()))
        }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertEquals("a connection was opened for a non ASCII host", 0, record.opened)
    }

    @Test
    fun `an empty host is refused NOT_HTTPS`() {
        val transport = transportOver(connectionOk(), SeamRecord())
        for (url in listOf("https:///x", "https://:443/x", "https://user@/x")) {
            val refusal = refusedWith { transport.send(requestTo(url, emptyList())) }
            assertEquals("an empty host was accepted: $url", HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        }
    }

    @Test
    fun `a space or control character in the address is refused NOT_HTTPS`() {
        val transport = transportOver(connectionOk(), SeamRecord())
        for (url in listOf("https://example.test/a b", "https://example.test/a\tb", "https://example.test/a\nb", "https://example.test/a\rb")) {
            val refusal = refusedWith { transport.send(requestTo(url, emptyList())) }
            assertEquals("a space or control character was accepted", HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        }
    }

    @Test
    fun `the https scheme is accepted in any ASCII case`() {
        for (url in listOf("HTTPS://example.test/x", "Https://example.test/x")) {
            val reply = transportOver(connectionOk(), SeamRecord()).send(requestTo(url, emptyList()))
            assertEquals(url, 200, reply.status)
        }
    }

    @Test
    fun `an address with user information and a port is refused NOT_HTTPS before any connection`() {
        val record = SeamRecord()
        val refusal = refusedWith {
            transportOver(connectionOk(), record).send(requestTo("https://user@example.test:8443/p?q", emptyList()))
        }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertEquals("a connection was opened for user information", 0, record.opened)
    }

    @Test
    fun `a non ASCII user part is refused NOT_HTTPS before any connection`() {
        val record = SeamRecord()
        val refusal = refusedWith {
            transportOver(connectionOk(), record).send(requestTo("https://ex\u00E4mple@example.test/x", emptyList()))
        }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertEquals("a connection was opened for user information", 0, record.opened)
    }

    @Test
    fun `an at sign only in the path or the query is not the authority and the address is accepted`() {
        val url = "https://example.test/p@x?q=a@b"
        val record = SeamRecord()
        val reply = transportOver(connectionOk(), record).send(requestTo(url, emptyList()))
        assertEquals(200, reply.status)
        assertEquals(listOf(url), record.urls)
    }

    @Test
    fun `a header with an empty name is refused BAD_HEADER before any connection`() {
        val record = SeamRecord()
        val refusal = refusedWith {
            transportOver(connectionOk(), record).send(requestTo("https://example.test/x", listOf("" to "value")))
        }
        assertEquals(HttpRefusalReason.BAD_HEADER, refusal?.reason)
        assertEquals("a connection was opened for a bad header", 0, record.opened)
    }

    @Test
    fun `a header name or value with a CR or LF is refused BAD_HEADER before any connection`() {
        val record = SeamRecord()
        val transport = transportOver(connectionOk(), record)
        val bad = listOf(
            listOf("X-A\r" to "v"),
            listOf("X-A" to "a\nb"),
            listOf("X-A" to "a\rb"),
            listOf("X-A\n" to "v"),
        )
        for (headers in bad) {
            val refusal = refusedWith { transport.send(requestTo("https://example.test/x", headers)) }
            assertEquals("a CR or LF header was accepted", HttpRefusalReason.BAD_HEADER, refusal?.reason)
        }
        assertEquals("a connection was opened for a bad header", 0, record.opened)
    }

    @Test
    fun `a refusal message holds no header or address text`() {
        val transport = transportOver(connectionOk(), SeamRecord())
        val refusedHeader = refusedWith {
            transport.send(requestTo("https://example.test/x", listOf("X-A" to "canary-header-secret\r\n")))
        }
        val refusedAddress = refusedWith {
            transport.send(requestTo("http://canary-address.example.test/x", emptyList()))
        }
        for (refusal in listOf(refusedHeader, refusedAddress)) {
            assertTrue("a refusal was expected", refusal != null)
            assertTrue("a refusal message holds caller text: ${refusal?.message}", !refusal!!.message.orEmpty().contains("canary"))
        }
    }

    @Test
    fun `a platform url the platform cannot parse is refused NOT_HTTPS with no cause`() {
        val refusal = refusedWith { platformConnection("https://host:abc/") }
        assertEquals(HttpRefusalReason.NOT_HTTPS, refusal?.reason)
        assertNull("the refusal carries a cause", refusal?.cause)
    }

    @Test
    fun `a refusal caught first routes to the refusal handler and a connection problem to the second`() {
        val refusedAtAddress = routed {
            transportOver(connectionOk(), SeamRecord()).send(requestTo("http://example.test/x", emptyList()))
        }
        assertEquals("refusal", refusedAtAddress)
        val lost = connectionOk()
        lost.connectFails = true
        val lostConnection = routed {
            transportOver(lost, SeamRecord()).send(requestTo("https://example.test/x", emptyList()))
        }
        assertEquals("lost connection", lostConnection)
    }
}
