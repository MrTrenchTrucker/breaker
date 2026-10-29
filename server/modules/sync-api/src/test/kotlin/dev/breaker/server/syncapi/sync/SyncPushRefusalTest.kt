package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The push route on refused bodies: each refusal has its fixed status and body, and stores nothing. */
internal class SyncPushRefusalTest : SyncRouteTestBase() {

    private val enc = "AQID"

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `malformed JSON shapes answer 400 and store nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val cases = listOf(
            "a bare word" to utf8("not json"),
            "an array" to utf8("[]"),
            "an empty body" to utf8(""),
            "a truncated object" to utf8(pushBody(CLIENT_ID, "0", enc).dropLast(1)),
        )
        testApplication {
            application { installSyncRoutes() }
            for ((label, body) in cases) {
                val response = client.postSync(auth, body)
                assertEquals("$label answers 400", HttpStatusCode.BadRequest, response.status)
                assertEquals("$label body", BAD_REQUEST_BODY, response.bodyAsText())
            }
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `wrong field shapes answer 400 and store nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val cases = listOf(
            "a missing key" to utf8("""{"client_id":"$CLIENT_ID","encrypted":"$enc"}"""),
            "an unknown key" to utf8("""{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"$enc","x":1}"""),
            "a duplicate key" to utf8("""{"client_id":"$CLIENT_ID","client_id":"$CLIENT_ID","key_version":0,"encrypted":"$enc"}"""),
            "a key version as a string" to utf8(pushBody(CLIENT_ID, "\"0\"", enc)),
            "a negative key version" to utf8(pushBody(CLIENT_ID, "-1", enc)),
            "a fractional key version" to utf8(pushBody(CLIENT_ID, "1.0", enc)),
            "an exponent key version" to utf8(pushBody(CLIENT_ID, "1e0", enc)),
            "a key version with a leading zero" to utf8(pushBody(CLIENT_ID, "01", enc)),
            "a key version above the Int range" to utf8(pushBody(CLIENT_ID, "2147483648", enc)),
            "a byte order mark" to utf8("\uFEFF" + pushBody(CLIENT_ID, "0", enc)),
            "bytes after the object" to utf8(pushBody(CLIENT_ID, "0", enc) + " x"),
            "bytes that are not UTF-8" to byteArrayOf(0xFF.toByte()) + utf8(pushBody(CLIENT_ID, "0", enc)),
        )
        testApplication {
            application { installSyncRoutes() }
            for ((label, body) in cases) {
                val response = client.postSync(auth, body)
                assertEquals("$label answers 400", HttpStatusCode.BadRequest, response.status)
                assertEquals("$label body", BAD_REQUEST_BODY, response.bodyAsText())
            }
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a client id that is upper case, short, long, not hex or without hyphens answers 400 and stores nothing`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            val cases = listOf(
                "an upper case client id" to CLIENT_ID.uppercase(),
                "a short client id" to CLIENT_ID.dropLast(1),
                "a long client id" to CLIENT_ID + "0",
                "a client id with a non hex character" to "g" + CLIENT_ID.drop(1),
                "a client id without hyphens" to CLIENT_ID.replace("-", ""),
            )
            testApplication {
                application { installSyncRoutes() }
                for ((label, id) in cases) {
                    val response = client.postSync(auth, utf8(pushBody(id, "0", validEncrypted())))
                    assertEquals("$label answers 400", HttpStatusCode.BadRequest, response.status)
                    assertEquals("$label body", BAD_REQUEST_BODY, response.bodyAsText())
                }
            }
            assertEquals("nothing stored", 0L, storedRowCount())
        }

    @Test
    fun `an encrypted text that is not base64 or not a sync body answers 400 and stores nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val cases = listOf(
            "text that is not base64" to "!!!!",
            "base64 without padding" to "AQ",
            "base64 with a URL safe character" to "-_8=",
            "base64 of text that is not an object" to encryptedOf("hello"),
            "base64 of an array" to encryptedOf("[]"),
            "base64 of a sync body with another source" to encryptedOf(syncBodyJson("hello", source = "Local")),
            "base64 of a sync body with a missing key" to encryptedOf("""{"v":1,"text":"hello"}"""),
        )
        testApplication {
            application { installSyncRoutes() }
            for ((label, text) in cases) {
                val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", text)))
                assertEquals("$label answers 400", HttpStatusCode.BadRequest, response.status)
                assertEquals("$label body", BAD_REQUEST_BODY, response.bodyAsText())
            }
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a key version above the current answers 409 and stores nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", validEncrypted())))
            assertEquals("status", HttpStatusCode.Conflict, response.status)
            assertEquals("body", CONFLICT_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a key version above the current with text that is not base64 answers 400 and stores nothing`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            testApplication {
                application { installSyncRoutes() }
                val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", "!!!!")))
                assertEquals("status", HttpStatusCode.BadRequest, response.status)
                assertEquals("body", BAD_REQUEST_BODY, response.bodyAsText())
            }
            assertEquals("nothing stored", 0L, storedRowCount())
        }

    @Test
    fun `a key version above the current with a bad client id answers 400`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID.uppercase(), "1", validEncrypted())))
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", BAD_REQUEST_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a key version above the current with text over the decoded limit answers 413`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", encryptedOfSize(65537))))
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("body", PAYLOAD_TOO_LARGE_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `key version one with an empty encrypted text answers 400 and not 409`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", "")))
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", BAD_REQUEST_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `the same valid body with a good client id answers 204 and stores one row`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", validEncrypted())))
            assertEquals("status", HttpStatusCode.NoContent, response.status)
        }
        assertEquals("one row stored", 1L, storedRowCount())
    }

    @Test
    fun `a key version one with text that is not padded answers 400 and stores nothing`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            for (text in listOf("AQ", "AQI")) {
                val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", text)))
                assertEquals("$text answers 400", HttpStatusCode.BadRequest, response.status)
                assertEquals("$text body", BAD_REQUEST_BODY, response.bodyAsText())
            }
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a text with non zero trailing bits answers 400 at key version zero and at key version one`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            testApplication {
                application { installSyncRoutes() }
                for (version in listOf("0", "1")) {
                    for (text in listOf("AR==", "AQJ=")) {
                        val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, version, text)))
                        assertEquals("$text at key version $version", HttpStatusCode.BadRequest, response.status)
                    }
                }
            }
            assertEquals("nothing stored", 0L, storedRowCount())
        }

    @Test
    fun `a non canonical text over the decoded limit answers 400 and not 413`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val texts = listOf("A".repeat(87383) + "!", "A".repeat(87380) + "AQJ=")
        testApplication {
            application { installSyncRoutes() }
            for (version in listOf("0", "1")) {
                for (text in texts) {
                    val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, version, text)))
                    assertEquals("key version $version answers 400", HttpStatusCode.BadRequest, response.status)
                }
            }
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }

    @Test
    fun `a key version above the current with canonical base64 that is not a sync body answers 409`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            testApplication {
                application { installSyncRoutes() }
                val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", encryptedOf("hello"))))
                assertEquals("status", HttpStatusCode.Conflict, response.status)
                assertEquals("body", CONFLICT_BODY, response.bodyAsText())
            }
            assertEquals("nothing stored", 0L, storedRowCount())
        }

    @Test
    fun `a key version above the current with exactly 65536 decoded bytes answers 409`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.postSync(auth, utf8(pushBody(CLIENT_ID, "1", encryptedOfSize(65536))))
            assertEquals("status", HttpStatusCode.Conflict, response.status)
            assertEquals("body", CONFLICT_BODY, response.bodyAsText())
        }
        assertEquals("nothing stored", 0L, storedRowCount())
    }
}
