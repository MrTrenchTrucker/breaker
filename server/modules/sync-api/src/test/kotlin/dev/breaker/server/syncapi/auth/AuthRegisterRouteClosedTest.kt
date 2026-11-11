package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the registration-closed rule: once an account exists, `POST /v1/auth/register`
 * answers 403 `{"error":"registration_closed","message":"Registration is closed."}`
 * unless the `registration_open` switch is on; zero accounts always allows the first
 * registration (it becomes admin). No PBKDF2 hash runs when closed (asserted through a
 * counting hasher), and two concurrent registers on an empty server yield exactly one
 * admin and one 403.
 */
internal class AuthRegisterRouteClosedTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setup() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    // -- helpers -----------------------------------------------------------

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun validSalt(): String = b64(ByteArray(16) { 1 })

    private fun validVerifier(): String = b64(ByteArray(32) { 2 })

    private fun registerBody(
        username: String = "alice",
        salt: String = validSalt(),
        memoryKib: Int = 65536,
        iterations: Int = 3,
        parallelism: Int = 1,
        kdfVersion: Int = 1,
        verifier: String = validVerifier(),
    ): String = """{"username":"$username","salt":"$salt","kdf_params":{"memory_kib":$memoryKib,"iterations":$iterations,"parallelism":$parallelism},"kdf_version":$kdfVersion,"auth_verifier":"$verifier"}"""

    /** Runs the block against an application with only the register route installed. */
    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application { routing { authRegisterRoute(store) } }
            block()
        }
    }

    // -- zero accounts: first registration always allowed ------------------

    @Test
    fun `first registration on an empty server returns 201 and admin`() = runBlocking {
        withApp {
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            assertEquals("body", """{"username":"alice","role":"admin"}""", response.bodyAsText())
        }
    }

    // -- once an account exists: closed unless open -----------------------

    @Test
    fun `second registration with the switch off returns 403 closed`() = runBlocking {
        withApp {
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals(
                "body",
                """{"error":"registration_closed","message":"Registration is closed."}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `second registration with the switch on returns 201`() = runBlocking {
        withApp {
            runBlocking { store.settings.setRegistrationOpenEnabled(true) }
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Created, response.status)
            assertEquals("body", """{"username":"bob","role":"user"}""", response.bodyAsText())
        }
    }

    @Test
    fun `switch off again returns 403 closed`() = runBlocking {
        withApp {
            runBlocking { store.settings.setRegistrationOpenEnabled(true) }
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            runBlocking { store.settings.setRegistrationOpenEnabled(false) }
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Forbidden, response.status)
            assertEquals(
                "body",
                """{"error":"registration_closed","message":"Registration is closed."}""",
                response.bodyAsText(),
            )
        }
    }

    // -- closed answer before username-taken -----------------------------

    @Test
    fun `closed server does not reveal whether a username exists`() = runBlocking {
        withApp {
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            // bob does not exist; a taken-username server would answer 409 here, but
            // closed comes first so both existing and unknown names get 403.
            val existing = client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Forbidden, existing.status)
            val unknown = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("status", HttpStatusCode.Forbidden, unknown.status)
        }
    }

    // -- no hash runs when closed ---------------------------------------

    @Test
    fun `a closed registration runs no PBKDF2 hash`() = runBlocking {
        val hashes = AtomicInteger(0)
        val countingHasher = object : dev.breaker.server.syncapi.accounts.VerifierHasher(
            iterations = AccountFixtures.FAST_ITERATIONS,
        ) {
            override fun hashNew(verifier: dev.breaker.server.syncapi.accounts.AuthVerifier): dev.breaker.server.syncapi.accounts.StoredVerifier {
                hashes.incrementAndGet()
                return super.hashNew(verifier)
            }
        }
        val countingStore = dev.breaker.server.syncapi.accounts.AccountStore(
            temp.db,
            countingHasher,
            dev.breaker.server.syncapi.accounts.AccountFixtures.fixedClock,
        )
        testApplication {
            application { routing { authRegisterRoute(countingStore) } }
            client.post("/v1/auth/register") {
                setBody(registerBody("alice"))
                contentType(ContentType.Application.Json)
            }
            val before = hashes.get()
            assertEquals("the first registration hashed once", 1, before)
            val response = client.post("/v1/auth/register") {
                setBody(registerBody("bob"))
                contentType(ContentType.Application.Json)
            }
            assertEquals("closed status", HttpStatusCode.Forbidden, response.status)
            assertEquals("no new hash", before, hashes.get())
        }
    }

    // -- race: two registers on an empty server ------------------------

    @Test
    fun `two concurrent registers on an empty server yield exactly one admin and one 403`() = runBlocking {
        withApp {
            // Both requests pass the cheap pre-check (no account yet) and hash; the insert
            // transactions serialize on the single database lane, so exactly one sees zero
            // accounts (admin) and the other sees one account with the switch off (closed).
            val responses = coroutineScope {
                listOf("alice", "bob").map { name ->
                    async {
                        client.post("/v1/auth/register") {
                            setBody(registerBody(name))
                            contentType(ContentType.Application.Json)
                        }
                    }
                }.awaitAll()
            }
            val statuses = responses.map { it.status }
            assertTrue("exactly one 201, got $statuses", statuses.count { it == HttpStatusCode.Created } == 1)
            assertTrue("exactly one 403, got $statuses", statuses.count { it == HttpStatusCode.Forbidden } == 1)
            val winner = responses.first { it.status == HttpStatusCode.Created }
            assertTrue("the winner is admin", winner.bodyAsText().contains("\"role\":\"admin\""))
        }
    }

    // -- the cheap pre-check the route runs before the hash ----------------

    @Test
    fun `registrationClosedNow is false with no account and true with an account until the switch is on`() {
        assertEquals("no account", false, runBlocking { store.registrationClosedNow() })
        runBlocking {
            store.register(
                "alice",
                AccountFixtures.salt(1),
                dev.breaker.server.syncapi.accounts.KdfParams(65536, 3, 1),
                1,
                AccountFixtures.verifier(1),
            )
        }
        assertEquals("one account, switch absent", true, runBlocking { store.registrationClosedNow() })
        runBlocking { store.settings.setRegistrationOpenEnabled(true) }
        assertEquals("switch on", false, runBlocking { store.registrationClosedNow() })
        runBlocking { store.settings.setRegistrationOpenEnabled(false) }
        assertEquals("switch explicitly off", true, runBlocking { store.registrationClosedNow() })
    }
}
