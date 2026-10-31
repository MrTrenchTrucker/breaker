package dev.breaker.server.whisper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for WhisperConfig.fromEnv() parsing.
 * Plan: SLICE4_MAIN.md "Config parsing tests" (10 tests).
 */
internal class WhisperConfigTest {

    private val baseEnv = mapOf(
        "BREAKER_WHISPER_PORT" to "8080",
        "BREAKER_WHISPER_DB" to "/tmp/test.db",
        "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
    )

    @Test
    fun `BREAKER_WHISPER_PORT missing refuses with a message naming BREAKER_WHISPER_PORT`() {
        val env = baseEnv - "BREAKER_WHISPER_PORT"
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_PORT: ${error.message}", error.message!!.contains("BREAKER_WHISPER_PORT"))
    }

    @Test
    fun `BREAKER_WHISPER_PORT not a number refuses with a message naming BREAKER_WHISPER_PORT`() {
        val env = baseEnv + ("BREAKER_WHISPER_PORT" to "abc")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_PORT: ${error.message}", error.message!!.contains("BREAKER_WHISPER_PORT"))
    }

    @Test
    fun `BREAKER_WHISPER_PORT zero refuses with a message naming BREAKER_WHISPER_PORT`() {
        val env = baseEnv + ("BREAKER_WHISPER_PORT" to "0")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_PORT: ${error.message}", error.message!!.contains("BREAKER_WHISPER_PORT"))
    }

    @Test
    fun `BREAKER_WHISPER_PORT above 65535 refuses with a message naming BREAKER_WHISPER_PORT`() {
        val env = baseEnv + ("BREAKER_WHISPER_PORT" to "65536")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_PORT: ${error.message}", error.message!!.contains("BREAKER_WHISPER_PORT"))
    }

    @Test
    fun `BREAKER_WHISPER_DB missing refuses with a message naming BREAKER_WHISPER_DB`() {
        val env = baseEnv - "BREAKER_WHISPER_DB"
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_DB: ${error.message}", error.message!!.contains("BREAKER_WHISPER_DB"))
    }

