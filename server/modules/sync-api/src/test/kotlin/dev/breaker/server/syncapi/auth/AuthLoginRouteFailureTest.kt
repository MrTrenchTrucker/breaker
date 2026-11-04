package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AuthLoginRouteFailureTest : AuthKeysRouteTestBase() {

    private val wholeSecondMs = KeyPatterns.REKEYED_AT_MS
    private val millisMs = KeyPatterns.RESET_AT_MS
    private val sevenMs = 1_700_000_000_007L

    private fun login(): Reply =
        postTo("/v1/auth/login", null, """{"username":"alice","auth_verifier":"${KeyPatterns.text(AccountFixtures.verifierBytes(1))}"}""")

    /** The raw JSON text of one top-level member: a quoted string, null or a number. */
    private fun memberOf(body: String, name: String): String {
        val marker = "\"$name\":"
        val start = body.indexOf(marker)
        check(start >= 0) { "sync-api test helper: member $name is missing from the body" }
        val from = start + marker.length
        var end = from
        while (end < body.length && body[end] != ',' && body[end] != '}') end++
        return body.substring(from, end)
    }

    private fun assertUnavailable(label: String) {
        val reply = login()
        assertEquals("sync-api: $label status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: $label body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
        assertEquals("sync-api: $label left a token row", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `a failure in verify gets 503 with the exact body and no token row and login works again after the fault is removed`() {
        register("alice", 1)
        runSql(temp, "ALTER TABLE accounts RENAME COLUMN verifier_hash TO verifier_hash_x")
        assertUnavailable("a failure in verify")
        runSql(temp, "ALTER TABLE accounts RENAME COLUMN verifier_hash_x TO verifier_hash")
        assertEquals("sync-api: login after the fault is removed", HttpStatusCode.OK, login().status)
    }

    @Test
    fun `a failure in keysFor gets 503 with the exact body and no token row and login works again after the fault is removed`() {
        register("alice", 1)
        runSql(temp, "ALTER TABLE accounts RENAME COLUMN rekeyed_at TO rekeyed_at_x")
        assertUnavailable("a failure in keysFor")
        runSql(temp, "ALTER TABLE accounts RENAME COLUMN rekeyed_at_x TO rekeyed_at")
        assertEquals("sync-api: login after the fault is removed", HttpStatusCode.OK, login().status)
    }

    @Test
    fun `a failure in issueSession gets 503 with the exact body and no token row and login works again after the fault is removed`() {
        register("alice", 1)
        runSql(
            temp,
            "CREATE TRIGGER forced_issue_failure BEFORE INSERT ON tokens BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )
        assertUnavailable("a failure in issueSession")
        runSql(temp, "DROP TRIGGER forced_issue_failure")
        assertEquals("sync-api: login after the fault is removed", HttpStatusCode.OK, login().status)
    }

    @Test
    fun `login prints a whole second as three fractional digits and milliseconds as three digits`() {
        val id = register("alice", 1)
        runSql(temp, "UPDATE accounts SET rekeyed_at = $wholeSecondMs, reset_at = $millisMs WHERE id = $id")
        val first = login()
        assertEquals("sync-api: login status", HttpStatusCode.OK, first.status)
        assertEquals("sync-api: rekeyed_at of a whole second", "\"2023-11-14T22:13:20.000Z\"", memberOf(first.body, "rekeyed_at"))
        assertEquals("sync-api: reset_at with milliseconds", "\"2023-11-14T22:13:20.123Z\"", memberOf(first.body, "reset_at"))
        runSql(temp, "UPDATE accounts SET rekeyed_at = $sevenMs WHERE id = $id")
        val second = login()
        assertEquals("sync-api: rekeyed_at with seven milliseconds", "\"2023-11-14T22:13:20.007Z\"", memberOf(second.body, "rekeyed_at"))
    }
}
