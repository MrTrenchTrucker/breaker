package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.CryptoRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one request the finish sends to the exchange: method, url, headers and body bytes. */
class HandoffRequestTest {
    private fun sentRequest(configure: (HandoffRig) -> Unit = { }): AuthHttpRequest {
        val rig = HandoffRig()
        configure(rig)
        rig.answerExchange()
        rig.receive(makeAttempt())
        return rig.exchange.requests.single()
    }

    @Test
    fun `the exchange is one post to the exchange url`() {
        val request = sentRequest()
        assertEquals("POST", request.method)
        assertEquals(EXCHANGE_URL, request.url)
    }

    @Test
    fun `only the content type header is sent and no authorization`() {
        val request = sentRequest()
        assertEquals(listOf("Content-Type" to "application/json"), request.headers)
        assertNull(headerValue(request, "Authorization"))
    }

    @Test
    fun `the body holds the code and the secret and no device name when none is given`() {
        val request = sentRequest()
        assertEquals(expectedExchangeBody(CODE_TEXT, null), bodyText(request))
    }

    @Test
    fun `the body holds the device name last when one is given`() {
        val request = sentRequest { it.deviceName = "Pixel phone" }
        assertEquals(expectedExchangeBody(CODE_TEXT, "Pixel phone"), bodyText(request))
    }

    @Test
    fun `the body escapes quotes backslashes and control characters`() {
        val rig = HandoffRig()
        rig.deviceName = "n\"a\\m"
        rig.answerExchange()
        rig.receive(makeAttempt(), "a\"b\\c\nd")
        val expected = "{\"code\":\"a\\\"b\\\\c\\nd\",\"secret\":\"" + attemptSecretText() +
            "\",\"device_name\":\"n\\\"a\\\\m\"}"
        assertEquals(expected, bodyText(rig.exchange.requests.single()))
    }

    @Test
    fun `a name of 64 code points is sent whole`() {
        val name = "\uD83D\uDE00".repeat(64)
        val request = sentRequest { it.deviceName = name }
        assertEquals(expectedExchangeBody(CODE_TEXT, name), bodyText(request))
    }

    @Test
    fun `the address is read once and its trailing slash is not doubled`() {
        val rig = HandoffRig()
        rig.base = "https://box.example/"
        rig.answerExchange()
        rig.receive(makeAttempt())
        assertEquals(1, rig.baseReads)
        assertEquals(EXCHANGE_URL, rig.exchange.requests.single().url)
    }

    @Test
    fun `the exchange instance carries the body and the logout instance never does`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.crypto.failure = CryptoRefused()
        rig.logout.answer(200)
        rig.receive(makeAttempt())
        assertEquals(1, rig.exchange.requests.size)
        assertTrue(bodyText(rig.exchange.requests[0]).contains(attemptSecretText()))
        assertEquals(1, rig.logout.requests.size)
        val logoutRequest = rig.logout.requests[0]
        assertNull(logoutRequest.body)
        assertNull(headerValue(logoutRequest, "Content-Type"))
        assertEquals(LOGOUT_URL, logoutRequest.url)
    }
}
