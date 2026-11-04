package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.IssueResult
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A store that places a reset in the gap between the verify and the session insert, with no
 * concurrency at all: right before it makes the session of the victim account it marks that account pending in
 * a transaction of its own, which commits first, and then runs the real issue.
 */
private class GapTokenStore(private val temp: TempDatabase, clock: Clock, private val victim: Long) :
    TokenStore(temp.db, clock) {

    override suspend fun issueSession(accountId: Long): IssueResult {
        if (accountId == victim) {
            temp.db.transaction<Int> { connection ->
                connection.prepareStatement("UPDATE accounts SET reset_pending = 1 WHERE id = ?").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeUpdate()
                }
            }
        }
        return super.issueSession(accountId)
    }
}

/** The login route answers a reset that came after the verify as it answers a wrong verifier. */
internal class AuthLoginRaceGapTest : TempDatabaseTest() {

    private class Answer(val status: HttpStatusCode, val body: String)

    private val wrongVerifierBody = """{"error":"unauthorized","message":"Invalid username or verifier"}"""

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var accounts: AccountStore

    // The first account is the admin, the second is a user. The second one is the victim of the gap.
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        accounts = AccountFixtures.newStore(temp, clock = clock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun login(store: TokenStore, name: String, seed: Int): Answer {
        val verifierText = Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(seed))
        var answer: Answer? = null
        runBlocking {
            testApplication {
                application { authLoginRoute(accounts, store) }
                val response = client.post("/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"$name","auth_verifier":"$verifierText"}""")
                }
                answer = Answer(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(answer)
    }

    private fun rowsOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM tokens WHERE account_id = $accountId").single()
        }

    private fun pendingOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT reset_pending FROM accounts WHERE id = $accountId").single()
        }

    @Test
    fun `a login whose account is reset after the verify gives the wrong verifier 401 and stores no session`() {
        val gap = GapTokenStore(temp, clock, bob)
        val reference = login(gap, "alice", 99)
        assertEquals("sync-api: status of a wrong verifier", HttpStatusCode.Unauthorized, reference.status)
        assertEquals("sync-api: body of a wrong verifier", wrongVerifierBody, reference.body)
        assertEquals("sync-api: the victim is not pending before the login", 0L, pendingOf(bob))
        val reply = login(gap, "bob", 2)
        assertEquals("sync-api: the gap must have marked the victim", 1L, pendingOf(bob))
        assertEquals("sync-api: status after the gap", HttpStatusCode.Unauthorized, reply.status)
        assertArrayEquals(
            "sync-api: the body must be the wrong verifier body byte for byte",
            reference.body.toByteArray(StandardCharsets.UTF_8),
            reply.body.toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals("sync-api: no session row for the victim", 0L, rowsOf(bob))
    }

    @Test
    fun `a login whose account is reset after the verify counts no wrong try`() {
        val gap = GapTokenStore(temp, clock, bob)
        assertNull("sync-api: no wrong try row before the login", runBlocking { accounts.attempts.attemptOf("bob") })
        val reply = login(gap, "bob", 2)
        assertEquals("sync-api: status after the gap", HttpStatusCode.Unauthorized, reply.status)
        assertEquals("sync-api: the gap must have marked the victim", 1L, pendingOf(bob))
        assertNull("sync-api: a wrong try was counted", runBlocking { accounts.attempts.attemptOf("bob") })
    }

    @Test
    fun `the same login through a plain store gives 200 and one session`() {
        val plain = TokenFixtures.tokenStore(temp, clock)
        val reply = login(plain, "bob", 2)
        assertEquals("sync-api: status of the control login", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: the control must not be pending", 0L, pendingOf(bob))
        assertEquals("sync-api: one session row for the control", 1L, rowsOf(bob))
    }

    @Test
    fun `the gap store leaves the login of another account alone`() {
        val gap = GapTokenStore(temp, clock, bob)
        val reply = login(gap, "alice", 1)
        assertEquals("sync-api: status of the other account", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: one session row for the other account", 1L, rowsOf(alice))
        assertEquals("sync-api: the other account is not pending", 0L, pendingOf(alice))
        assertEquals("sync-api: the victim is not touched by this login", 0L, pendingOf(bob))
    }
}
