package dev.breaker.shared.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialToStringTest {

    private fun validVerifier(): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    private fun validSalt16(): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(16))

    private fun kdfParams() = KdfParams(memoryKib = 65536, iterations = 3, parallelism = 1)

    // ---------- LoginRequest ----------
    @Test
    fun `LoginRequest toString redacts authVerifier`() {
        val sampleValue = validVerifier()
        val req = LoginRequest("alice", sampleValue)
        val s = req.toString()
        assertFalse("authVerifier must not print: $s", s.contains(sampleValue))
        assertTrue("class name present: $s", s.contains("LoginRequest"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("username printed: $s", s.contains("alice"))
    }

    // ---------- RegisterRequest ----------
    @Test
    fun `RegisterRequest toString redacts authVerifier`() {
        val sampleValue = validVerifier()
        val req = RegisterRequest("alice", validSalt16(), kdfParams(), 1, sampleValue)
        val s = req.toString()
        assertFalse("authVerifier must not print: $s", s.contains(sampleValue))
        assertTrue("class name present: $s", s.contains("RegisterRequest"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("username printed: $s", s.contains("alice"))
    }

    // ---------- LoginResponse ----------
    @Test
    fun `LoginResponse toString redacts token and wraps key blobs`() {
        val sampleValue = "sampleValue-token-abc"
        val dekValue = "sampleValue-wrapped-dek-def"
        val boxPrivValue = "sampleValue-wrapped-priv-ghi"
        val prevDekValue = "sampleValue-prev-dek-jkl"
        val resp = LoginResponse(
            token = sampleValue,
            role = "admin",
            wrappedDek = dekValue,
            wrappedBoxPrivkey = boxPrivValue,
            boxPubkey = "pubkey-sample",
            keyVersion = 0,
            prevWrappedDek = prevDekValue,
            resetAt = "2024-01-01",
            rekeyedAt = null,
        )
        val s = resp.toString()
        assertFalse("token must not print: $s", s.contains(sampleValue))
        assertFalse("wrappedDek must not print: $s", s.contains(dekValue))
        assertFalse("wrappedBoxPrivkey must not print: $s", s.contains(boxPrivValue))
        assertFalse("prevWrappedDek must not print: $s", s.contains(prevDekValue))
        assertTrue("class name present: $s", s.contains("LoginResponse"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("wrappedDek length text present: $s",
            s.contains("wrappedDek=" + dekValue.length + " chars"))
        assertTrue("wrappedBoxPrivkey length text present: $s",
            s.contains("wrappedBoxPrivkey=" + boxPrivValue.length + " chars"))
        assertTrue("prevWrappedDek length text present: $s",
            s.contains("prevWrappedDek=" + prevDekValue.length + " chars"))
        assertTrue("boxPubkey printed: $s", s.contains("pubkey-sample"))
    }

    // ---------- HandoffExchangeResponse ----------
    @Test
    fun `HandoffExchangeResponse toString redacts token and seals package`() {
        val handshakeValue = "sampleValue-handshake-token"
        val sealValue = "sampleValue-sealed-package-blob"
        val resp = HandoffExchangeResponse(
            token = handshakeValue,
            role = "admin",
            keyVersion = 1,
            sealedPackage = sealValue,
            username = "alice",
            accountId = 42L,
        )
        val s = resp.toString()
        assertFalse("token must not print: $s", s.contains(handshakeValue))
        assertFalse("sealedPackage must not print: $s", s.contains(sealValue))
        assertTrue("class name present: $s", s.contains("HandoffExchangeResponse"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("sealedPackage length text present: $s",
            s.contains("sealedPackage=" + sealValue.length + " chars"))
    }

    // ---------- AgentToken ----------
    @Test
    fun `AgentToken toString redacts token`() {
        val sampleValue = "sampleValue-agent-token"
        val tok = AgentToken(
            token = sampleValue,
            name = "my-agent",
            ownerUsername = "alice",
            scope = AgentTokenScope.TRANSCRIBE,
            expiresAt = "2024-01-01",
        )
        val s = tok.toString()
        assertFalse("token must not print: $s", s.contains(sampleValue))
        assertTrue("class name present: $s", s.contains("AgentToken"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("name printed: $s", s.contains("my-agent"))
    }

    // ---------- TranscriptionServiceUpdate ----------
    @Test
    fun `TranscriptionServiceUpdate toString redacts apiKey`() {
        val sampleValue = "sampleValue-api-key"
        val upd = TranscriptionServiceUpdate(
            name = "svc",
            baseUrl = "https://example.com",
            enabled = true,
            apiKey = sampleValue,
        )
        val s = upd.toString()
        assertFalse("apiKey must not print: $s", s.contains(sampleValue))
        assertTrue("class name present: $s", s.contains("TranscriptionServiceUpdate"))
        assertTrue("redacted marker present: $s", s.contains("redacted"))
        assertTrue("name printed: $s", s.contains("svc"))
    }
}
