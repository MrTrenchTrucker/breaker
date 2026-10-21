package dev.breaker.dictation.stt.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The JDK transport against a plain server on 127.0.0.1. Each test runs ONE server
 * coroutine on Dispatchers.IO inside runBlocking. It never stalls and never waits
 * on a clock; the safety-net timeouts end only a wait the code must never leave open.
 */

private const val SERVER_NET_MILLIS = 10_000
private const val CLIENT_NET_MILLIS = 20_000

private const val CRLF = "\r\n"
private const val ENDPOINT = "/v1/audio/transcriptions"
private const val BOUNDARY = "0123456789abcdef"
private const val KEY = "loopback-fixture-credential"

private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

/** One request as the server read it: the head up to the blank line, and the body by its Content-Length. */
private class Seen(val head: String, val body: ByteArray)

/** Every connection the server read, and a sentence when the server itself went wrong. */
private class ServerRecord(val seen: List<Seen>, val problem: String?)

private class Run<T>(val value: T?, val failure: Throwable?, val record: ServerRecord)

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

private fun readBody(input: InputStream, head: String): ByteArray {
    val declared = head.split(CRLF).firstOrNull { it.lowercase().startsWith("content-length:") }
    val length = declared?.substringAfter(':')?.trim()?.toInt() ?: 0
    val out = ByteArray(length)
    var got = 0
    while (got < length) {
        val count = input.read(out, got, length - got)
        if (count < 0) throw IOException("the request body ended early")
        got += count
    }
    return out
}

/** Answers one connection at a time, recording each request, until [close]. */
private class LoopbackServer(private val respond: (index: Int, socket: Socket) -> Unit) {

    private val listener = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        .also { it.soTimeout = SERVER_NET_MILLIS }

    @Volatile private var closing = false

    val base: String get() = "http://127.0.0.1:${listener.localPort}"

    /** Runs on the server coroutine. A write that fails after the client has its answer is not a server problem. */
    fun serve(): ServerRecord {
        val seen = ArrayList<Seen>()
        var problem: String? = null
        try {
            while (true) {
                listener.accept().use { connection ->
                    connection.soTimeout = SERVER_NET_MILLIS
                    val input = connection.getInputStream()
                    val head = readHead(input)
                    seen.add(Seen(head, readBody(input, head)))
                    try {
                        respond(seen.size - 1, connection)
                    } catch (e: IOException) {
                        // The client may stop reading early, as it does after a reply over the cap.
                    }
                }
            }
        } catch (e: IOException) {
            if (!closing) problem = "the server failed while it waited for or read a connection: ${e.javaClass.name}"
        }
        return ServerRecord(seen, problem)
    }

    /** Closes the listener; a blocked accept ends at once and reports no problem. */
    fun close() {
        closing = true
        listener.close()
    }
}

/** Starts the server, runs [client] on this thread, closes the server and returns the outcome. */
private fun <T> withServer(respond: (Int, Socket) -> Unit, client: (String) -> T): Run<T> = runBlocking {
    val server = LoopbackServer(respond)
    try {
        val served = async(Dispatchers.IO) { server.serve() }
        var value: T? = null
        var failure: Throwable? = null
        try {
            value = client(server.base)
        } catch (e: Throwable) {
            failure = e
        }
        server.close()
        Run(value, failure, served.await())
    } finally {
        server.close()
    }
}

private fun statusHead(statusLine: String, vararg fields: String): String =
    (listOf(statusLine) + fields.toList() + listOf("Connection: close")).joinToString(CRLF) + CRLF + CRLF

private fun send(socket: Socket, head: String, body: ByteArray = ByteArray(0)) {
    val out = socket.getOutputStream()
    out.write(ascii(head))
    out.write(body)
    out.flush()
}

private fun authHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer $KEY", "Accept" to "application/json")

private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): ServerRequest =
    ServerRequest(method, url, headers, body, CLIENT_NET_MILLIS, CLIENT_NET_MILLIS)

