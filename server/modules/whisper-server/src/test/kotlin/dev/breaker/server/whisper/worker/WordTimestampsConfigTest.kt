package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.config.WhisperConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The word timestamp setting is read once, in WhisperConfig.fromEnv, and carried from there.
// These tests pin the three rules: absent means false, true and false parse, anything else
// refuses the start and names the variable.
internal class WordTimestampsConfigTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
        "BREAKER_WHISPER_SYNC_API_URL" to "http://sync-api:8081",
        "BREAKER_WHISPER_SYNC_API_SERVICE_KEY" to "svc-key",
    )

    @Test
    fun `BREAKER_WHISPER_WORD_TIMESTAMPS missing defaults to false`() {
        val config = WhisperConfig.fromEnv(baseEnv)
        assertFalse("whisper-server: the word timestamp setting must default to false", config.wordTimestamps)
    }

    @Test
    fun `BREAKER_WHISPER_WORD_TIMESTAMPS true and false parse`() {
        val on = WhisperConfig.fromEnv(baseEnv + ("BREAKER_WHISPER_WORD_TIMESTAMPS" to "true"))
        assertTrue("whisper-server: true must parse as on", on.wordTimestamps)
        val off = WhisperConfig.fromEnv(baseEnv + ("BREAKER_WHISPER_WORD_TIMESTAMPS" to "false"))
        assertFalse("whisper-server: false must parse as off", off.wordTimestamps)
    }

    @Test
    fun `BREAKER_WHISPER_WORD_TIMESTAMPS a value other than true or false refuses naming the variable`() {
        for (bad in listOf("yes", "1", "0", "", "   ", "TRUEISH", "on")) {
            val env = baseEnv + ("BREAKER_WHISPER_WORD_TIMESTAMPS" to bad)
            val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
            assertTrue(
                "whisper-server: message must name BREAKER_WHISPER_WORD_TIMESTAMPS for '$bad': ${error.message}",
                error.message!!.contains("BREAKER_WHISPER_WORD_TIMESTAMPS"),
            )
        }
    }

    @Test
    fun `BREAKER_WHISPER_WORD_TIMESTAMPS is trimmed and case insensitive`() {
        val upper = WhisperConfig.fromEnv(baseEnv + ("BREAKER_WHISPER_WORD_TIMESTAMPS" to " TRUE "))
        assertTrue("whisper-server: a padded upper case true must parse as on", upper.wordTimestamps)
        val mixed = WhisperConfig.fromEnv(baseEnv + ("BREAKER_WHISPER_WORD_TIMESTAMPS" to "False"))
        assertFalse("whisper-server: a mixed case false must parse as off", mixed.wordTimestamps)
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
