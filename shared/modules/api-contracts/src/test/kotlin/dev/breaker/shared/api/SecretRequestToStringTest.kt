package dev.breaker.shared.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRequestToStringTest {

    // ---------- HandoffExchangeRequest ----------
    @Test
    fun `HandoffExchangeRequest toString redacts code and secret`() {
        val codeValue = "c".repeat(43)
        val pairValue = "s".repeat(43)
        val req = HandoffExchangeRequest(code = codeValue, secret = pairValue, deviceName = null)
        val s = req.toString()
        assertFalse("code must not print: $s", s.contains(codeValue))
        assertFalse("secret must not print: $s", s.contains(pairValue))
        assertTrue("class name present: $s", s.contains("HandoffExchangeRequest"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("deviceName=null printed: $s", s.contains("deviceName=null"))

        val req2 = HandoffExchangeRequest(code = codeValue, secret = pairValue, deviceName = "phone-1")
        val s2 = req2.toString()
        assertTrue("deviceName printed: $s2", s2.contains("deviceName=phone-1"))
    }

    // ---------- HandoffStartRequest ----------
    @Test
    fun `HandoffStartRequest toString prints challenge and hides sealed package`() {
        val handshakeValue = "h".repeat(64)
        val sealValue = "s".repeat(64)
        val req = HandoffStartRequest(challenge = handshakeValue, sealedPackage = sealValue)
        val s = req.toString()
        assertFalse("sealedPackage must not print: $s", s.contains(sealValue))
        assertTrue("class name present: $s", s.contains("HandoffStartRequest"))
        assertTrue("challenge printed: $s", s.contains("challenge=" + handshakeValue))
        assertTrue("sealedPackage length text present: $s",
            s.contains("sealedPackage=" + sealValue.length + " chars"))
    }

    // ---------- ResetAccountResponse ----------
    @Test
    fun `ResetAccountResponse toString redacts reset code`() {
        val resetValue = "X".repeat(40)
        val resp = ResetAccountResponse(resetCode = resetValue)
        val s = resp.toString()
        assertFalse("resetCode must not print: $s", s.contains(resetValue))
        assertTrue("class name present: $s", s.contains("ResetAccountResponse"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
    }
}
