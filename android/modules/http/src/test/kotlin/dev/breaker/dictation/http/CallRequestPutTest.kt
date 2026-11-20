package dev.breaker.dictation.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRequestPutTest {

    private val url = "https://example.test/put-path"
    private val headerValue = "put-header-value"
    private val bodyText = "put-body-text"
    private val limits = limitsOf(65_536L)

    @Test
    fun `a PUT with a body is accepted at construction`() {
        val body = bodyText.toByteArray()
        assertTrue("a PUT with a body was refused", argumentRefusalOf {
            CallRequest(CallMethod.PUT, url, emptyList(), body, limits)
        } == null)
    }

    @Test
    fun `a PUT without a body is accepted`() {
        assertTrue("a PUT without a body was refused", argumentRefusalOf {
            CallRequest(CallMethod.PUT, url, emptyList(), null, limits)
        } == null)
    }

    @Test
    fun `a DELETE with a body is refused at construction`() {
        val body = bodyText.toByteArray()
        val refusal = argumentRefusalOf {
            CallRequest(CallMethod.DELETE, url, emptyList(), body, limits)
        }
        assertTrue("a DELETE with a body was accepted", refusal != null)
    }

    @Test
    fun `a GET with a body is still refused at construction`() {
        val body = bodyText.toByteArray()
        val refusal = argumentRefusalOf {
            CallRequest(CallMethod.GET, url, emptyList(), body, limits)
        }
        assertTrue("a GET with a body was accepted", refusal != null)
    }

    @Test
    fun `a PUT request keeps its values and prints the method only`() {
        val body = bodyText.toByteArray()
        val request = CallRequest(CallMethod.PUT, url, listOf("Accept" to headerValue), body, limits)
        assertEquals(CallMethod.PUT, request.method)
        assertEquals(url, request.url)
        assertEquals(listOf("Accept" to headerValue), request.headers)
        assertTrue("the body is the one given", request.body === body)
        assertTrue("the limits are the ones given", request.limits === limits)
        assertEquals("CallRequest(method=PUT)", request.toString())
        assertTrue("the request string holds no url", !request.toString().contains("put-path"))
        assertTrue("the request string holds no header value", !request.toString().contains(headerValue))
    }
}