    @Test
    fun `BREAKER_WHISPER_DB blank refuses with a message naming BREAKER_WHISPER_DB`() {
        val env = baseEnv + ("BREAKER_WHISPER_DB" to "   ")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_DB: ${error.message}", error.message!!.contains("BREAKER_WHISPER_DB"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL missing refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv - "BREAKER_WHISPER_FORWARDER_URL"
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL blank refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL set but blank refuses`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "   ")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL not a valid URL refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "not a url")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL with ftp scheme refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "ftp://example.com")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL without scheme refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "localhost:9000")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL without host refuses with a message naming BREAKER_WHISPER_FORWARDER_URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "http:///path")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error.message}", error.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `BREAKER_WHISPER_PORT not a number error includes the actual value`() {
        val env = baseEnv + ("BREAKER_WHISPER_PORT" to "abc")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must include the value 'abc': ${error.message}", error.message!!.contains("abc"))
    }

    @Test
    fun `BREAKER_WHISPER_PORT out of range error includes the actual value`() {
        val env = baseEnv + ("BREAKER_WHISPER_PORT" to "99999")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must include the value '99999': ${error.message}", error.message!!.contains("99999"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL ftp scheme error includes the scheme value`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "ftp://example.com")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must include scheme 'ftp': ${error.message}", error.message!!.contains("ftp"))
    }

    @Test
    fun `BREAKER_WHISPER_FORWARDER_URL error message does not leak the full URL`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "ftp://secret-host.example.com")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must not contain 'secret-host.example.com': ${error.message}", !error.message!!.contains("secret-host.example.com"))
    }

    @Test
    fun `BREAKER_WHISPER_HOST missing gives the default bind address`() {
        val env = baseEnv - "BREAKER_WHISPER_HOST"
        val config = WhisperConfig.fromEnv(env)
        assertEquals("127.0.0.1", config.bindAddress)
    }

    @Test
    fun `BREAKER_WHISPER_HOST set but blank falls back to the default bind address`() {
        val env = baseEnv + ("BREAKER_WHISPER_HOST" to "   ")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("127.0.0.1", config.bindAddress)
    }

    @Test
    fun `all present parses correctly`() {
        val env = baseEnv + ("BREAKER_WHISPER_HOST" to "0.0.0.0")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals(8080, config.port)
        assertEquals("/tmp/test.db", config.databasePath)
        assertEquals("http://localhost:9000", config.forwarderBaseUrl)
    }

    @Test
    fun `an https forwarder URL is accepted`() {
        val env = baseEnv + ("BREAKER_WHISPER_FORWARDER_URL" to "https://example.com")
        val config = WhisperConfig.fromEnv(env)
        assertEquals("https://example.com", config.forwarderBaseUrl)
    }

    @Test
    fun `BREAKER_WHISPER_RESULT_TTL_S parses a given value`() {
        val env = baseEnv + ("BREAKER_WHISPER_RESULT_TTL_S" to "120")
        val config = WhisperConfig.fromEnv(env)
        assertEquals(120, config.resultTtlSeconds)
    }

    @Test
    fun `BREAKER_WHISPER_RESULT_TTL_S missing defaults to 600 seconds`() {
        val env = baseEnv - "BREAKER_WHISPER_RESULT_TTL_S"
        val config = WhisperConfig.fromEnv(env)
        assertEquals(600, config.resultTtlSeconds)
    }

    @Test
    fun `BREAKER_WHISPER_RESULT_TTL_S a bad value refuses with a message naming BREAKER_WHISPER_RESULT_TTL_S`() {
        for (bad in listOf("   ", "-5", "abc", "0")) {
            val env = baseEnv + ("BREAKER_WHISPER_RESULT_TTL_S" to bad)
            val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
            assertTrue("message must name BREAKER_WHISPER_RESULT_TTL_S for '$bad': ${error.message}", error.message!!.contains("BREAKER_WHISPER_RESULT_TTL_S"))
        }
    }

    @Test
    fun `BREAKER_WHISPER_RESULT_TTL_S above 86400 refuses with a message naming BREAKER_WHISPER_RESULT_TTL_S`() {
        val env = baseEnv + ("BREAKER_WHISPER_RESULT_TTL_S" to "86401")
        val error = expectFailure<IllegalStateException> { WhisperConfig.fromEnv(env) }
        assertTrue("message must name BREAKER_WHISPER_RESULT_TTL_S: ${error.message}", error.message!!.contains("BREAKER_WHISPER_RESULT_TTL_S"))
    }

    @Test
    fun `BREAKER_WHISPER_RESULT_TTL_S 86400 parses as the upper bound`() {
        val env = baseEnv + ("BREAKER_WHISPER_RESULT_TTL_S" to "86400")
        val config = WhisperConfig.fromEnv(env)
        assertEquals(86400, config.resultTtlSeconds)
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

    // the old WHISPER_* names are not read (half-rename goes red)

    @Test
    fun `WHISPER_PORT alone is not read (BREAKER_WHISPER_PORT is required)`() {
        val env = mapOf(
            "BREAKER_WHISPER_HOST" to "127.0.0.1",
            "WHISPER_PORT" to "8080",
            "BREAKER_WHISPER_DB" to "/tmp/test.db",
            "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000"
        )
        val error = kotlin.runCatching { WhisperConfig.fromEnv(env) }.exceptionOrNull() as? IllegalStateException
        assertNotNull("WHISPER_PORT must not be read", error)
        assertTrue("message must name BREAKER_WHISPER_PORT: ${error?.message}", error!!.message!!.contains("BREAKER_WHISPER_PORT"))
    }

    @Test
    fun `WHISPER_DATABASE_PATH alone is not read (BREAKER_WHISPER_DB is required)`() {
        val env = mapOf(
            "BREAKER_WHISPER_HOST" to "127.0.0.1",
            "BREAKER_WHISPER_PORT" to "8080",
            "WHISPER_DATABASE_PATH" to "/tmp/test.db",
            "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000"
        )
        val error = kotlin.runCatching { WhisperConfig.fromEnv(env) }.exceptionOrNull() as? IllegalStateException
        assertNotNull("WHISPER_DATABASE_PATH must not be read", error)
        assertTrue("message must name BREAKER_WHISPER_DB: ${error?.message}", error!!.message!!.contains("BREAKER_WHISPER_DB"))
    }

    @Test
    fun `WHISPER_FORWARDER_URL alone is not read (BREAKER_WHISPER_FORWARDER_URL is required)`() {
        val env = mapOf(
            "BREAKER_WHISPER_HOST" to "127.0.0.1",
            "BREAKER_WHISPER_PORT" to "8080",
            "BREAKER_WHISPER_DB" to "/tmp/test.db",
            "WHISPER_FORWARDER_URL" to "http://localhost:9000"
        )
        val error = kotlin.runCatching { WhisperConfig.fromEnv(env) }.exceptionOrNull() as? IllegalStateException
        assertNotNull("WHISPER_FORWARDER_URL must not be read", error)
        assertTrue("message must name BREAKER_WHISPER_FORWARDER_URL: ${error?.message}", error!!.message!!.contains("BREAKER_WHISPER_FORWARDER_URL"))
    }

    @Test
    fun `WHISPER_BIND_ADDRESS alone is not read (BREAKER_WHISPER_HOST is used)`() {
        val env = mapOf(
            "WHISPER_BIND_ADDRESS" to "0.0.0.0",
            "BREAKER_WHISPER_PORT" to "8080",
            "BREAKER_WHISPER_DB" to "/tmp/test.db",
            "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000"
        )
        val config = WhisperConfig.fromEnv(env)
        // WHISPER_BIND_ADDRESS is ignored; default 127.0.0.1 is used
        assertEquals("127.0.0.1", config.bindAddress)
    }
}
