package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.StoredVerifier
import dev.breaker.server.syncapi.accounts.VerifierHasher
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
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A store failure in the register route's pre-check answers the fixed 503 once, and no hash and no second store call follow it. */
internal class AuthRegisterPreCheckFailureTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun setUp() {
        temp = openTemp()
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun registerBody(): String =
        """{"username":"alice","salt":"${b64(ByteArray(16) { 1 })}","kdf_params":{"memory_kib":65536,"iterations":3,"parallelism":1},"kdf_version":1,"auth_verifier":"${b64(ByteArray(32) { 2 })}"}"""

    @Test
    fun `a failing pre-check answers 503 once and runs no hash`() {
        val hashes = AtomicInteger(0)
        val countingHasher = object : VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS) {
            override fun hashNew(verifier: AuthVerifier): StoredVerifier {
                hashes.incrementAndGet()
                return super.hashNew(verifier)
            }
        }
        val store = AccountStore(temp.db, countingHasher, AccountFixtures.fixedClock)
        temp.closeKeepingFiles()
        runBlocking<Unit> {
            testApplication {
                application { routing { authRegisterRoute(store) } }
                val response = client.post("/v1/auth/register") {
                    setBody(registerBody())
                    contentType(ContentType.Application.Json)
                }
                assertEquals("status", HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("body", UNAVAILABLE_BODY, response.bodyAsText())
            }
        }
        assertEquals("sync-api: no hash ran after the failed pre-check", 0, hashes.get())
    }
}
