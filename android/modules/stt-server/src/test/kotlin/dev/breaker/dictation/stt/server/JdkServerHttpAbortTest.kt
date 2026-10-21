package dev.breaker.dictation.stt.server

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The abort registration of the JDK transport, driven through a fake connection
 * that never touches the network. Blocking is a signal: the fake completes a
 * deferred when disconnect is called, and the reply waits on that deferred. The
 * timeouts below are safety nets only; they end a wait that a broken abort
 * registration would otherwise leave open, and they never decide a passing test.
 */

private const val SAFETY_NET_MILLIS = 10_000L
private const val TARGET_URL = "http://127.0.0.1:1/v1/jobs/x"

/**
 * A connection that never touches the network. [entered] completes when the call
 * reaches the reply. With [blockUntilDisconnect] that call then waits until
 * [disconnect] releases it. [disconnects] counts every disconnect, and
 * [disconnectsAtConnect] is the count taken when connect ran. [announcedLength] is
 * the Content-Length the fake announces; -1 means none.
 */
private class JdkServerHttpAbortTestConnection(
    private val replyStatus: Int = 200,
    private val replyBody: ByteArray = ByteArray(0),
    private val connectFailure: Throwable? = null,
    private val blockUntilDisconnect: Boolean = false,
    private val announcedLength: Long = -1L,
) : HttpURLConnection(URL(TARGET_URL)) {

    val entered = CompletableDeferred<Unit>()
    val disconnects = AtomicInteger(0)
    val disconnectsAtConnect = AtomicInteger(-1)
    private val released = CompletableDeferred<Unit>()

    override fun connect() {
        disconnectsAtConnect.set(disconnects.get())
        connectFailure?.let { throw it }
    }

    override fun disconnect() {
        disconnects.incrementAndGet()
        released.complete(Unit)
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int {
        entered.complete(Unit)
        if (blockUntilDisconnect) {
            runBlocking { withTimeout(SAFETY_NET_MILLIS) { released.await() } }
        }
        return replyStatus
    }

    override fun getContentLengthLong(): Long = announcedLength

    override fun getInputStream(): InputStream = ByteArrayInputStream(replyBody)
}

class JdkServerHttpAbortTest {

    private fun request(): ServerRequest = ServerRequest("GET", TARGET_URL, emptyMap(), null, 1_500, 1_500)

    @Test
    fun `an abort that lands while the exchange is blocked ends the exchange with an IOException`() = runBlocking {
        val connection = JdkServerHttpAbortTestConnection(blockUntilDisconnect = true)
        val abort = AbortHandle()
        val exchange = async(Dispatchers.IO) {
            runCatching { JdkServerHttp { connection }.exchange(request(), abort) }.exceptionOrNull()
        }
        withTimeout(SAFETY_NET_MILLIS) { connection.entered.await() }
        abort.abort()
        val failure = withTimeout(SAFETY_NET_MILLIS) { exchange.await() }
        assertTrue(
            "the abort must end the exchange with an IOException; got ${failure?.javaClass?.name}",
            failure is IOException,
        )
        assertTrue("the abort must call disconnect on the connection", connection.disconnects.get() >= 1)
    }

    @Test
    fun `an abort made before the exchange starts ends the exchange with an IOException`() {
        val connection = JdkServerHttpAbortTestConnection()
        val abort = AbortHandle()
        abort.abort()
        val failure = runCatching { JdkServerHttp { connection }.exchange(request(), abort) }.exceptionOrNull()
        assertTrue("an IOException was expected", failure is IOException)
        assertTrue(
            "the registration must run disconnect before connect",
            connection.disconnectsAtConnect.get() >= 1,
        )
    }

    @Test
    fun `with no abort the exchange returns the fake reply and disconnects once at the final release`() {
        val connection = JdkServerHttpAbortTestConnection(
            replyStatus = 202,
            replyBody = "fake reply".toByteArray(Charsets.US_ASCII),
        )
        val reply = JdkServerHttp { connection }.exchange(request(), AbortHandle())
        assertEquals("the status is the fake's", 202, reply.status)
        assertArrayEquals("the body is the fake's", "fake reply".toByteArray(Charsets.US_ASCII), reply.body)
        assertEquals("one disconnect, the final release", 1, connection.disconnects.get())
    }

    @Test
    fun `a failure from connect comes out as a ConnectFailedException whose cause is that failure`() {
        val thrown = ConnectException("refused")
        val connection = JdkServerHttpAbortTestConnection(connectFailure = thrown)
        val failure = runCatching { JdkServerHttp { connection }.exchange(request(), AbortHandle()) }.exceptionOrNull()
        assertTrue("a ConnectFailedException was expected", failure is ConnectFailedException)
        assertTrue("the cause is the failure that connect threw", failure?.cause === thrown)
    }

    @Test
    fun `a 401 with an absent error stream and an announced length comes back with an empty body`() {
        val connection = JdkServerHttpAbortTestConnection(replyStatus = 401, announcedLength = 19L)
        val reply = JdkServerHttp { connection }.exchange(request(), AbortHandle())
        assertEquals("the status is the fake's", 401, reply.status)
        assertEquals("no body comes back", 0, reply.body.size)
    }

    @Test
    fun `a 503 with an absent error stream and an announced length comes back with an empty body`() {
        val connection = JdkServerHttpAbortTestConnection(replyStatus = 503, announcedLength = 19L)
        val reply = JdkServerHttp { connection }.exchange(request(), AbortHandle())
        assertEquals("the status is the fake's", 503, reply.status)
        assertEquals("no body comes back", 0, reply.body.size)
    }

    @Test
    fun `a 200 whose stream ends before the announced length still throws an IOException`() {
        val connection = JdkServerHttpAbortTestConnection(
            replyStatus = 200,
            replyBody = "abc".toByteArray(Charsets.US_ASCII),
            announcedLength = 19L,
        )
        val failure = runCatching { JdkServerHttp { connection }.exchange(request(), AbortHandle()) }.exceptionOrNull()
        assertTrue("an IOException must come out for a stream that ends early", failure is IOException)
    }
}
