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

    private val manifest = SyncApiConfig.ENV_MANIFEST_FILE

    @Test
    fun `when the manifest env is absent the manifest file path is empty string`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db"))
        assertEquals("default manifest path is empty", "", config.manifestFile)
    }

    @Test
    fun `a manifest path is accepted as given`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db", manifest to "/data/release.json"))
        assertEquals("manifest path", "/data/release.json", config.manifestFile)
    }

    @Test
    fun `a manifest path set but blank is refused`() {
        assertNamed(refused(mapOf(db to "x.db", manifest to "")), manifest)
        assertNamed(refused(mapOf(db to "x.db", manifest to "   ")), manifest)
    }

    @Test
    fun `an invalid manifest path text is refused`() {
        assertNamed(refused(mapOf(db to "x.db", manifest to "/data/\u0000release.json")), manifest)
    }

    private val gitTag = SyncApiConfig.ENV_GIT_TAG

    @Test
    fun `when the git tag env is absent the tag defaults to unknown`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db"))
        assertEquals("default git tag is unknown", "unknown", config.gitTag)
    }

    @Test
    fun `a git tag is accepted as given`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db", gitTag to "v2.3.0-rc1"))
        assertEquals("git tag", "v2.3.0-rc1", config.gitTag)
    }

    @Test
    fun `a git tag of 64 characters is accepted at the upper bound`() {
        val tag = "a".repeat(64)
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db", gitTag to tag))
        assertEquals("64-char git tag", tag, config.gitTag)
    }

    @Test
    fun `a git tag set but blank is refused`() {
        assertNamed(refused(mapOf(db to "x.db", gitTag to "")), gitTag)
        assertNamed(refused(mapOf(db to "x.db", gitTag to "   ")), gitTag)
    }

    @Test
    fun `a git tag with a space is refused`() {
        assertNamed(refused(mapOf(db to "x.db", gitTag to "v1.0 tag")), gitTag)
    }

    @Test
    fun `a git tag of 65 characters is refused`() {
        assertNamed(refused(mapOf(db to "x.db", gitTag to "a".repeat(65))), gitTag)
    }

    @Test
    fun `a git tag with a control character is refused`() {
        assertNamed(refused(mapOf(db to "x.db", gitTag to "v1.0\u0000tag")), gitTag)
    }

    @Test
    fun `a git tag may use both ends of the printable range`() {
        for (tag in listOf("!", "~", "!v1~")) {
            val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db", gitTag to tag))
            assertEquals("git tag $tag", tag, config.gitTag)
        }
    }

    @Test
    fun `a git tag with the delete character is refused`() {
        assertNamed(refused(mapOf(db to "x.db", gitTag to "\u007F")), gitTag)
        assertNamed(refused(mapOf(db to "x.db", gitTag to "v1\u007Ftag")), gitTag)
    }

    @Test
    fun `the git tag is read from the variable named BREAKER_SYNC_API_GIT_TAG`() {
        val config = SyncApiConfig.fromEnv(mapOf(db to "/data/sync.db", "BREAKER_SYNC_API_GIT_TAG" to "literal-1"))
        assertEquals("git tag through the literal name", "literal-1", config.gitTag)
    }

    @Test
    fun `an empty and a spaces only git tag get the blank message, not the length message`() {
        val blankMessage = "sync-api: $gitTag is set but blank"
        val lengthMessage = "sync-api: $gitTag must be 1 to 64 characters"
        assertEquals("empty tag", blankMessage, refused(mapOf(db to "x.db", gitTag to "")).message)
        assertEquals("spaces only tag", blankMessage, refused(mapOf(db to "x.db", gitTag to "   ")).message)
        assertEquals("65 character tag", lengthMessage, refused(mapOf(db to "x.db", gitTag to "a".repeat(65))).message)
    }
}
