package dev.breaker.server.whisper.worker

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The forwarder is where the setting acts: the multipart part is written only when the flag is
// on, and the stored result carries a words member only when the reply carried at least one word.
internal class WordTimestampsMultipartTest {

    private lateinit var server: HttpServer
    private var responseBody: String = """{"text":"hello"}"""
    private var receivedBody: ByteArray = ByteArray(0)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange ->
            receivedBody = exchange.requestBody.readBytes()
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun forward(wordTimestamps: Boolean): ForwardOutcome {
        val forwarder = HttpTranscriptionForwarder(
            DownstreamConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                wordTimestamps = wordTimestamps,
                requestTimeout = Duration.ofSeconds(5),
            )
        )
        return runBlocking { withTimeout(30_000L) { forwarder.forward(ForwardRequest(1L, byteArrayOf(1, 2, 3), 1)) } }
    }

    private fun successBody(wordTimestamps: Boolean): String {
        val outcome = forward(wordTimestamps)
        assertTrue("whisper-server: expected Success but was $outcome", outcome is ForwardOutcome.Success)
        return (outcome as ForwardOutcome.Success).result
    }

    @Test
    fun `the word flag adds the timestamp granularities part with the value word`() {
        successBody(wordTimestamps = true)
        val bodyText = String(receivedBody, Charsets.UTF_8)
        assertTrue(
            "whisper-server: the part must be named with the bracket suffix",
            bodyText.contains("Content-Disposition: form-data; name=\"timestamp_granularities[]\"\r\n\r\nword\r\n"),
        )
        assertTrue(
            "whisper-server: the response format part must still be present",
            bodyText.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"),
        )
    }

    @Test
    fun `the part is absent when the flag is off`() {
        successBody(wordTimestamps = false)
        val bodyText = String(receivedBody, Charsets.UTF_8)
        assertFalse(
            "whisper-server: the timestamp granularities part must not be written when the flag is off",
            bodyText.contains("timestamp_granularities"),
        )
    }

    @Test
    fun `the words member follows segments in the stored json`() {
        responseBody =
            """{"text":"hello world","segments":[{"start":0.0,"end":1.0,"text":"hello world"}],""" +
                """"words":[{"word":"hello","start":0.0,"end":0.5},{"word":"world","start":0.5,"end":1.0}]}"""
        val result = successBody(wordTimestamps = true)
        assertEquals(
            "whisper-server: the words member must follow segments and precede language",
            "{\"text\":\"hello world\",\"segments\":[{\"start\":0.0,\"end\":1.0,\"text\":\"hello world\"}]," +
                "\"words\":[{\"word\":\"hello\",\"start\":0.0,\"end\":0.5}," +
                "{\"word\":\"world\",\"start\":0.5,\"end\":1.0}],\"language\":\"\"}",
            result,
        )
    }

    @Test
    fun `the words member is absent from the result when the reply carried none`() {
        responseBody = """{"text":"hello","words":[]}"""
        val result = successBody(wordTimestamps = true)
        assertEquals(
            "whisper-server: the result must be the exact json without a words member",
            "{\"text\":\"hello\",\"segments\":[],\"language\":\"\"}",
            result,
        )
        assertFalse("whisper-server: the result must not mention words", result.contains("\"words\""))
    }

    @Test
    fun `a word with a quote and a backslash is escaped in the stored json`() {
        responseBody =
            """{"text":"hello","words":[{"word":"say \"hi\" \\ now","start":0.0,"end":1.0}]}"""
        val result = successBody(wordTimestamps = true)
        assertEquals(
            "whisper-server: the stored word must carry the escaped quote and backslash",
            "{\"text\":\"hello\",\"segments\":[],\"words\":[{\"word\":\"say \\\"hi\\\" \\\\ now\",\"start\":0.0,\"end\":1.0}],\"language\":\"\"}",
            result,
        )
    }
}
