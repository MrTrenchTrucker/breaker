package dev.breaker.server.whisper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: the two new required sync-api env vars. They refuse on missing or blank exactly like
 * the existing required vars (BREAKER_WHISPER_DB, BREAKER_WHISPER_FORWARDER_URL).
 */
internal class WhisperConfigSyncApiTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
        "BREAKER_WHISPER_SYNC_API_URL" to "http://sync-api:8081",
        "BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "svc-key-123",
    )

    @Test
    fun `BREAKER_WHISPER_SYNC_API_URL missing refuses with a message naming the var`() {
        val env = baseEnv - "BREAKER_WHISPER_SYNC_API_URL"
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_SYNC_API_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_SYNC_API_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_SYNC_API_URL blank refuses with a message naming the var`() {
        val env = baseEnv + ("BREAKER_WHISPER_SYNC_API_URL" to "   ")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_SYNC_API_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_SYNC_API_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_SYNC_API_URL not a valid URL refuses with a message naming the var`() {
        val env = baseEnv + ("BREAKER_WHISPER_SYNC_API_URL" to "not a url")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_SYNC_API_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_SYNC_API_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_SYNC_API_SERVICE_KEY missing refuses with a message naming the var`() {
        val env = baseEnv - "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_SYNC_API_SERVICE_KEY: ${error.message}", error.message!!.contains("BREAKER_WHISPER_SYNC_API_SERVICE_KEY"))
    }

    @Test
    fun `BREAKER_WHISPER_SYNC_API_SERVICE_KEY blank refuses with a message naming the var`() {
        val env = baseEnv + ("BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "  ")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_SYNC_API_SERVICE_KEY: ${error.message}", error.message!!.contains("BREAKER_WHISPER_SYNC_API_SERVICE_KEY"))
    }

    @Test
    fun `both sync-api vars present parse into the config`() {
        val config = WhisperConfig.fromEnv(baseEnv)
        assertEquals("http://sync-api:8081", config.syncApiBaseUrl)
        assertEquals("svc-key-123", config.syncApiServiceKey)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) {
                return thrown
            }
            throw AssertionError("expected ${T::class.java.name} but got ${thrown::class.java.name}: ${thrown.message}", thrown)
        }
        throw AssertionError("expected ${T::class.java.name} but nothing was thrown")
    }
}