private fun exchange(request: ServerRequest): ServerReply = JdkServerHttp().exchange(request, AbortHandle())

private fun postReply(statusLine: String, body: String): Run<ServerReply> = withServer(
    { _, socket -> send(socket, statusHead(statusLine, "Content-Length: ${body.length}"), ascii(body)) },
) { base ->
    exchange(request("POST", "$base$ENDPOINT", authHeaders(), ascii("x")))
}

private fun assertStatusAndBody(claim: String, statusLine: String, status: Int, body: String) {
    val run = postReply(statusLine, body)
    assertNull("$claim: server side: ${run.record.problem}", run.record.problem)
    val reply = run.value
    assertTrue("$claim: no reply came back: ${run.failure?.javaClass?.name}", reply != null)
    assertEquals("$claim: status", status, reply!!.status)
    assertArrayEquals("$claim: body", ascii(body), reply.body)
}

private fun assertOverCap(statusLine: String) {
    val over = MAX_REPLY_BYTES + 1
    val run = withServer({ _, socket ->
        send(socket, statusHead(statusLine, "Content-Length: $over"), ByteArray(over))
    }) { base -> exchange(request("GET", "$base/big", authHeaders(), null)) }
    assertNull("$statusLine: server side: ${run.record.problem}", run.record.problem)
    assertTrue("$statusLine: a ReplyTooLargeException was expected", run.failure is ReplyTooLargeException)
}

