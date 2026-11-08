package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.SaltLookup
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAVAILABLE_BODY
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** A failing account store answers the fixed 503 on the salt and register routes, never a 500. */
internal class AuthAccountStoreFailureTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    private companion object {
        const val FAIL_INSERT_SQL =
            "CREATE TRIGGER forced_account_failure BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT, 'forced'); END"
        const val DROP_TRIGGER_SQL = "DROP TRIGGER forced_account_failure"
    }

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun registerBody(username: String): String =
        "{\"username\":\"" + username + "\",\"salt\":\"" + b64(AccountFixtures.salt(1)) + "\"," +
            "\"kdf_params\":{\"memory_kib\":65536,\"iterations\":3,\"parallelism\":1},\"kdf_version\":1," +
            "\"auth_verifier\":\"" + b64(AccountFixtures.verifierBytes(1)) + "\"}"

    private fun post(path: String, requestBody: String): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application {
                authSaltRoute(store)
                routing { authRegisterRoute(store) }
            }
            val response = client.post(path) {
                setBody(requestBody)
                contentType(ContentType.Application.Json)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    private fun runSql(sql: String) {
        inTransaction(temp) { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    @Test
    fun `a salt lookup that fails in the store answers the fixed 503`() {
        runBlocking {
            store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1))
        }
        assertEquals(HttpStatusCode.OK, post("/v1/auth/salt", "{\"username\":\"alice\"}").first)
        temp.db.close()
        val reply = post("/v1/auth/salt", "{\"username\":\"alice\"}")
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }

    @Test
    fun `a registration on a closed database answers the fixed 503`() {
        assertEquals(HttpStatusCode.Created, post("/v1/auth/register", registerBody("bob")).first)
        temp.db.close()
        val reply = post("/v1/auth/register", registerBody("alice"))
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
    }

    @Test
    fun `a registration that fails inside the insert answers the fixed 503 and leaves no account`() {
        runSql(FAIL_INSERT_SQL)
        val reply = post("/v1/auth/register", registerBody("alice"))
        assertEquals(HttpStatusCode.ServiceUnavailable, reply.first)
        assertEquals(UNAVAILABLE_BODY, reply.second)
        assertEquals(SaltLookup.NoSuchAccount, runBlocking { store.saltFor("alice") })
        runSql(DROP_TRIGGER_SQL)
        assertEquals(HttpStatusCode.Created, post("/v1/auth/register", registerBody("alice")).first)
    }
}
