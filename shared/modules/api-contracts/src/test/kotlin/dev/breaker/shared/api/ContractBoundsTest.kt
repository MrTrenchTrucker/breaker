package dev.breaker.shared.api

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * the api-contracts data classes carry the server's exact bounds in init blocks, so a
 * client that builds a contract object gets the same refusal the server would. A valid value
 * builds; a value outside the bound throws IllegalArgumentException naming the field.
 */
class ContractBoundsTest {

    private fun iae(block: () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e
        }
        throw AssertionError("api-contracts test: expected IllegalArgumentException but the constructor returned")
    }

    private fun validVerifier(): String = java.util.Base64.getEncoder().encodeToString(ByteArray(32))

    // ---------- LoginRequest ----------

    @Test
    fun `LoginRequest with a valid username and verifier builds`() {
        val req = LoginRequest("alice", validVerifier())
        assertTrue(req.username.isNotEmpty())
    }

    @Test
    fun `LoginRequest with an empty username refuses`() {
        val e = iae { LoginRequest("", validVerifier()) }
        assertTrue("message names the username: ${e.message}", e.message!!.contains("username"))
    }

    @Test
    fun `LoginRequest with a 65 char username refuses`() {
        val e = iae { LoginRequest("a".repeat(65), validVerifier()) }
        assertTrue(e.message!!.contains("username"))
    }

    @Test
    fun `LoginRequest with a username carrying a space refuses`() {
        val e = iae { LoginRequest("a b", validVerifier()) }
        assertTrue(e.message!!.contains("username"))
    }

    @Test
    fun `LoginRequest with a verifier that is not base64 of 32 bytes refuses`() {
        // 16 bytes of base64: the wrong length.
        val e = iae { LoginRequest("alice", java.util.Base64.getEncoder().encodeToString(ByteArray(16))) }
        assertTrue("message names the verifier: ${e.message}", e.message!!.contains("authVerifier"))
    }

    @Test
    fun `LoginRequest with a non-base64 verifier refuses`() {
        val e = iae { LoginRequest("alice", "not-base64!!") }
        assertTrue(e.message!!.contains("authVerifier"))
    }

    // ---------- LoginResponse ----------

    @Test
    fun `LoginResponse with a non-negative keyVersion builds`() {
        val resp = LoginResponse(
            token = "t",
            role = "admin",
            wrappedDek = "w",
            wrappedBoxPrivkey = null,
            boxPubkey = null,
            keyVersion = 0,
            prevWrappedDek = null,
            resetAt = null,
            rekeyedAt = null,
        )
        assertTrue(resp.keyVersion >= 0)
    }

    @Test
    fun `LoginResponse with a negative keyVersion refuses`() {
        val e = iae {
            LoginResponse(
                token = "t",
                role = "admin",
                wrappedDek = "w",
                wrappedBoxPrivkey = null,
                boxPubkey = null,
                keyVersion = -1,
                prevWrappedDek = null,
                resetAt = null,
                rekeyedAt = null,
            )
        }
        assertTrue("message names the keyVersion: ${e.message}", e.message!!.contains("keyVersion"))
    }

    // ---------- HandoffExchangeRequest ----------

    @Test
    fun `HandoffExchangeRequest with 43 char code and secret builds`() {
        val code = "A".repeat(43)
        val secret = "B".repeat(43)
        val req = HandoffExchangeRequest(code = code, secret = secret, deviceName = null)
        assertTrue(req.code.length == 43)
    }

    @Test
    fun `HandoffExchangeRequest with a 42 char code refuses`() {
        val e = iae { HandoffExchangeRequest(code = "A".repeat(42), secret = "B".repeat(43), deviceName = null) }
        assertTrue("message names the code: ${e.message}", e.message!!.contains("code"))
    }

    @Test
    fun `HandoffExchangeRequest with a 42 char secret refuses`() {
        val e = iae { HandoffExchangeRequest(code = "A".repeat(43), secret = "B".repeat(42), deviceName = null) }
        assertTrue("message names the secret: ${e.message}", e.message!!.contains("secret"))
    }

    @Test
    fun `HandoffExchangeRequest with a code carrying a non-alnum char refuses`() {
        // 42 alnum + one space = 43 chars, but the space is not in [A-Za-z0-9_-].
        val e = iae { HandoffExchangeRequest(code = "A".repeat(42) + " ", secret = "B".repeat(43), deviceName = null) }
        assertTrue(e.message!!.contains("code"))
    }

    @Test
    fun `HandoffExchangeRequest with a 65 code point device name refuses`() {
        val e = iae { HandoffExchangeRequest(code = "A".repeat(43), secret = "B".repeat(43), deviceName = "d".repeat(65)) }
        assertTrue("message names the deviceName: ${e.message}", e.message!!.contains("deviceName"))
    }

    @Test
    fun `HandoffExchangeRequest with a 64 code point device name builds`() {
        val req = HandoffExchangeRequest(code = "A".repeat(43), secret = "B".repeat(43), deviceName = "d".repeat(64))
        assertTrue(req.deviceName!!.length == 64)
    }

    // ---------- ResetAccountResponse ----------

    @Test
    fun `ResetAccountResponse with a 64 char code without whitespace builds`() {
        val resp = ResetAccountResponse(resetCode = "C".repeat(64))
        assertTrue(resp.resetCode.length == 64)
    }

    @Test
    fun `ResetAccountResponse with a blank code refuses`() {
        val e = iae { ResetAccountResponse(resetCode = "   ") }
        assertTrue("message names the resetCode: ${e.message}", e.message!!.contains("resetCode"))
    }

    @Test
    fun `ResetAccountResponse with a code carrying an inner space refuses`() {
        // Non-blank overall, but it carries a whitespace char.
        val e = iae { ResetAccountResponse(resetCode = "abc def") }
        assertTrue(e.message!!.contains("resetCode"))
    }

    @Test
    fun `ResetAccountResponse with a 65 char code refuses`() {
        val e = iae { ResetAccountResponse(resetCode = "C".repeat(65)) }
        assertTrue(e.message!!.contains("resetCode"))
    }
}