class JdkServerHttpLoopbackTest {
    @Test
    fun `the POST goes out with its request line, its headers and the multipart bytes as sent`() {
        val multipart = MultipartBodies.create(ByteArray(64) { (it * 5).toByte() }, "small", "en", BOUNDARY)
        val headers = authHeaders() + ("Content-Type" to multipart.contentType)
        val run = withServer({ _, socket ->
            send(socket, statusHead("HTTP/1.1 202 Accepted", "Content-Length: 2"), ascii("{}"))
        }) { base ->
            exchange(request("POST", "$base$ENDPOINT", headers, multipart.bytes))
        }
        assertNull("server side: ${run.record.problem}", run.record.problem)
        assertEquals("one connection", 1, run.record.seen.size)
        val seen = run.record.seen[0]
        val lines = seen.head.split(CRLF).filter { it.isNotEmpty() }
        assertEquals("the request line", "POST $ENDPOINT HTTP/1.1", lines[0])
        assertTrue("the Authorization header carries the bearer key", lines.contains("Authorization: Bearer $KEY"))
        assertTrue(
            "the content type names the multipart boundary",
            lines.contains("Content-Type: multipart/form-data; boundary=$BOUNDARY"),
        )
        assertTrue("the content length is the body size", lines.contains("Content-Length: ${multipart.bytes.size}"))
        assertArrayEquals("the body bytes on the wire", multipart.bytes, seen.body)
        val wire = String(seen.body, Charsets.ISO_8859_1)
        assertTrue("the body opens with the boundary line", wire.startsWith("--$BOUNDARY$CRLF"))
        assertTrue("the body closes with the final delimiter and its CRLF", wire.endsWith("--$BOUNDARY--$CRLF"))
        assertTrue(
            "the file part names audio.wav",
            wire.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"$CRLF"),
        )
        assertTrue("the file part is audio/wav", wire.contains("Content-Type: audio/wav$CRLF$CRLF"))
        assertEquals("the answer status", 202, run.value?.status)
    }

    @Test
    fun `a 202 reply comes back with its status and its body`() {
        assertStatusAndBody("202", "HTTP/1.1 202 Accepted", 202, "{\"job_id\":\"j-1\",\"status\":\"queued\"}")
    }

    @Test
    fun `a 200 reply comes back with its status and its body`() {
        assertStatusAndBody("200", "HTTP/1.1 200 OK", 200, "{\"status\":\"done\"}")
    }

    @Test
    fun `a 401 reply comes back as its status only, since the platform drops a fixed-length 401 body`() {
        assertEquals("the status is 401 and no body", 401 to 0, postReply("HTTP/1.1 401 Unauthorized", "the key was refused").value?.let { it.status to it.body.size }) // the platform drops this body
    }

    @Test
    fun `a 404 reply comes back with its status and its error body read`() {
        assertStatusAndBody("404", "HTTP/1.1 404 Not Found", 404, "no such job")
    }

    @Test
    fun `a 503 reply comes back with its status and its error body read`() {
        assertStatusAndBody("503", "HTTP/1.1 503 Service Unavailable", 503, "busy, try again later")
    }

    @Test
    fun `a redirect reply is returned as it came and is not followed`() {
        val run = withServer({ index, socket ->
            if (index == 0) {
                send(socket, statusHead("HTTP/1.1 302 Found", "Location: /elsewhere", "Content-Length: 0"))
            } else {
                send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: 2"), ascii("ok"))
            }
        }) { base ->
            exchange(request("GET", "$base/start", authHeaders(), null))
        }
        assertNull("server side: ${run.record.problem}", run.record.problem)
        assertEquals("the status is the redirect itself", 302, run.value?.status)
        assertEquals("one connection; a second one would be a followed redirect", 1, run.record.seen.size)
    }

    @Test
    fun `a closed port gives a ConnectFailedException and no header value in its messages`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val failure = runCatching {
            exchange(request("POST", "http://127.0.0.1:$port$ENDPOINT", authHeaders(), ascii("x")))
        }.exceptionOrNull()
        assertTrue("a ConnectFailedException was expected", failure is ConnectFailedException)
        assertTrue("the exception message carries no header value", failure?.message?.contains(KEY) != true)
        assertTrue("the cause message carries no header value", failure?.cause?.message?.contains(KEY) != true)
    }

    @Test
    fun `a server that closes in the middle of the body gives an IOException at once`() {
        val run = withServer({ _, socket ->
            send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: 1000"), ByteArray(100) { 7 })
        }) { base ->
            exchange(request("GET", "$base/model", authHeaders(), null))
        }
        assertNull("server side: ${run.record.problem}", run.record.problem)
        val failure = run.failure
        assertTrue("an IOException was expected", failure is IOException)
        assertTrue("a short body is not a connect failure", failure !is ConnectFailedException)
        assertTrue("a short body is not an oversize reply", failure !is ReplyTooLargeException)
    }

    @Test
    fun `a 200 reply over the cap gives a ReplyTooLargeException`() {
        assertOverCap("HTTP/1.1 200 OK")
    }

    @Test
    fun `an error reply over the cap gives a ReplyTooLargeException too`() {
        assertOverCap("HTTP/1.1 503 Service Unavailable")
    }

    @Test
    fun `an abort before the exchange gives an IOException and no reply`() {
        val run = withServer({ _, socket ->
            send(socket, statusHead("HTTP/1.1 200 OK", "Content-Length: 0"))
        }) { base ->
            val handle = AbortHandle()
            handle.abort()
            JdkServerHttp().exchange(request("GET", "$base/start", authHeaders(), null), handle)
        }
        assertTrue("an IOException was expected", run.failure is IOException)
        assertNull("an aborted exchange returns no reply", run.value)
    }

    @Test
    fun `a request prints its method and body size and never its headers, url or key`() {
        val text = request("POST", "http://127.0.0.1:1$ENDPOINT", authHeaders(), ascii("abcd")).toString()
        assertTrue("the method is printed", text.contains("POST"))
        assertTrue("the body size is printed", text.contains("4"))
        assertTrue("no header name is printed", !text.contains("Authorization") && !text.contains("Accept"))
        assertTrue("no key is printed", !text.contains(KEY))
        assertTrue("no url is printed", !text.contains(ENDPOINT) && !text.contains("127.0.0.1"))
    }
}
