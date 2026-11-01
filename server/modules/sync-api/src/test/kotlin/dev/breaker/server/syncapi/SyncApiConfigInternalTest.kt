package dev.breaker.server.syncapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiConfigInternalTest {

    private val db = SyncApiConfig.ENV_DB
    private val host = SyncApiConfig.ENV_HOST
    private val port = SyncApiConfig.ENV_PORT
    private val internalHost = SyncApiConfig.ENV_INTERNAL_HOST
    private val internalPort = SyncApiConfig.ENV_INTERNAL_PORT
    private val serviceKeyFile = SyncApiConfig.ENV_SERVICE_KEY_FILE

    // The database path that every test uses unless it names another one.
    private val databasePath = "state/sync.db"

    // Every spelling of the wildcard address that the one wildcard test must recognise.
    private val wildcardFamily = listOf("0.0.0.0", "::", "[::]", "::0", "0:0:0:0:0:0:0:0", "[0::0]", "0")

    // Builds the environment from the database path and the pairs the test names, and nothing else.
    private fun config(vararg pairs: Pair<String, String>): SyncApiConfig =
        SyncApiConfig.fromEnv(mapOf(db to databasePath) + pairs)

    private fun refused(vararg pairs: Pair<String, String>): IllegalArgumentException {
        val outcome = runCatching { config(*pairs) }
        assertTrue("sync-api: the environment was expected to be refused but was accepted", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue("sync-api: expected IllegalArgumentException but got $error", error is IllegalArgumentException)
        return error as IllegalArgumentException
    }

    private fun assertNamed(error: IllegalArgumentException, variable: String) {
        val message = error.message
        assertTrue("sync-api: refusal for $variable has no message", message != null)
        assertTrue("sync-api: message must start with sync-api: but was: $message", message!!.startsWith("sync-api:"))
        assertTrue("sync-api: message must name $variable but was: $message", message.contains(variable))
    }

    private fun assertNamesBothPorts(error: IllegalArgumentException) {
        val message = error.message
        assertTrue("sync-api: refusal has no message", message != null)
        assertTrue("sync-api: message must start with sync-api: but was: $message", message!!.startsWith("sync-api:"))
        assertTrue("sync-api: message must name $internalPort but was: $message", message.contains(internalPort))
        assertTrue("sync-api: message must name $port but was: $message", message.contains(port))
    }

    @Test
    fun `the defaults are loopback on 8080 and 8081 with the key file next to the database`() {
        val settings = config()
        assertEquals("sync-api: public host default", "127.0.0.1", settings.host)
        assertEquals("sync-api: public port default", 8080, settings.port)
        assertEquals("sync-api: internal host default", "127.0.0.1", settings.internalHost)
        assertEquals("sync-api: internal port default", 8081, settings.internalPort)
        assertEquals("sync-api: key file default", "state/service-key", settings.serviceKeyFile)
    }

    @Test
    fun `a database in another folder puts the key file in that folder`() {
        val settings = config(db to "data/breaker/sync.db")
        assertEquals("sync-api: key file next to the database", "data/breaker/service-key", settings.serviceKeyFile)
    }

    @Test
    fun `a bare database file name puts the key file in the current folder as a bare name`() {
        val settings = config(db to "sync.db")
        assertEquals("sync-api: bare key file name", "service-key", settings.serviceKeyFile)
    }

    @Test
    fun `an explicit key file path is kept exactly as given`() {
        val settings = config(serviceKeyFile to "keys/custom-key")
        assertEquals("sync-api: explicit key file", "keys/custom-key", settings.serviceKeyFile)
    }

    @Test
    fun `a loopback internal host is kept as given`() {
        val settings = config(internalHost to "::1")
        assertEquals("sync-api: internal host", "::1", settings.internalHost)
    }

    @Test
    fun `each wildcard spelling of the family is refused for the internal host and the refusal names the variable`() {
        for (wildcard in wildcardFamily) {
            assertNamed(refused(internalHost to wildcard), internalHost)
        }
    }

    @Test
    fun `the three plain wildcard spellings with leading and trailing spaces are refused for the internal host`() {
        for (spaced in listOf(" 0.0.0.0 ", "  ::  ", " [::] ")) {
            assertNamed(refused(internalHost to spaced), internalHost)
        }
    }

    @Test
    fun `an empty or blank internal host is refused naming the variable`() {
        for (value in listOf("", "   ")) {
            assertNamed(refused(internalHost to value), internalHost)
        }
    }

    @Test
    fun `the internal host refusal never prints the wildcard it refused`() {
        val error = refused(internalHost to "0.0.0.0")
        assertFalse("sync-api: the refusal printed the rejected value", error.message!!.contains("0.0.0.0"))
    }

    @Test
    fun `every malformed or out of range internal port is refused and the message names the internal port variable`() {
        val bad = listOf("", " 80", "+80", "80 ", "abc", "0", "65536", "-1", "99999999999", "8.0", "\u0668\u0660")
        for (value in bad) {
            assertNamed(refused(internalPort to value), internalPort)
        }
    }

    @Test
    fun `the lowest and highest internal ports are accepted`() {
        assertEquals("sync-api: internal port 1", 1, config(internalPort to "1").internalPort)
        assertEquals("sync-api: internal port 65535", 65535, config(internalPort to "65535").internalPort)
    }

    @Test
    fun `a set but blank value is refused naming its variable for each of the three new variables`() {
        for (variable in listOf(internalHost, internalPort, serviceKeyFile)) {
            for (value in listOf("", "   ")) {
                assertNamed(refused(variable to value), variable)
            }
        }
    }

    @Test
    fun `the same port on the same loopback host name is refused naming both port variables`() {
        assertNamesBothPorts(refused(port to "9000", host to "localhost", internalHost to "localhost", internalPort to "9000"))
    }

    @Test
    fun `the public wildcard family collides with any internal host on the same port`() {
        for (wildcard in wildcardFamily + listOf(" 0.0.0.0 ", "  ::  ", " [::] ")) {
            assertNamesBothPorts(refused(host to wildcard, port to "9000", internalHost to "127.0.0.1", internalPort to "9000"))
        }
    }

    @Test
    fun `two loopback spellings on the same port collide in either order`() {
        val pairs = listOf("localhost" to "127.0.0.1", "127.0.0.1" to "localhost", "::1" to "[::1]", "[::1]" to "localhost")
        for ((publicHost, internalName) in pairs) {
            assertNamesBothPorts(refused(host to publicHost, port to "9000", internalHost to internalName, internalPort to "9000"))
        }
    }

    @Test
    fun `a public loopback name with capital letters and spaces still collides with loopback on the same port`() {
        assertNamesBothPorts(refused(host to " LocalHost ", port to "9000", internalHost to "127.0.0.1", internalPort to "9000"))
    }

    @Test
    fun `two equal host names on the same port collide even when one is spaced and capitalised`() {
        assertNamesBothPorts(refused(host to "service.example", port to "9000", internalHost to "service.example", internalPort to "9000"))
        assertNamesBothPorts(refused(host to " Service.Example ", port to "9000", internalHost to "service.example", internalPort to "9000"))
    }

    @Test
    fun `a named public host and a loopback internal host on the same port are accepted`() {
        val settings = config(host to "service.example", port to "9000", internalHost to "127.0.0.1", internalPort to "9000")
        assertEquals("sync-api: public port kept", 9000, settings.port)
        assertEquals("sync-api: internal port kept", 9000, settings.internalPort)
    }

    @Test
    fun `different ports never collide even with the public wildcard or loopback`() {
        val wildcard = config(host to "0.0.0.0", port to "9000", internalHost to "127.0.0.1", internalPort to "9001")
        assertEquals("sync-api: public port with a wildcard", 9000, wildcard.port)
        assertEquals("sync-api: internal port with a wildcard", 9001, wildcard.internalPort)
        val loopback = config(host to "localhost", port to "9000", internalHost to "localhost", internalPort to "9001")
        assertEquals("sync-api: internal port on loopback", 9001, loopback.internalPort)
    }

    @Test
    fun `a NUL character in the database path is refused with a fresh message that copies neither the text nor a NUL`() {
        val error = refused(db to "state/bad\u0000.db")
        assertNamed(error, db)
        assertFalse("sync-api: the refusal copied the path text", error.message!!.contains("state/bad"))
        assertFalse("sync-api: the refusal holds a NUL character", error.message!!.contains("\u0000"))
    }

    @Test
    fun `a NUL character in the explicit key file path is refused with a fresh message that copies neither the text nor a NUL`() {
        val error = refused(serviceKeyFile to "keys/bad\u0000key")
        assertNamed(error, serviceKeyFile)
        assertFalse("sync-api: the refusal copied the path text", error.message!!.contains("keys/bad"))
        assertFalse("sync-api: the refusal holds a NUL character", error.message!!.contains("\u0000"))
    }
}
