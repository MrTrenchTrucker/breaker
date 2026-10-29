package dev.breaker.dictation.auth.client

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHttpTypesTest {
    private val request = AuthHttpRequest(
        method = "POST",
        url = "https://box.example/v1/auth/login",
        headers = listOf("Authorization" to "Bearer bearer-value-x"),
        body = "verifier-value-x".toByteArray(StandardCharsets.UTF_8),
    )

    @Test
    fun `a request prints only its method when it is converted to text`() {
        assertEquals("AuthHttpRequest(method=POST)", request.toString())
    }

    @Test
    fun `a request text never carries the url, a header value or the body bytes`() {
        val text = request.toString()
        assertTrue("the request text carried the url", !text.contains("box.example"))
        assertTrue("the request text carried a header value", !text.contains("bearer-value-x"))
        assertTrue("the request text carried the body", !text.contains("verifier-value-x"))
    }

    @Test
    fun `a reply prints only its status when it is converted to text`() {
        val reply = AuthHttpReply(401, "reply-body-x".toByteArray(StandardCharsets.UTF_8))
        assertEquals("AuthHttpReply(status=401)", reply.toString())
    }

    @Test
    fun `a reply text never carries its body bytes`() {
        val reply = AuthHttpReply(200, "reply-body-x".toByteArray(StandardCharsets.UTF_8))
        assertTrue("the reply text carried the body", !reply.toString().contains("reply-body-x"))
    }

    @Test
    fun `an http port answers each request with the reply the app builds`() {
        val seen = mutableListOf<AuthHttpRequest>()
        val port = AuthHttp { sent ->
            seen.add(sent)
            AuthHttpReply(204, ByteArray(0))
        }
        val reply = port.send(request)
        assertEquals(204, reply.status)
        assertTrue("the port did not receive the request it was given", seen.size == 1 && seen[0] === request)
    }
}
