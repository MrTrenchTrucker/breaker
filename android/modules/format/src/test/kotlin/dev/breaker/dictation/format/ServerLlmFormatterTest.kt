package dev.breaker.dictation.format

import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.shared.prompts.PromptParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServerLlmFormatterTest {

    @Test
    fun `url strips one trailing slash`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com/" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        assertEquals("https://example.com/v1/chat/completions", transport.requests.last().url)
    }

    @Test
    fun `url keeps no trailing slash`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        assertEquals("https://example.com/v1/chat/completions", transport.requests.last().url)
    }

    @Test
    fun `header order with key`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        assertEquals(listOf("Content-Type" to "application/json", "Accept" to "application/json", "Authorization" to "Bearer secret"), transport.requests.last().headers)
    }

    @Test
    fun `header order without key`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { null }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        assertEquals(listOf("Content-Type" to "application/json", "Accept" to "application/json"), transport.requests.last().headers)
    }

    @Test
    fun `header order with blank key`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "   " }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        assertEquals(listOf("Content-Type" to "application/json", "Accept" to "application/json"), transport.requests.last().headers)
    }

    @Test
    fun `exact body with special chars`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        val raw = "He said \"hi\" then\nleft caf\u00e9"
        f.format(raw)
        val maxTokens = PromptParameters.maxTokensFor((raw.length + 3) / 4)
        val expected = "{\"model\":\"gpt-4\",\"temperature\":0,\"max_tokens\":$maxTokens,\"stream\":false,\"messages\":[{\"role\":\"system\",\"content\":\"${f.escapeJson(prompt.systemPrompt)}\"},{\"role\":\"user\",\"content\":\"He said \\\"hi\\\" then\\u000aleft caf\u00e9\"}]}"
        val body = String(transport.requests.last().body ?: byteArrayOf())
        assertEquals(expected, body)
    }

    @Test
    fun `limits are the fixed connect read and size caps`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("hi")
        val limits = transport.requests.last().limits
        assertEquals(1500, limits.connectTimeoutMs)
        assertEquals(30000, limits.readTimeoutMs)
        assertEquals(1_048_576L, limits.maxReplyBytes)
    }

    @Test
    fun `golden case returns formatted text and does not call the fallback`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, """{"choices":[{"message":{"content":"I have 3 things I want you to go over:\n\n1. is file a.\n2. is file b.\n3. is file c."}}]}""".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        val raw = "I have 3 things I want you to go over one is file a, two is file b, three is file c"
        val out = f.format(raw)
        assertEquals("I have 3 things I want you to go over:\n\n1. is file a.\n2. is file b.\n3. is file c.", out)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `io exception falls back once`() {
        val transport = FakeTransport()
        transport.throwOnSend = IOException("boom")
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `http refusal falls back once`() {
        val transport = FakeTransport()
        transport.throwOnSend = HttpRefusal(HttpRefusalReason.NOT_HTTPS)
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `illegal argument falls back once`() {
        val transport = FakeTransport()
        transport.throwOnSend = IllegalArgumentException("bad")
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `blank raw text returns as is no call`() {
        val transport = FakeTransport()
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("   ", f.format("   "))
        assertEquals("", f.format(""))
        assertEquals(0, transport.requests.size)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `blank base uses fallback no call`() {
        val transport = FakeTransport()
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(0, transport.requests.size)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `blank model uses fallback no call`() {
        val transport = FakeTransport()
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(0, transport.requests.size)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `max tokens follows the length of a long text`() {
        val transport = FakeTransport()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, CountingFallback(), transport, prompt)
        f.format("a ".repeat(500))
        val body = String(transport.requests.last().body ?: byteArrayOf())
        assertTrue(body.contains("\"max_tokens\":439,"))
    }
}
