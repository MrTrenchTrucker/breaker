package dev.breaker.dictation.format

import dev.breaker.dictation.http.BytesReply
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerLlmFormatterReplyTest {

    @Test
    fun `status 500 falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(500, byteArrayOf())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `not json falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "not json".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `truncated json falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `trailing garbage falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]} junk".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `choices empty falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `choices missing falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"message\":{\"content\":\"x\"}}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `message missing falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `content a number falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{\"message\":{\"content\":42}}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `content blank falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `content added word n9 falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{\"message\":{\"content\":\"hello universe\"}}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hello world", f.format("hello world"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `content too long falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, ("{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(200) + "\"}}]}").toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hi", f.format("hi"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `content dropped most words falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, "{\"choices\":[{\"message\":{\"content\":\"the.\"}}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:the quick brown fox", f.format("the quick brown fox"))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `real reply with null logprobs and big numbers returns content`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(200, """{"id":"x","created":3000000000,"choices":[{"index":0,"message":{"role":"assistant","content":"Hello world."},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"total_tokens":9}}""".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("Hello world.", f.format("hello world"))
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `status 500 with a valid reply falls back once`() {
        val transport = FakeTransport()
        transport.reply = BytesReply(500, "{\"choices\":[{\"message\":{\"content\":\"hello world.\"}}]}".toByteArray())
        val fallback = CountingFallback()
        val f = ServerLlmFormatter({ "https://example.com" }, { "secret" }, { "gpt-4" }, fallback, transport, prompt)
        assertEquals("fallback:hello world", f.format("hello world"))
        assertEquals(1, fallback.calls)
    }
}
