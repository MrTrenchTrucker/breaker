package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.RegisterResult
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before

/** The shared set-up of the hand-off route tests: a temp database, the stores and one-request helpers. */
internal abstract class HandoffRouteTestBase {

    protected lateinit var temp: TempDatabase
    protected lateinit var accountStore: AccountStore
    protected lateinit var tokenStore: TokenStore
    protected lateinit var handoffStore: HandoffStore
    protected lateinit var transcriptionStore: TranscriptionStore

    @Before
    fun setUpStores() {
        temp = TestDatabases.openTemp()
        accountStore = AccountFixtures.newStore(temp, AccountFixtures.fastHasher())
        tokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        transcriptionStore = SyncRouteTestSupport.newStore(temp)
    }

    @After
    fun tearDownStores() {
        temp.close()
    }

    protected val sealedPackage: String = HandoffFixtures.SEALED_PACKAGE_TEXT
    protected val challenge: String = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    protected val secret: String = HandoffFixtures.SECRET
    protected val badRequestBody = """{"error":"bad_request","message":"Invalid request body"}"""
    protected val sessionRequiredBody = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    protected val noName = "\u0001null"

    /** The first registered account is the ADMIN. */
    protected fun register(name: String, seed: Int): Long {
        val result = runBlocking {
            accountStore.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }
        if (result !is RegisterResult.Registered) {
            throw AssertionError("expected Registered but got $result")
        }
        return result.accountId
    }

    protected fun sessionToken(accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) }).secret.reveal()

    /** Starts a code with the given package text; returns the code text. */
    protected fun codeFor(token: String, packageText: String): String {
        var code: String? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post("/v1/auth/handoff/start") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"challenge":"$challenge","sealed_package":"$packageText"}""")
                }
                assertEquals("start status", HttpStatusCode.Created, response.status)
                val body = response.bodyAsText()
                val start = body.indexOf("\"code\":\"") + 8
                code = body.substring(start, start + 43)
            }
        }
        return checkNotNull(code)
    }

    protected fun postTo(path: String, bearer: String?, body: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(path) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    protected fun exch(body: String): Reply = postTo("/v1/auth/handoff/exchange", null, body)

    protected fun getList(bearer: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.get("/v1/transcriptions") { header(HttpHeaders.Authorization, "Bearer $bearer") }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** The device name of the newest session row, or [noName] when the column is empty. */
    protected fun lastSessionDeviceName(): String = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT device_name FROM tokens WHERE kind = 'session' ORDER BY id DESC LIMIT 1").use { s ->
                s.executeQuery().use { rows -> if (rows.next()) (rows.getString(1) ?: noName) else "no row" }
            }
        }
    }
}
