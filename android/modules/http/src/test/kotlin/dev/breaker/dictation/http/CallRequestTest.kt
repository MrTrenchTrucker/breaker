package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRequestTest {

    private val limits = CallLimits(connectTimeoutMs = 1_500, readTimeoutMs = 5_000, maxReplyBytes = 65_536L)
    private val url = "https://example.test/canary-path"
    private val headerValue = "canary-header-value"
    private val bodyText = "canary-body-text"

    /** Whether building a request with [method] and [body] is refused at construction. */
    private fun refusedAtConstruction(method: CallMethod, body: ByteArray?): Boolean {
        try {
            CallRequest(method, url, emptyList(), body, limits)
        } catch (expected: IllegalArgumentException) {
            return true
        }
        return false
    }

    @Test
    fun `a GET with a body is refused at construction`() {
        assertTrue("a GET with a body was accepted", refusedAtConstruction(CallMethod.GET, bodyText.toByteArray()))
    }

    @Test
    fun `a GET without a body and a POST with or without a body are accepted`() {
        assertTrue("a GET without a body was refused", !refusedAtConstruction(CallMethod.GET, null))
        assertTrue("a POST without a body was refused", !refusedAtConstruction(CallMethod.POST, null))
        assertTrue("a POST with a body was refused", !refusedAtConstruction(CallMethod.POST, bodyText.toByteArray()))
    }

    @Test
    fun `the request keeps the values it was built with`() {
        val body = bodyText.toByteArray()
        val request = CallRequest(CallMethod.POST, url, listOf("Accept" to headerValue), body, limits)
        assertEquals(CallMethod.POST, request.method)
        assertEquals(url, request.url)
        assertEquals(listOf("Accept" to headerValue), request.headers)
        assertTrue("the body is the one given", request.body === body)
        assertTrue("the limits are the ones given", request.limits === limits)
    }

    @Test
    fun `the request string prints the method only`() {
        val request = CallRequest(CallMethod.POST, url, listOf("Authorization" to headerValue), bodyText.toByteArray(), limits)
        assertEquals("CallRequest(method=POST)", request.toString())
        assertTrue("the request string holds no url", !request.toString().contains("canary-path"))
        assertTrue("the request string holds no header value", !request.toString().contains(headerValue))
    }
}
