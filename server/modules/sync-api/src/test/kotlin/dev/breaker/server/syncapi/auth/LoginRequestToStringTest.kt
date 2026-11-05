package dev.breaker.server.syncapi.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LoginRequestToStringTest {

    private val sampleValue = "sampleValue-verifier-abc"

    @Test
    fun toStringHidesVerifier() {
        val req = LoginRequest("alice", sampleValue)
        assertFalse("toString must not reveal the verifier", req.toString().contains(sampleValue))
    }

    @Test
    fun toStringNamesClassAndRedacts() {
        val req = LoginRequest("alice", sampleValue)
        val text = req.toString()
        assertTrue("toString must name the class", text.contains("LoginRequest"))
        assertTrue("toString must redact the verifier", text.contains("redacted"))
    }

    @Test
    fun toStringShowsUsername() {
        val req = LoginRequest("alice", sampleValue)
        assertTrue("toString must show the username", req.toString().contains("alice"))
    }

    @Test
    fun equalRequestsHaveEqualHashCode() {
        val a = LoginRequest("alice", sampleValue)
        val b = LoginRequest("alice", sampleValue)
        assertTrue("equal requests must be equal", a == b)
        assertTrue("equal requests must have equal hashCode", a.hashCode() == b.hashCode())
    }
}
