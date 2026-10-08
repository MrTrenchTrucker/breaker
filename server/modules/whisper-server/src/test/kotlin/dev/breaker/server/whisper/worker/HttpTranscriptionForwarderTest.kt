package dev.breaker.server.whisper.worker

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class HttpTranscriptionForwarderTest {

    private lateinit var server: HttpServer
    private var responseBody: String = ""
    private var responseCode: Int = 200
    private var responseContentType: String = "application/json"
    private var delayMillis: Long = 0
    private var holdResponse: Boolean = false
    private val releaseServer = CountDownLatch(1)
    private val requestArrived = CountDownLatch(1)
    private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var receivedAuth: String? = null
    private var receivedContentType: String? = null
    private var receivedBody: ByteArray = ByteArray(0)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange ->
            requestCount.incrementAndGet()
            requestArrived.countDown()
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
            receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
            receivedBody = exchange.requestBody.readBytes()
            if (delayMillis > 0) { LockSupport.parkNanos(delayMillis * 1_000_000) }
            if (holdResponse) { releaseServer.await() }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", responseContentType)
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() { server.stop(0) }

    private fun forwarder(requestTimeout: Duration = Duration.ofSeconds(5)): HttpTranscriptionForwarder {
        return HttpTranscriptionForwarder(DownstreamConfig(baseUrl = "http://127.0.0.1:${server.address.port}", requestTimeout = requestTimeout))
    }

    private fun forwarder(
        requestTimeout: Duration = Duration.ofSeconds(5),
        apiKey: String? = null,
        model: String = "whisper-1",
    ): HttpTranscriptionForwarder {
        return HttpTranscriptionForwarder(
            DownstreamConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = apiKey,
                model = model,
                requestTimeout = requestTimeout,
            )
        )
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun forward(requestTimeout: Duration = Duration.ofSeconds(5)): ForwardOutcome {
        return runBlocking { withTimeout(30_000L) { forwarder(requestTimeout).forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)) } }
    }

    @Test
    fun `a golden answer with valid json text returns success`() {
        responseBody = """{"text": "hello world"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello world\",\"segments\":[]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a golden answer with text language and segments returns success`() {
        responseBody = """{"text": "hello world", "language": "en", "segments": [{"start": 0.0, "end": 1.0, "text": "hello"}]}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello world\",\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"hello\"}],\"language\":\"en\"}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a plain text answer returns success`() {
        responseBody = "hello world"
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello world\",\"segments\":[]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a verbose json answer with extra fields returns success`() {
        responseBody = """{"task":"transcribe","language":"english","duration":1.5,"text":"hello","segments":[{"id":0,"start":0.0,"end":1.5,"text":"hello"}]}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello\",\"segments\":[{\"start\":0.0,\"end\":1.5,\"text\":\"hello\"}],\"language\":\"english\"}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a language field last returns success`() {
        responseBody = """{"text":"hello","language":"en"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello\",\"segments\":[],\"language\":\"en\"}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a segment text with a closing brace returns success`() {
        responseBody = """{"segments":[{"start":0,"end":1,"text":"ok }"}],"language":"en","text":"ok }"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"ok }\",\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"ok }\"}],\"language\":\"en\"}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a value equal to a key name does not confuse the parser`() {
        responseBody = """{"task":"text","segments":[{"start":0,"end":1,"text":"seg"}],"text":"real"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"real\",\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"seg\"}]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `an escaped quote in text returns success`() {
        responseBody = """{"text":"he said \"hi\"","language":"en"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"he said \\\"hi\\\"\",\"segments\":[],\"language\":\"en\"}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a json string with braces in value returns success`() {
        responseBody = """{"text": "hello {world}"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello {world}\",\"segments\":[]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a text only answer with json content type returns success`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        responseContentType = "application/json"
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello\",\"segments\":[]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a malformed answer returns failure`() {
        responseBody = """{"invalid": "json"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
    }

    @Test
    fun `a field without a colon makes the answer unreadable`() {
        responseBody = """{"text": "hello", "language" "en"}"""
        responseCode = 200
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
    }

    @Test
    fun `a 5xx answer then a success answer returns failure then success`() {
        responseBody = """{"text": "first"}"""
        responseCode = 500
        val first = forward()
        assertTrue("whisper-server: expected Failure but was $first", first is ForwardOutcome.Failure)
        responseBody = """{"text": "second"}"""
        responseCode = 200
        val second = forward()
        assertTrue("whisper-server: expected Success but was $second", second is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"second\",\"segments\":[]}",
            (second as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `four consecutive 5xx answers all return failure`() {
        responseBody = """{"text": "should not appear"}"""
        responseCode = 500
        repeat(4) { i ->
            val outcome = forward()
            assertTrue("whisper-server: attempt ${i + 1} expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        }
    }

    @Test
    fun `a slow server beyond the request timeout returns failure`() {
        responseBody = """{"text": "too late"}"""
        responseCode = 200
        delayMillis = 2_000L
        val outcome = forward(Duration.ofMillis(500))
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        val reason = (outcome as ForwardOutcome.Failure).reason
        assertTrue("whisper-server: expected timeout failure but was $reason", reason.contains("timed out"))
    }

    @Test
    fun `a wrong content type returns failure`() {
        responseBody = """{"text": "hello"}"""
        responseCode = 200
        responseContentType = "text/html"
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        val reason = (outcome as ForwardOutcome.Failure).reason
        assertTrue("whisper-server: expected content type failure but was $reason", reason.contains("unexpected content type"))
    }

    @Test
    fun `a 500 with wrong content type returns HTTP 500 failure`() {
        responseBody = """{"text": "hello"}"""
        responseCode = 500
        responseContentType = "text/html"
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        val reason = (outcome as ForwardOutcome.Failure).reason
        assertTrue("whisper-server: expected HTTP 500 failure but was $reason", reason.contains("HTTP 500"))
    }

    @Test
    fun `a 503 with wrong content type returns HTTP 503`() {
        responseBody = """{"text": "hello"}"""
        responseCode = 503
        responseContentType = "text/html"
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        val reason = (outcome as ForwardOutcome.Failure).reason
        assertTrue("whisper-server: expected HTTP 503 failure but was $reason", reason.contains("HTTP 503"))
    }

    @Test
    fun `a 200 with case insensitive content type returns success`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        responseContentType = "Application/JSON; charset=utf-8"
        val outcome = forward()
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        assertEquals(
            "whisper-server: the result must be the exact expected json",
            "{\"text\":\"hello\",\"segments\":[]}",
            (outcome as ForwardOutcome.Success).result,
        )
    }

    @Test
    fun `a cancelled coroutine rethrows CancellationException`() {
        holdResponse = true
        responseBody = """{"text": "hello"}"""
        responseCode = 200
        val outcomeRef = AtomicReference<ForwardOutcome?>(null)
        val thrownRef = AtomicReference<Throwable?>(null)
        runBlocking {
            val job = launch(Dispatchers.IO) {
                try {
                    outcomeRef.set(forwarder().forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)))
                } catch (t: Throwable) {
                    thrownRef.set(t)
                }
            }
            val arrived = requestArrived.await(5, TimeUnit.SECONDS)
            assertTrue("whisper-server: the request must reach the server", arrived)
            job.cancel()
            releaseServer.countDown()
            job.join()
            assertTrue("whisper-server: no outcome must be returned after cancel", outcomeRef.get() == null)
            assertTrue("whisper-server: the cancellation must be rethrown", thrownRef.get() is CancellationException)
            assertTrue("whisper-server: the job must be cancelled", job.isCancelled)
        }
    }

    @Test
    fun `a wrapped io failure is unreachable`() {
        server.stop(0)
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        val reason = (outcome as ForwardOutcome.Failure).reason
        assertTrue("whisper-server: expected unreachable failure but was $reason", reason.contains("unreachable"))
    }

    @Test
    fun `a verbose json answer with a spaces only text is unreadable`() {
        responseBody = """{"text": "   "}"""
        responseCode = 200
        responseContentType = "application/json"
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        assertEquals(
            "whisper-server: the reason must pin the unreadable response",
            "whisper-server: the transcription service returned an unreadable response",
            (outcome as ForwardOutcome.Failure).reason,
        )
    }

    @Test
    fun `a whitespace only body is an empty response`() {
        responseBody = "   "
        responseCode = 200
        responseContentType = "application/json"
        val outcome = forward()
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        assertEquals(
            "whisper-server: the reason must pin the empty response",
            "whisper-server: the transcription service returned an empty response",
            (outcome as ForwardOutcome.Failure).reason,
        )
    }

    @Test
    fun `empty audio returns failure without calling the server`() {
        val outcome = runBlocking { forwarder().forward(ForwardRequest(1L, byteArrayOf(), 1)) }
        assertTrue("whisper-server: expected Failure but was $outcome", outcome is ForwardOutcome.Failure)
        assertEquals(
            "whisper-server: the reason must pin the empty audio",
            "whisper-server: the audio is empty",
            (outcome as ForwardOutcome.Failure).reason,
        )
        assertEquals("whisper-server: the server must receive no request", 0, requestCount.get())
    }

    @Test
    fun `an api key is sent as a bearer authorization header`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        runBlocking { withTimeout(30_000L) { forwarder(apiKey = "k-123").forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)) } }
        assertEquals(
            "whisper-server: the request must carry exactly the bearer header",
            "Bearer k-123",
            receivedAuth,
        )
    }

    @Test
    fun `a null api key sends no authorization header`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        runBlocking { withTimeout(30_000L) { forwarder(apiKey = null).forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)) } }
        assertEquals("whisper-server: a null key must send no authorization header", null, receivedAuth)
    }

    @Test
    fun `a blank api key sends no authorization header`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        runBlocking { withTimeout(30_000L) { forwarder(apiKey = "   ").forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)) } }
        assertEquals("whisper-server: a blank key must send no authorization header", null, receivedAuth)
    }

    @Test
    fun `the request body carries the file model and response format`() {
        responseBody = """{"text":"hello"}"""
        responseCode = 200
        val audio = byteArrayOf(1, 2, 3, 4, 5)
        runBlocking { withTimeout(30_000L) { forwarder(model = "m-test").forward(ForwardRequest(1L, audio, 1)) } }

        val contentType = receivedContentType
        assertTrue("whisper-server: the request must be multipart", contentType != null && contentType.contains("multipart/form-data"))
        val boundary = contentType!!.substringAfter("boundary=")
        val bodyText = String(receivedBody, Charsets.UTF_8)

        assertTrue(
            "whisper-server: the file part must be named and typed",
            bodyText.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""),
        )
        assertTrue("whisper-server: the file part must be audio/wav", bodyText.contains("Content-Type: audio/wav"))
        assertTrue(
            "whisper-server: the model part must carry the configured model",
            bodyText.contains("name=\"model\"\r\n\r\nm-test\r\n"),
        )
        assertTrue(
            "whisper-server: the response format must be verbose_json",
            bodyText.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"),
        )

        val marker = "Content-Type: audio/wav\r\n\r\n".toByteArray(Charsets.UTF_8)
        val start = indexOfBytes(receivedBody, marker)
        assertTrue("whisper-server: the audio part must be present", start >= 0)
        val payload = receivedBody.copyOfRange(start + marker.size, start + marker.size + audio.size)
        assertTrue("whisper-server: the file part must carry exactly the audio bytes", payload.contentEquals(audio))
        assertTrue("whisper-server: the boundary must close the body", bodyText.contains("--$boundary--"))
    }
}
