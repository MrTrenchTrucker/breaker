package dev.breaker.server.syncapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiConfigTest {

    private val db = SyncApiConfig.ENV_DB
    private val port = SyncApiConfig.ENV_PORT
    private val host = SyncApiConfig.ENV_HOST

    private fun refused(env: Map<String, String>): IllegalArgumentException {
        val outcome = runCatching { SyncApiConfig.fromEnv(env) }
        assertTrue("expected the environment $env to be refused, but it was accepted", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue("expected IllegalArgumentException for $env but got $error", error is IllegalArgumentException)
        return error as IllegalArgumentException
    }

    private fun assertNamed(error: IllegalArgumentException, variable: String) {
        val message = error.message
        assertNotNull("refusal for $variable has no message", message)
        assertTrue("message must start with 'sync-api:' but was: $message", message!!.startsWith("sync-api:"))
        assertTrue("message must name $variable but was: $message", message.contains(variable))
    }

    @Test
    fun `host and port fall back to their defaults when only the database path is set`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db"))
        assertEquals("default host", "127.0.0.1", config.host)
        assertEquals("default port", 8080, config.port)
        assertEquals("database path", "/data/sync.db", config.dbPath)
    }

    @Test
    fun `an empty environment is refused because the database path is required`() {
        assertNamed(refused(emptyMap()), db)
    }

    @Test
    fun `explicit host port and database path are used as given`() {
        val config = SyncApiConfig.fromEnv(mapOf(host to "0.0.0.0", port to "9090", db to "state/sync.db"))
        assertEquals("host", "0.0.0.0", config.host)
        assertEquals("port", 9090, config.port)
        assertEquals("database path", "state/sync.db", config.dbPath)
    }

    @Test
    fun `the lowest and the highest valid port are accepted`() {
        assertEquals("port 1", 1, SyncApiConfig.fromEnv(mapOf(port to "1", db to "x.db")).port)
        assertEquals("port 65535", 65535, SyncApiConfig.fromEnv(mapOf(port to "65535", db to "x.db")).port)
    }

    @Test
    fun `every malformed or out of range port is refused and the message names the port variable`() {
        val bad = listOf("", " 80", "+80", "80 ", "abc", "0", "65536", "-1", "99999999999", "8.0", "\u0668\u0660")
        for (value in bad) {
            assertNamed(refused(mapOf(port to value, db to "x.db")), port)
        }
    }

    @Test
    fun `a host that is set but blank is refused even though an absent host is fine`() {
        assertNamed(refused(mapOf(host to "", db to "x.db")), host)
        assertNamed(refused(mapOf(host to "   ", db to "x.db")), host)
    }

    @Test
    fun `a blank database path is refused`() {
        assertNamed(refused(mapOf(db to "")), db)
        assertNamed(refused(mapOf(db to "  ")), db)
    }
}
