package dev.breaker.dictation.stt.ondevice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The real HTTP opener against a plain server on the loopback address.
 *
 * The fetcher tests use a scripted opener, so they cannot show what the real one
 * puts on the wire or how it reads an answer. Each test here starts ONE server
 * coroutine on Dispatchers.IO inside runBlocking, bound to an ephemeral port, and
 * the opener talks to it over plain http (the https rule belongs to the fetcher).
 *
 * A test passes on the server coroutine's own completion or on the exception the
 * client got; no test waits for a clock, except the read timeout test (the last
 * one), where the timer is the behaviour under test. NET_MILLIS and
 * CLIENT_NET_MILLIS are safety nets only: long, and they expire only when the code
 * under test is broken, so a blocked read ends and the test fails by name.
 */

/** Longest the server waits for a client before it gives up and reports it. */
private const val NET_MILLIS = 10_000

/** Connect and read timeout handed to the opener when the test is not about timeouts; above NET_MILLIS so the two never tie. */
private const val CLIENT_NET_MILLIS = 20_000

private const val CRLF = "\r\n"
private const val AGENT = "loopback-test-agent"

private val BINARY_HEADERS: Map<String, String> = mapOf(
    "Accept" to "application/octet-stream",
    "Accept-Encoding" to "identity",
    "User-Agent" to AGENT,
)

private typealias Responder = (index: Int, socket: Socket, base: String) -> Unit

/**
 * A server on 127.0.0.1 that answers each connection through [respond], one at a
 * time, until [close] is called, and records the raw head of each request.
 * [heads] and [current] have one writer (the server coroutine) and are read after
 * it completed, so volatile is enough.
 */
private class LoopbackServer(private val respond: Responder) {

    private val listener = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).also { it.soTimeout = NET_MILLIS }

    @Volatile private var current: Socket? = null
    @Volatile private var closing = false
    @Volatile private var heads: List<String> = emptyList()

    val base: String get() = "http://127.0.0.1:${listener.localPort}"
    val requestHeads: List<String> get() = heads

    /** Runs on the server coroutine. Null when [close] ended it, else a sentence about what went wrong. */
    fun serve(): String? = try {
        acceptLoop()
        null
    } catch (e: SocketTimeoutException) {
        "the server's own safety net of $NET_MILLIS ms expired while it waited for the client"
    } catch (e: IOException) {
        if (closing) null else "the server failed with ${e.javaClass.name}: ${e.message}"
    }

    private fun acceptLoop() {
        while (true) {
            val socket = listener.accept()
            current = socket
            try {
                socket.soTimeout = NET_MILLIS
                val head = readHead(socket.getInputStream())
                heads = heads + head
                respond(heads.size - 1, socket, base)
            } finally {
                socket.close()
            }
        }
    }

    /** Closes the open connection and the listener, which ends [serve] at once. */
    fun close() {
        closing = true
        runCatching { current?.close() }
        runCatching { listener.close() }
    }
}

/** Reads up to and including the blank line that ends a request head. */
private fun readHead(input: InputStream): String {
    val buffer = ByteArrayOutputStream()
    var lastFour = 0
    while (buffer.size() < 16_384) {
        val next = input.read()
        if (next < 0) break
        buffer.write(next)
        lastFour = (lastFour shl 8) or next
        if (lastFour == 0x0D0A0D0A) break
    }
    return String(buffer.toByteArray(), Charsets.ISO_8859_1)
}

/** A status line, the given header lines, and the blank line. The connection is always closed after the answer. */
private fun statusHead(status: String, vararg fields: String): String =
    (listOf(status) + fields.toList() + listOf("Connection: close")).joinToString(CRLF) + CRLF + CRLF

private fun send(socket: Socket, head: String, body: ByteArray = ByteArray(0)) {
    val out = socket.getOutputStream()
    out.write(head.toByteArray(Charsets.ISO_8859_1))
    out.write(body)
    out.flush()
}

private fun openWithRealOpener(url: String, readTimeoutMillis: Int = CLIENT_NET_MILLIS): HttpReply =
    JdkHttpOpener().open(url, BINARY_HEADERS, CLIENT_NET_MILLIS, readTimeoutMillis)

private class Exchange<T>(val result: T, val heads: List<String>, val serverProblem: String?)

/**
 * Starts the server coroutine, runs [client] (a blocking call), closes the server
 * and waits for the coroutine to finish. The server is closed on every path. An
 * exception from [client] becomes a failure that names [claim].
 */
private fun <T> exchange(claim: String, respond: Responder, client: (base: String) -> T): Exchange<T> = runBlocking {
    val server = LoopbackServer(respond)
    try {
        val served = async(Dispatchers.IO) { server.serve() }
        val result = try {
            client(server.base)
        } catch (e: Exception) {
            throw AssertionError("$claim: the opener call threw ${e.javaClass.name}: ${e.message}", e)
        }
        server.close()
        Exchange(result, server.requestHeads, served.await())
    } finally {
        server.close()
    }
}

private class Seen(val base: String, val status: Int, val location: String?)
private class Fetched(val status: Int, val length: Long, val type: String?, val bytes: ByteArray)
private class Partial(val status: Int, val declared: Long, val total: Long, val failure: IOException?)

class JdkHttpOpenerLoopbackTest {

