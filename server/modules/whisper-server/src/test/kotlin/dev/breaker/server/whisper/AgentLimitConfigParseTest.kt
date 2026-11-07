package dev.breaker.server.whisper

import dev.breaker.server.whisper.config.WhisperConfig
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AgentLimitConfigParseTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
        "BREAKER_WHISPER_SYNC_API_URL" to "http://sync-api:8081",
        "BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "svc-key",
    )

    private fun expectFailure(block: () -> Unit): IllegalStateException {
        return try {
            block()
            throw AssertionError("expected IllegalStateException but nothing was thrown")
        } catch (thrown: Throwable) {
            if (thrown is IllegalStateException) thrown
            else throw AssertionError(
                "expected IllegalStateException but got ${thrown::class.java.name}",
                thrown,
            )
        }
    }

    @Test
    fun `rate limit absent means the limit is off`() {
        val config = WhisperConfig.fromEnv(baseEnv)
        assertEquals("no rate limit when the variable is absent", null, config.agentRateLimitPerMin)
    }

    @Test
    fun `rate limit one parses`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "1")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("cap 1 parses", 1, config.agentRateLimitPerMin)
    }

    @Test
    fun `rate limit ten thousand parses at the upper bound`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "10000")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("cap 10000 parses", 10000, config.agentRateLimitPerMin)
    }

    @Test
    fun `rate limit blank refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "   ")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit zero refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "0")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit negative refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "-5")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit not a number refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "abc")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"),
        )
    }

    @Test
    fun `rate limit above ten thousand refuses naming the variable`() {
        val env = baseEnv + ("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN" to "10001")
        val error = expectFailure { WhisperConfig.fromEnv(env) }
        assertTrue(
            "message names BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN: ${error.message}",
            error.message!!.contains("BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN"),
        )
    }
}
