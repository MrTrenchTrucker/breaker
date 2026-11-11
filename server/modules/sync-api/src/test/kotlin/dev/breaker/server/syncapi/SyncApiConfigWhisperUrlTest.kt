package dev.breaker.server.syncapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BREAKER_WHISPER_HEALTH_URL: unset is not listed, a plain http or https URL is kept, anything else stops the start. */
class SyncApiConfigWhisperUrlTest {

    private val db = SyncApiConfig.ENV_DB
    private val url = SyncApiConfig.ENV_WHISPER_HEALTH_URL

    private fun refused(env: Map<String, String>): IllegalArgumentException {
        val outcome = runCatching { SyncApiConfig.fromEnv(env) }
        assertTrue("expected the environment $env to be refused", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue("expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        return error as IllegalArgumentException
    }

    private fun assertNamed(error: IllegalArgumentException) {
        val message = error.message
        assertNotNull("the refusal has no message", message)
        assertTrue("message must start with 'sync-api:' but was: $message", message!!.startsWith("sync-api:"))
        assertTrue("message must name $url but was: $message", message.contains(url))
    }

    @Test
    fun `an unset url is empty and means whisper-server is not listed`() {
        assertEquals("", SyncApiConfig.fromEnv(mapOf(db to "x.db")).whisperHealthUrl)
    }

    @Test
    fun `a plain http or https url is kept as written`() {
        for (good in listOf("http://whisper:9000/health", "https://whisper.internal/health", "http://127.0.0.1:9000/health", "http://[::1]:9000/health")) {
            assertEquals("kept: $good", good, SyncApiConfig.fromEnv(mapOf(db to "x.db", url to good)).whisperHealthUrl)
        }
    }

    @Test
    fun `a blank url is refused`() {
        assertNamed(refused(mapOf(db to "x.db", url to "")))
        assertNamed(refused(mapOf(db to "x.db", url to "   ")))
    }

    @Test
    fun `a value that is not a plain http or https url is refused`() {
        for (bad in listOf("whisper:9000/health", "ftp://whisper/health", "file:///etc/passwd", "http://", "https:///health", "http://:9000/health", "http://user:secret@whisper/health", "http://user@whisper/health")) {
            assertNamed(refused(mapOf(db to "x.db", url to bad)))
        }
    }

    @Test
    fun `an upper case or mixed case http scheme is accepted and kept as written`() {
        for (good in listOf("HTTP://host/health", "Https://host.internal/health")) {
            assertEquals("kept: $good", good, SyncApiConfig.fromEnv(mapOf(db to "x.db", url to good)).whisperHealthUrl)
        }
    }

    @Test
    fun `a scheme that is not http or https is refused in any case`() {
        for (bad in listOf("FTP://host/health", "Ftp://host/health", "ws://host/health")) {
            assertNamed(refused(mapOf(db to "x.db", url to bad)))
        }
    }

    @Test
    fun `an empty bracket host and a host with no closing bracket are refused`() {
        assertNamed(refused(mapOf(db to "x.db", url to "http://[]/health")))
        assertNamed(refused(mapOf(db to "x.db", url to "http://[::1/health")))
    }

    @Test
    fun `the setting is read under the exact name BREAKER_WHISPER_HEALTH_URL`() {
        assertEquals("sync-api: the constant", "BREAKER_WHISPER_HEALTH_URL", url)
        val config = SyncApiConfig.fromEnv(mapOf(db to "x.db", "BREAKER_WHISPER_HEALTH_URL" to "http://whisper.test/health"))
        assertEquals("sync-api: read under the literal name", "http://whisper.test/health", config.whisperHealthUrl)
        val other = SyncApiConfig.fromEnv(mapOf(db to "x.db", "BREAKER_WHISPER_HEALTH_URLS" to "http://whisper.test/health"))
        assertEquals("sync-api: another name is not read", "", other.whisperHealthUrl)
    }
}
