package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenSecret
import dev.breaker.server.syncapi.tokens.TokenStore

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class AdminCreateAgentTokenRouteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
    }

    // ---------- helpers ----------

    private fun register(name: String, seed: Int) = TokenFixtures.register(temp, name, seed)

    private fun session(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    private fun bearer(text: String): String = "Bearer $text"

    private fun resolves(token: String): Boolean = runBlocking { tokenStore.resolve(token) != null }

    private fun valueOf(body: String): String {
        val key = "\"token\":\""
        val start = body.indexOf(key) + key.length
        assertTrue("the 201 body carries a quoted token: $body", start > key.length - 1)
        return body.substring(start, start + TokenSecret.TEXT_LENGTH)
    }

    private fun enableResetPending(accountId: Long) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("UPDATE accounts SET reset_pending = 1 WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeUpdate()
            }
        }
    }

    // ---------- happy path ----------

    @Test
    fun `an admin creates an agent token and receives the value exactly once`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"agent-label","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            val body = response.bodyAsText()
            assertTrue("the body carries a token", body.contains("\"token\":\""))
            assertEquals("the token key appears exactly once", 1, body.split("\"token\"").size - 1)
            assertTrue("the body carries the name", body.contains("\"name\":\"agent-label\""))
            assertTrue("the body carries the owner name", body.contains("\"owner_username\":\"alice\""))
            assertTrue("the default scope is transcribe", body.contains("\"scope\":\"transcribe\""))
            assertTrue("a null expiry is null", body.contains("\"expires_at\":null"))
            lastBody = body
        }
        val token = valueOf(lastBody)
        assertTrue("the returned token resolves", resolves(token))
    }

    // lastBody is set by the happy-path test above; keep the token for the resolve check.
    private lateinit var lastBody: String

    @Test
    fun `the response owner_username is the canonical stored name, not the request spelling`() = runBlocking<Unit> {
        val owner = register("Alice", 1)
        val ownerSession = session(owner.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(ownerSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"ticket","owner_username":"alice","scope":"transcribe"}""")
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            lastBody = response.bodyAsText()
            assertTrue("canonical spelling wins", lastBody.contains("\"owner_username\":\"Alice\""))
            assertTrue("explicit transcribe scope", lastBody.contains("\"scope\":\"transcribe\""))
        }
        assertTrue("the issued token resolves", resolves(valueOf(lastBody)))
    }

    @Test
    fun `an owner_username in any case folds to the stored name and finds the account`() = runBlocking<Unit> {
        val alice = register("Alice", 1) // first account: admin; canonical "Alice", folded "alice"
        val aliceSession = session(alice.accountId)
        val aliceDot = register("Alice.Doe-2", 2) // canonical "Alice.Doe-2", folded "alice.doe-2"
        val aliceDotSession = session(aliceDot.accountId)
        // "Alice.Doe-2" is a non-admin (second account); the fold rule still lands on its
        // username_lower, but creating for it needs an admin caller -- use the admin Alice session.
        val adminForDotted = aliceSession

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            // Every request spelling below differs from the stored folded name by case. The fold
            // (UsernameRules.fold = lowercase(Locale.ROOT)) is what maps the request onto
            // username_lower; without it the owner lookup misses and answers 404. The response
            // still carries the canonical stored name, not the request spelling.
            for (ownerUsername in listOf("ALICE", "AlIcE", "aLiCe")) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(aliceSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"fold-$ownerUsername","owner_username":"$ownerUsername"}""")
                }
                assertEquals("status for owner_username '$ownerUsername'", HttpStatusCode.Created, response.status)
                assertTrue(
                    "canonical name wins for '$ownerUsername'",
                    response.bodyAsText().contains("\"owner_username\":\"Alice\""),
                )
            }
            // The fold rule preserves the allowed special characters (dot, dash, digit) while
            // folding case: the request spelling is upper-cased with the dots and dashes, and the
            // fold still lands on the stored username_lower.
            val dotted = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminForDotted.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"dotted","owner_username":"ALICE.DOE-2"}""")
            }
            assertEquals("status for dotted owner_username", HttpStatusCode.Created, dotted.status)
            assertTrue(
                "canonical dotted name wins",
                dotted.bodyAsText().contains("\"owner_username\":\"Alice.Doe-2\""),
            )
        }
    }

    @Test
    fun `scope admin is issued for an admin owner, case-insensitively`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            for (scope in listOf("admin", "AdMiN", "ADMIN")) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"staff-$scope","owner_username":"alice","scope":"$scope"}""")
                }
                assertEquals("scope '$scope' is created", HttpStatusCode.Created, response.status)
                assertTrue("body scope is admin", response.bodyAsText().contains("\"scope\":\"admin\""))
            }
        }
    }

    @Test
    fun `scope admin for a non-admin owner returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"bob","scope":"admin"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", """{"error":"bad_request","message":"Invalid request body"}""", response.bodyAsText())
            assertFalse("no token key in a refusal", response.bodyAsText().contains("\"token\""))
        }
    }

    @Test
    fun `expires_at in the future is echoed as ISO-8601 with three fraction digits`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"timed","owner_username":"alice","expires_at":"2026-06-15T10:15:30.250Z"}""")
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            assertTrue("expiry echoed", response.bodyAsText().contains("\"expires_at\":\"2026-06-15T10:15:30.250Z\""))
        }
    }

    @Test
    fun `expires_at absent or null means never expires`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            for (body in listOf("""{"name":"one","owner_username":"alice"}""", """{"name":"two","owner_username":"alice","expires_at":null}""")) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals("status", HttpStatusCode.Created, response.status)
                assertTrue("null expiry", response.bodyAsText().contains("\"expires_at\":null"))
            }
        }
    }

    @Test
    fun `expires_at in the past returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice","expires_at":"1970-01-01T00:00:00.001Z"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
            assertEquals("body", """{"error":"bad_request","message":"Invalid request body"}""", response.bodyAsText())
        }
    }

    @Test
    fun `expires_at too far from 1970 for a millisecond count returns 400 and stores nothing`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val before = TokenFixtures.tokenRowCount(temp)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            for (far in listOf("+292278995-01-01T00:00:00Z", "+999999999-12-31T23:59:59Z", "-999999999-01-01T00:00:00Z")) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"x","owner_username":"alice","expires_at":"$far"}""")
                }
                assertEquals("status for $far", HttpStatusCode.BadRequest, response.status)
                assertEquals(
                    "body for $far",
                    """{"error":"bad_request","message":"Invalid request body"}""",
                    response.bodyAsText(),
                )
            }
        }
        assertEquals("no token row stored", before, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `expires_at that is not an instant returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice","expires_at":"not-a-time"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---------- bad bodies ----------

    @Test
    fun `a malformed body returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"a","owner_username":"alice"""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a body missing a required field returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            for (body in listOf("{}", """{"owner_username":"alice"}""", """{"name":"a"}""")) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals("status for $body", HttpStatusCode.BadRequest, response.status)
            }
        }
    }

    @Test
    fun `a body with a null required field returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":null,"owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a body with a duplicate key returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"a","owner_username":"alice","name":"b"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a body with an unknown key returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"a","owner_username":"alice","extra":"x"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a body with a wrong-type value returns 400`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            for (body in listOf(
                """{"name":"a","owner_username":"alice","scope":1}""",
                """{"name":"a","owner_username":"alice","expires_at":1}""",
            )) {
                val response = client.post("/v1/admin/agent-tokens") {
                    header("Authorization", bearer(adminSession.secret.reveal()))
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals("status for $body", HttpStatusCode.BadRequest, response.status)
            }
        }
    }

    @Test
    fun `an empty name returns 400 through issueAgent`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---------- ownership and the caller ----------

    @Test
    fun `an unknown owner returns 404`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"no-such-account"}""")
            }
            assertEquals("status", HttpStatusCode.NotFound, response.status)
            assertEquals("body", """{"error":"not_found","message":"Account not found"}""", response.bodyAsText())
        }
    }

    @Test
    fun `no token returns 401`() = runBlocking<Unit> {
        val admin = register("alice", 1)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.Unauthorized, response.status)
            assertEquals("body", UNAUTHORIZED_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `a non-admin caller returns 403`() = runBlocking<Unit> {
        register("alice", 1) // first account: admin
        val bob = register("bob", 2) // second account: user
        val bobSession = session(bob.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(bobSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals("body", FORBIDDEN_BODY, response.bodyAsText())
        }
    }

    // ---------- caps and refsuals ----------

    @Test
    fun `a body over the 4096-byte cap returns 413`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        val tooBig = """{"name":"${"x".repeat(5000)}","owner_username":"alice"}"""

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody(tooBig)
            }
            assertEquals("status", HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                "body",
                """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `an owner waiting on a reset returns 409`() = runBlocking<Unit> {
        val owner = register("alice", 1) // first account: admin
        val ownerSession = session(owner.accountId)
        enableResetPending(owner.accountId)

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(ownerSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.Conflict, response.status)
            assertEquals(
                "body",
                """{"error":"conflict","message":"The account is waiting for a reset to be completed"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `a forced store failure returns 503`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        // Close the database: the next store call throws, and the route answers 503.
        temp.closeKeepingFiles()

        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val response = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("body", SERVICE_UNAVAILABLE_BODY, response.bodyAsText())
        }
    }

    @Test
    fun `no refusal body ever carries the token`() = runBlocking<Unit> {
        val admin = register("alice", 1)
        val adminSession = session(admin.accountId)
        testApplication {
            application { adminCreateAgentTokenRoute(tokenStore) }
            val created = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"leak-me","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.Created, created.status)
            val secret = valueOf(created.bodyAsText())

            val refused = client.post("/v1/admin/agent-tokens") {
                header("Authorization", bearer(adminSession.secret.reveal()))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"","owner_username":"alice"}""")
            }
            assertEquals("status", HttpStatusCode.BadRequest, refused.status)
            val body = refused.bodyAsText()
            assertFalse("the token value is not in the body", body.contains(secret))
            assertFalse("the token key is not in the body", body.contains("\"token\""))
        }
    }
}