    @Test
    fun `the real opener sends the binary-stream and identity headers and no credential or cookie`() {
        val claim = "request shape"
        val done = exchange(claim, { _, socket, _ ->
            send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: 2"), "ok".toByteArray())
        }) { base ->
            openWithRealOpener("$base/files/model.bin").use { it.status }
        }

        assertNull("$claim: ${done.serverProblem}", done.serverProblem)
        assertEquals("$claim: the server must have seen exactly one request", 1, done.heads.size)
        assertEquals("$claim: the answer status", 200, done.result)

        val lines = done.heads[0].split(CRLF).filter { it.isNotEmpty() }
        assertEquals("$claim: the request line", "GET /files/model.bin HTTP/1.1", lines[0])
        val fields = lines.drop(1).filter { it.contains(':') }
            .groupBy({ it.substringBefore(':').trim().lowercase() }, { it.substringAfter(':').trim() })
        assertEquals("$claim: Accept", listOf("application/octet-stream"), fields["accept"])
        assertEquals("$claim: Accept-Encoding must ask for no compression", listOf("identity"), fields["accept-encoding"])
        assertEquals("$claim: User-Agent is the one that was handed over", listOf(AGENT), fields["user-agent"])
        for (forbidden in listOf("authorization", "proxy-authorization", "cookie")) {
            assertTrue("$claim: the request carried a $forbidden header: ${fields[forbidden]}", forbidden !in fields)
        }
    }

    @Test
    fun `the real opener reports a 302 and its location and does not follow it`() {
        val claim = "redirect surfaced"
        // Every connection after the first gets a 200: a followed redirect would
        // reach it, so the status would be 200 and the server would see two requests.
        val done = exchange(claim, { index, socket, base ->
            if (index == 0) {
                send(socket, statusHead("HTTP/1.1 302 Found", "Location: $base/elsewhere", "Content-Length: 0"))
            } else {
                send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: 8"), "followed".toByteArray())
            }
        }) { base ->
            openWithRealOpener("$base/start").use { Seen(base, it.status, it.location) }
        }

        assertNull("$claim: ${done.serverProblem}", done.serverProblem)
        assertEquals("$claim: the status is the redirect itself", 302, done.result.status)
        assertEquals("$claim: the location is handed back as sent", "${done.result.base}/elsewhere", done.result.location)
        assertEquals("$claim: the server must see one connection; a second one is a followed redirect", 1, done.heads.size)
    }

    @Test
    fun `the real opener returns the declared length and the body bytes`() {
        val claim = "length and body"
        val payload = ByteArray(1000) { (it * 7).toByte() }
        val done = exchange(claim, { _, socket, _ ->
            val head = statusHead("HTTP/1.1 200 OK", "Content-Type: application/octet-stream", "Content-Length: ${payload.size}")
            send(socket, head, payload)
        }) { base ->
            openWithRealOpener("$base/model.bin").use { Fetched(it.status, it.contentLength, it.contentType, it.body.readBytes()) }
        }

        assertNull("$claim: ${done.serverProblem}", done.serverProblem)
        assertEquals("$claim: status", 200, done.result.status)
        assertEquals("$claim: the declared length", payload.size.toLong(), done.result.length)
        assertEquals("$claim: the content type", "application/octet-stream", done.result.type)
        assertArrayEquals("$claim: the body must come back byte for byte", payload, done.result.bytes)
    }

    @Test
    fun `a body closed by the server early surfaces as an IOException or a short count, not a clean full read`() {
        val claim = "early close"
        val declared = 1000
        val sent = 100
        val done = exchange(claim, { _, socket, _ ->
            send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: $declared"), ByteArray(sent) { 1 })
        }) { base ->
            openWithRealOpener("$base/model.bin").use { reply ->
                val buffer = ByteArray(256)
                var total = 0L
                var failure: IOException? = null
                try {
                    while (true) {
                        val count = reply.body.read(buffer)
                        if (count < 0) break
                        total += count
                    }
                } catch (e: IOException) {
                    failure = e
                }
                Partial(reply.status, reply.contentLength, total, failure)
            }
        }

        assertNull("$claim: ${done.serverProblem}", done.serverProblem)
        val got = done.result
        assertEquals("$claim: status", 200, got.status)
        assertEquals("$claim: the length the server announced", declared.toLong(), got.declared)
        // The cut may show as an exception or as a short count; a full read may not.
        assertTrue(
            "$claim: read ${got.total} of ${got.declared} bytes with no exception, which looks like a complete body",
            got.failure != null || got.total < got.declared,
        )
        assertTrue("$claim: read ${got.total} bytes but the server only sent $sent", got.total <= sent)
    }

    @Test
    fun `the real opener's read timeout fires on a silent server`() {
        // The one test that waits on a real timer, and it is argued: the timer IS
        // the behaviour under test (that the read timeout reaches the connection).
        // The wait is bounded by that value, 300 ms. The server stays silent in a
        // blocking read that ends when the client or the test closes the connection.
        // Its safety net matters only if the opener ignored the timeout: then the
        // server ends the connection, the client sees another outcome, and the
        // test fails by name instead of hanging. Nothing else here waits on time.
        val claim = "read timeout"
        var netExpired = false
        val done = exchange<IOException?>(claim, { _, socket, _ ->
            try {
                socket.getInputStream().read()
            } catch (e: SocketTimeoutException) {
                netExpired = true
            } catch (e: IOException) {
                // The client closed the connection, or the test closed it. Both are the normal end.
            }
        }) { base ->
            try {
                openWithRealOpener("$base/silent", readTimeoutMillis = 300).close()
                null
            } catch (e: IOException) {
                e
            }
        }

        val thrown = done.result
        assertTrue(
            "$claim: the opener returned a reply from a server that never answered (server net expired: $netExpired)",
            thrown != null,
        )
        assertTrue(
            "$claim: expected a SocketTimeoutException but got ${thrown?.javaClass?.name} (server net expired: $netExpired)",
            thrown is SocketTimeoutException,
        )
        assertTrue("$claim: the server's safety net ended the wait, not the read timeout", !netExpired)
    }
}
