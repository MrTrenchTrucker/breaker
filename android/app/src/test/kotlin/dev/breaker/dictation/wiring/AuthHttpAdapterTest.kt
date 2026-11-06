package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AuthHttpRequest
import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.http.BytesReply
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.StreamReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AuthHttpAdapterTest {

    private fun adapterWith(fake: FakeTransport): AuthHttpAdapter =
        AuthHttpAdapter(fake, CallLimits(AUTH_CONNECT_TIMEOUT_MS, AUTH_READ_TIMEOUT_MS, AUTH_MAX_REPLY_BYTES))

    @Test
    fun `POST GET and an unknown method map as specified`() {
        val fake = FakeTransport()
        val adapter = adapterWith(fake)
        adapter.send(AuthHttpRequest("POST", "https://x", emptyList(), null))
        assertEquals(CallMethod.POST, fake.last!!.method)
        adapter.send(AuthHttpRequest("GET", "https://x", emptyList(), null))
        assertEquals(CallMethod.GET, fake.last!!.method)
        try {
            adapter.send(AuthHttpRequest("DELETE", "https://x", emptyList(), null))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("the method is neither GET nor POST", e.message)
        }
    }

    @Test
    fun `url headers and body pass through unchanged`() {
        val fake = FakeTransport()
        val adapter = adapterWith(fake)
        val headers = listOf("Authorization" to "Bearer abc", "x-client" to "1")
        val body = byteArrayOf(1, 2, 3)
        adapter.send(AuthHttpRequest("POST", "https://x/y", headers, body))
        val call = fake.last!!
        assertEquals("https://x/y", call.url)
        assertEquals(headers, call.headers)
        assertSame(body, call.body)
    }

    @Test
    fun `the given limits reach the CallRequest`() {
        val fake = FakeTransport()
        val given = CallLimits(123, 456, 789L)
        val adapter = AuthHttpAdapter(fake, given)
        adapter.send(AuthHttpRequest("GET", "https://x", emptyList(), null))
        assertSame(given, fake.last!!.limits)
    }

    @Test
    fun `non-2xx replies pass through`() {
        val fake = FakeTransport()
        val adapter = adapterWith(fake)
        for ((status, text) in listOf(200 to "ok", 401 to "no", 500 to "err")) {
            val body = text.toByteArray()
            fake.reply = BytesReply(status, body)
            val reply = adapter.send(AuthHttpRequest("GET", "https://x", emptyList(), null))
            assertEquals(status, reply.status)
            assertSame(body, reply.body)
        }
    }

    @Test
    fun `an HttpRefusal and an IOException propagate as the same instance`() {
        val fake = FakeTransport()
        val adapter = adapterWith(fake)

        val refusal = HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)
        fake.throwNext = refusal
        try {
            adapter.send(AuthHttpRequest("GET", "https://x", emptyList(), null))
            fail("expected HttpRefusal")
        } catch (e: HttpRefusal) {
            assertSame(refusal, e)
        }

        val io = IOException("server down")
        fake.throwNext = io
        try {
            adapter.send(AuthHttpRequest("GET", "https://x", emptyList(), null))
            fail("expected IOException")
        } catch (e: IOException) {
            assertSame(io, e)
        }
    }

    @Test
    fun `the adapter keeps only transport and limits and logs nothing`() {
        val fields = AuthHttpAdapter::class.java.declaredFields.map { it.name }.sorted()
        assertEquals(listOf("limits", "transport"), fields)

        val source = AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/AuthHttpAdapter.kt")
        val code = AppSourceFiles.strip(source).code
        assertFalse(code.contains("println"))
        assertFalse(code.contains("print("))
        assertFalse(code.contains("Log."))
        assertFalse(code.contains("toString"))
    }
}

private class FakeTransport : HttpTransport {
    var last: CallRequest? = null
    var reply: BytesReply = BytesReply(200, "ok".toByteArray())
    var throwNext: Throwable? = null

    override fun send(request: CallRequest): BytesReply {
        last = request
        val next = throwNext
        if (next != null) throw next
        return reply
    }

    override fun open(request: CallRequest): StreamReply {
        throw UnsupportedOperationException()
    }
}
