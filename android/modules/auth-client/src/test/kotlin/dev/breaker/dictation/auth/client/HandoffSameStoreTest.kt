package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hand-off writes the same store a real account service reads. The service here is built over
 * the very vault the finish saves into, with its own address provider, so these tests show what
 * the person would see after a hand-off: the session, the pair with the address read now, and
 * nothing at another address.
 */
class HandoffSameStoreTest {
    private var addressNow: String = BASE_URL

    private fun serviceOver(rig: HandoffRig, http: FakeAuthHttp = FakeAuthHttp()): ServerAuthService =
        ServerAuthService(
            baseUrl = { addressNow },
            http = http,
            keyDerivation = FakeKeyDerivation(),
            vault = rig.sessions,
        )

    private fun handedOff(address: String = BASE_URL): HandoffRig {
        val rig = HandoffRig()
        rig.base = address
        rig.answerExchange()
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
        return rig
    }

    @Test
    fun `the current session after a hand-off is the session of the answer`() {
        val rig = handedOff()
        assertEquals(goodSession(), serviceOver(rig).currentSession())
    }

    @Test
    fun `the addressed session after a hand-off holds the address read now and the session`() {
        val rig = handedOff()
        val pair = serviceOver(rig).currentAddressedSession()
        assertNotNull(pair)
        assertEquals(BASE_URL, pair!!.base)
        assertEquals(goodSession(), pair.session)
    }

    @Test
    fun `the pair carries the address read now and not the stored text`() {
        val rig = handedOff()
        addressNow = "HTTPS://Box.Example/"
        val pair = serviceOver(rig).currentAddressedSession()
        assertNotNull(pair)
        assertEquals("HTTPS://Box.Example/", pair!!.base)
        assertEquals(goodSession(), pair.session)
    }

    @Test
    fun `with the address switched to another server both calls give null`() {
        val rig = handedOff()
        val service = serviceOver(rig)
        assertNotNull(service.currentSession())
        addressNow = "https://other.example"
        assertNull(service.currentSession())
        assertNull(service.currentAddressedSession())
        addressNow = BASE_URL
        assertNotNull(service.currentAddressedSession())
    }

    @Test
    fun `a hand-off at one address is invisible at another`() {
        val rig = handedOff("https://a.example")
        addressNow = "https://b.example"
        val service = serviceOver(rig)
        assertNull(service.currentSession())
        assertNull(service.currentAddressedSession())
        addressNow = "https://a.example"
        assertEquals(goodSession(), service.currentSession())
        assertEquals("https://a.example", service.currentAddressedSession()!!.base)
    }

    @Test
    fun `reading the session after a hand-off sends no request and writes nothing`() {
        val rig = handedOff()
        val http = FakeAuthHttp()
        val callsBefore = rig.sessions.calls.filter { it == "save" || it == "clear" }
        serviceOver(rig, http).currentAddressedSession()
        assertEquals(0, http.requests.size)
        assertEquals(callsBefore, rig.sessions.calls.filter { it == "save" || it == "clear" })
    }

    @Test
    fun `signing out through the service revokes the hand-off session and clears the store`() {
        val rig = handedOff()
        val http = FakeAuthHttp()
        http.answer(200)
        serviceOver(rig, http).logout()
        val request = http.requests.single()
        assertEquals(LOGOUT_URL, request.url)
        assertEquals(listOf("Authorization" to "Bearer " + GOOD_SESSION_TEXT), request.headers)
        assertNull(rig.sessions.stored)
    }

    @Test
    fun `signing out at another address clears the store and sends nothing`() {
        val rig = handedOff()
        addressNow = "https://other.example"
        val http = FakeAuthHttp()
        serviceOver(rig, http).logout()
        assertEquals(0, http.requests.size)
        assertNull(rig.sessions.stored)
    }
}
