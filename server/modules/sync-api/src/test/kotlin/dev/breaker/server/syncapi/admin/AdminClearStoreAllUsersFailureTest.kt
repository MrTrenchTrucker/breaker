package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.Reply
import dev.breaker.server.syncapi.auth.ResetRouteTestBase
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The manager's extra test: all-users clear, account 2 fails, account 1 stays cleared,
 * the exact 503 body asserted, account 2's rows untouched.
 *
 * A SQLite TRIGGER created through the temp database raises on any UPDATE of transcriptions
 * for account 2 only. Accounts are cleared in id order: account 1 is cleared first
 * (committed in its own transaction), then account 2's clear throws, the route answers 503.
 */
internal class AdminClearStoreAllUsersFailureTest : ResetRouteTestBase() {

    private val clearPath = "/v1/admin/clear-store"

    // 36 characters: 8+1+4+1+4+1+4+1+12
    private fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun postClear(bearer: String?, body: String): Reply = postTo(clearPath, bearer, body)

    private fun createTriggerForAccount(accountId: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "CREATE TRIGGER fail_clear_for_$accountId AFTER UPDATE ON transcriptions " +
                        "WHEN NEW.account_id = $accountId BEGIN SELECT RAISE(ABORT, 'forced clear failure'); END",
                ).use { statement ->
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun countLive(accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 0")
                    .use { statement ->
                        statement.setLong(1, accountId)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no count" }
                            rows.getLong(1)
                        }
                    }
            }
        }

    private fun countDeleted(accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1")
                    .use { statement ->
                        statement.setLong(1, accountId)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no count" }
                            rows.getLong(1)
                        }
                    }
            }
        }

    @Test
    fun `account 2 fails and account 1 stays cleared with the exact 503 body`() {
        val adminText = startAdmin() // root = id 1 (admin)
        val alice = register("alice", 2) // id 2
        val bob = register("bob", 3) // id 3
        runBlocking {
            transcriptionStore.push(adminId, id(1), 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(alice, id(2), 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(bob, id(3), 0, SyncRouteTestSupport.ENCRYPTED)
        }

        assertEquals("admin live before", 1L, countLive(adminId))
        assertEquals("alice live before", 1L, countLive(alice))
        assertEquals("bob live before", 1L, countLive(bob))

        // Account 2 fails on any transcription UPDATE.
        createTriggerForAccount(alice)

        val reply = postClear(adminText, """{"username":null,"confirm":true}""")

        assertEquals("all-users status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals(
            "all-users body",
            """{"error":"unavailable","message":"The service is unavailable, try again later"}""",
            reply.body,
        )

        // Account 1 cleared (committed before account 2 failed).
        assertEquals("admin live after", 0L, countLive(adminId))
        assertEquals("admin deleted after", 1L, countDeleted(adminId))

        // Account 2 untouched (its transaction rolled back).
        assertEquals("alice live after", 1L, countLive(alice))
        assertEquals("alice deleted after", 0L, countDeleted(alice))

        // Account 3 untouched too (route stopped at account 2).
        assertEquals("bob live after", 1L, countLive(bob))
        assertEquals("bob deleted after", 0L, countDeleted(bob))
    }

    @Test
    fun `confirm absent gets 400 even for all users`() {
        val adminText = startAdmin()
        val reply = postClear(adminText, """{"username":"alice"}""")
        assertEquals("confirm absent status", HttpStatusCode.BadRequest, reply.status)
        assertEquals(
            "confirm absent body",
            """{"error":"bad_request","message":"confirm must be true"}""",
            reply.body,
        )
    }
}
