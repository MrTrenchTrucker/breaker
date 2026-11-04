package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before

/**
 * The shared set-up of the reset tests: every store runs on one MutableClock, so a code lifetime and a
 * lock are moved by hand. The first account registered is the admin (id 1), so a test calls startAdmin first.
 */
internal abstract class ResetRouteTestBase : AuthChangePasswordRouteTestBase() {

    protected lateinit var clock: MutableClock

    @Before
    fun setUpResetClock() {
        clock = MutableClock(TokenFixtures.START)
        accountStore = AccountFixtures.newStore(temp, AccountFixtures.fastHasher(), clock)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        handoffStore = HandoffFixtures.handoffStore(temp, tokenStore, clock)
    }

    protected val adminId = 1L
    protected val resetPath = "/v1/admin/reset-account"
    protected val completePath = "/v1/auth/complete-reset"
    protected val saltPath = "/v1/auth/salt"
    protected val adminUnauthorizedBody = """{"error":"unauthorized","message":"Invalid or missing token"}"""
    protected val adminForbiddenBody = """{"error":"forbidden","message":"Admin role required"}"""
    protected val notFoundBody = """{"error":"not_found","message":"Account not found"}"""
    protected val refusedBody = """{"error":"unauthorized","message":"Invalid username or reset code"}"""
    protected val pendingBody = """{"error":"conflict","message":"The account is waiting for a reset to be completed"}"""

    /** Registers the first account, which is the admin, and returns a live session text for it. */
    protected fun startAdmin(): String = sessionToken(register("root", 1))

    protected fun adminAgentText(accountId: Long): String =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueAgent(accountId, AgentScope.ADMIN, "agent-label", null) })
            .secret.reveal()

    /**
     * A live session for an account that waits for a reset, made through the issuing function that does not read
     * the flag. It stands for a session that came into being in a race with the reset; the session issue that
     * reads the flag refuses such an account.
     */
    protected fun sessionOnPendingAccount(accountId: Long): String = runBlocking {
        temp.db.transaction<String> { connection ->
            tokenStore.issueSessionOn(connection, accountId, null, clock.millis()).secret.reveal()
        }
    }

    protected fun nameBody(name: String): String = """{"username":"$name"}"""

    protected fun postReset(bearer: String?, body: String): Reply = postTo(resetPath, bearer, body)

    /** Checks the whole answer of a successful reset and returns the 43 character code. */
    protected fun codeOf(reply: Reply): String {
        val prefix = "{\"reset_code\":\""
        assertEquals("sync-api: reset status", HttpStatusCode.OK, reply.status)
        assertTrue("sync-api: reset body shape: ${reply.body.length}", reply.body.startsWith(prefix) && reply.body.endsWith("\"}"))
        assertEquals("sync-api: reset body length", prefix.length + 43 + 2, reply.body.length)
        return reply.body.substring(prefix.length, prefix.length + 43)
    }

    protected fun resetAs(adminText: String, name: String): String = codeOf(postReset(adminText, nameBody(name)))

    /** Registers [name] with the verifier of [seed], resets it in capitals as the admin, and returns the code. */
    protected fun pendingAccount(adminText: String, name: String, seed: Int): String {
        register(name, seed)
        return resetAs(adminText, name.uppercase())
    }

    /** The six members of a valid complete-reset body, in order, as name and raw JSON value. */
    protected fun completeMembers(name: String, code: String): List<Pair<String, String>> = listOf(
        "username" to quoted(name),
        "reset_code" to quoted(code),
        "salt" to quoted(textOf(newSaltBytes)),
        "kdf_params" to kdfRaw(newKdf.memoryKib, newKdf.iterations, newKdf.parallelism),
        "kdf_version" to "1",
        "auth_verifier" to quoted(textOf(newVerifierBytes)),
    )

    protected fun completeBody(name: String, code: String): String = objectOf(completeMembers(name, code))

    protected fun completeWith(name: String, code: String, member: String, raw: String): String =
        objectOf(completeMembers(name, code).map { (key, value) -> key to (if (key == member) raw else value) })

    protected fun completeWithout(name: String, code: String, member: String): String =
        objectOf(completeMembers(name, code).filter { (key, _) -> key != member })

    protected fun postComplete(name: String, code: String): Reply = postTo(completePath, null, completeBody(name, code))

    /** Sends the Authorization header exactly as given, or none when it is null. */
    protected fun postCompleteWithAuthorization(authorization: String?, body: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(completePath) {
                    if (authorization != null) header(HttpHeaders.Authorization, authorization)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** The raw JSON text of one top-level member of a login body. */
    protected fun rawMember(body: String, name: String): String {
        val marker = "\"$name\":"
        val start = body.indexOf(marker)
        check(start >= 0) { "sync-api test helper: member $name is missing from the body" }
        val from = start + marker.length
        var end = from
        while (end < body.length && body[end] != ',' && body[end] != '}') end++
        return body.substring(from, end)
    }

    protected fun sessionTextOf(body: String): String = rawMember(body, "token").trim('"')

    // [sql] is always a literal from the calling test, never input.
    private fun countOf(sql: String): Long = runBlocking {
        temp.db.transaction { connection -> TestDatabases.longs(connection, sql).single() }
    }

    protected fun pendingFlag(accountId: Long): Long = countOf("SELECT reset_pending FROM accounts WHERE id = $accountId")

    protected fun codeRows(accountId: Long): Long = countOf("SELECT count(*) FROM reset_codes WHERE account_id = $accountId")

    protected fun transcriptionRows(accountId: Long): Long =
        countOf("SELECT count(*) FROM transcriptions WHERE account_id = $accountId")

    protected fun expiryOf(tokenId: Long): Long = countOf("SELECT expires_at_ms FROM tokens WHERE id = $tokenId")

    /** The wrong-password row of a folded name as one text, or none. */
    protected fun attemptsOf(foldedName: String): String {
        val info = runBlocking { accountStore.attempts.attemptOf(foldedName) }
        return if (info == null) "none" else "${info.wrongCount}/${info.lockedUntilMs}/${info.lastAttemptMs}"
    }

    /** Credentials, keys, tokens, the pending mark and the code rows of one account as one text. */
    protected fun stateOf(accountId: Long): String =
        snapshotOf(accountId) + "/" + pendingFlag(accountId) + "/" + codeRows(accountId)
}
