package dev.breaker.server.syncapi.sync

import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pull route's body: the empty array, the four fields, the time form, the order and the escaping. */
internal class TranscriptionsPullTest : SyncRouteTestBase() {

    private val enc = validEncrypted("hello")

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `an account with nothing stored pulls an empty array`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(auth)
            assertEquals("status", HttpStatusCode.OK, response.status)
            assertEquals("body", "[]", response.bodyAsText())
        }
    }

    @Test
    fun `a pull returns exactly the four fields of a row in the pinned form`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val expected = "[{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$enc\"," +
            "\"stored_at\":\"$START_STORED_AT\"}]"
        testApplication {
            application { installSyncRoutes() }
            client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", enc)))
            assertEquals("body", expected, client.pullSync(auth).bodyAsText())
        }
    }

    @Test
    fun `each row keeps its own client id, key version and encrypted text`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        val first = validEncrypted("first")
        val second = validEncrypted("second")
        insertRow(alice, CLIENT_ID, 0, first, 1_700_000_000_000L)
        insertRow(alice, SECOND_CLIENT_ID, 1, second, 1_700_000_000_000L)
        testApplication {
            application { installSyncRoutes() }
            val body = client.pullSync(auth).bodyAsText()
            assertTrue("the first row", body.contains("\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$first\""))
            assertTrue("the second row", body.contains("\"client_id\":\"$SECOND_CLIENT_ID\",\"key_version\":1,\"encrypted\":\"$second\""))
        }
    }

    @Test
    fun `stored_at is whole seconds in RFC 3339 form ending in Z`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        val stamp = Regex("\"stored_at\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\"")
        testApplication {
            application { installSyncRoutes() }
            client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", enc)))
            assertTrue("the stored_at form", stamp.containsMatchIn(client.pullSync(auth).bodyAsText()))
        }
    }

    @Test
    fun `stored_at on the wire is the whole second when the clock stood 600 milliseconds past it`() =
        runBlocking<Unit> {
            val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
            clock.advance(Duration.ofMillis(600))
            testApplication {
                application { installSyncRoutes() }
                client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", enc)))
                val body = client.pullSync(auth).bodyAsText()
                assertTrue("the stored time is the whole second", body.contains("\"stored_at\":\"$START_STORED_AT\""))
                assertTrue("not rounded up", !body.contains("22:13:21Z"))
            }
        }

    @Test
    fun `a stored time with a millisecond part is shown cut to its second`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        insertRow(alice, CLIENT_ID, 0, enc, TokenFixtures.START.toEpochMilli() + 600L)
        testApplication {
            application { installSyncRoutes() }
            val body = client.pullSync(auth).bodyAsText()
            assertTrue("cut to the second", body.contains("\"stored_at\":\"$START_STORED_AT\""))
            assertTrue("not rounded up", !body.contains("22:13:21Z"))
        }
    }

    @Test
    fun `two rows stored in different seconds pull as exactly two objects in order`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", enc)))
            clock.advance(Duration.ofSeconds(1))
            client.postSync(auth, utf8(pushBody(SECOND_CLIENT_ID, "0", enc)))
            val expected = "[{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$enc\"," +
                "\"stored_at\":\"$START_STORED_AT\"},{\"client_id\":\"$SECOND_CLIENT_ID\",\"key_version\":0," +
                "\"encrypted\":\"$enc\",\"stored_at\":\"2023-11-14T22:13:21Z\"}]"
            assertEquals("body", expected, client.pullSync(auth).bodyAsText())
        }
    }

    @Test
    fun `rows sharing one stored time come back ordered by client id`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            client.postSync(auth, utf8(pushBody(SECOND_CLIENT_ID, "0", enc)))
            client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", enc)))
            val body = client.pullSync(auth).bodyAsText()
            assertTrue("the smaller client id comes first", body.indexOf(CLIENT_ID) < body.indexOf(SECOND_CLIENT_ID))
        }
    }

    @Test
    fun `a pull answers with application json`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            val response = client.pullSync(auth)
            assertEquals("content type", ContentType.Application.Json, response.contentType()?.withoutParameters())
        }
    }

    @Test
    fun `a stored value that needs escapes comes back escaped and parses to the same text`() = runBlocking<Unit> {
        val alice = account("alice", 1)
        val auth = bearer(issueSession(alice).secret.reveal())
        val raw = "a\"b\\c\u0001d\ne\tf"
        TranscriptionFixtures.push(transcriptionStore, alice, CLIENT_ID, 0, raw)
        val expected = "[{\"client_id\":\"$CLIENT_ID\",\"key_version\":0," +
            "\"encrypted\":\"a\\\"b\\\\c\\u0001d\\ne\\tf\",\"stored_at\":\"$START_STORED_AT\"}]"
        testApplication {
            application { installSyncRoutes() }
            assertEquals("body", expected, client.pullSync(auth).bodyAsText())
        }
    }
}
