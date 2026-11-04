package dev.breaker.server.whisper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a forwarding URL that carries a userinfo component (user:pass@) is refused at config
 * time. It can smuggle credentials into the downstream URL and hide a misconfigured proxy, so
 * the scheme + host checks are unchanged but a userinfo is a hard refusal.
 */
internal class WhisperConfigUserinfoTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
        "BREAKER_WHISPER_SYNC_API_URL" to "http://sync-api:8081",
        "BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "svc-key",
    )

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return thrown
        }
        throw AssertionError("whisper-server test: expected ${T::class.java.simpleName} but the call returned")
    }

    @Test
    fun `a forwarder url with userinfo is refused at config time`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "http://user:pass@downstream:9000")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue(
            "whisper-server: the refusal must name the variable, was: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"),
        )
        assertTrue(
            "whisper-server: the refusal must name userinfo, was: ${error.message}",
            error.message!!.contains("userinfo"),
        )
    }

    @Test
    fun `a forwarder url with only a user (no password) is refused too`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "http://user@downstream:9000")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue(
            "whisper-server: a bare user is still userinfo and must be refused, was: ${error.message}",
            error.message!!.contains("userinfo"),
        )
    }

    @Test
    fun `a forwarder url without userinfo is accepted`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "http://downstream:9000")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("whisper-server: a clean url is accepted", "http://downstream:9000", config.forwarderBaseUrl)
    }

    @Test
    fun `the userinfo refusal is scoped to the forwarder url, not the sync-api url`() {
        // The userinfo check covers the forwarder base url only. The sync-api url has no userinfo check,
        // so a userinfo there is still accepted: this documents the boundary so the forwarder
        // check is not accidentally widened to the sync-api url (which would break existing config).
        val env = baseEnv + ("BREAKER_WHISPER_SYNC_API_URL" to "http://user:pass@sync-api:8081")
        val config = WhisperConfig.fromEnv(env)
        assertEquals(
            "whisper-server: the sync-api url userinfo is not refused (the check is forwarder-scoped)",
            "http://user:pass@sync-api:8081",
            config.syncApiBaseUrl,
        )
    }
}
