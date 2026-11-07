package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.Reply
import dev.breaker.server.syncapi.auth.ResetRouteTestBase
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AdminClearStoreRouteTest : ResetRouteTestBase() {

    private val clearPath = "/v1/admin/clear-store"

    // 36 characters: 8+1+4+1+4+1+4+1+12
    private fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun clearBody(username: String?, confirm: Boolean): String =
        buildString {
            append("{")
            if (username != null) append("\"username\":\"$username\",")
            append("\"confirm\":$confirm")
            append("}")
        }

    private fun postClear(bearer: String?, body: String) = postTo(clearPath, bearer, body)

    private fun assertCleared(label: String, reply: Reply, status: HttpStatusCode, body: String) {
        assertEquals("sync-api: $label status", status, reply.status)
        assertEquals("sync-api: $label body", body, reply.body)
    }

    private fun changeSeqOf(accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "no account $accountId" }
                        rows.getLong(1)
                    }
                }
            }
        }

    @Test
    fun `no token and an unknown token get 401 with the admin body`() {
        startAdmin()
        assertCleared(
            "no token",
            postClear(null, clearBody("alice", true)),
            HttpStatusCode.Unauthorized,
            """{"error":"unauthorized","message":"Invalid or missing token"}""",
        )
        assertCleared(
            "unknown token",
            postClear(SyncRouteTestSupport.UNKNOWN_TOKEN, clearBody("alice", true)),
            HttpStatusCode.Unauthorized,
            """{"error":"unauthorized","message":"Invalid or missing token"}""",
        )
    }

    @Test
    fun `a user session gets 403 and changes nothing`() {
        startAdmin()
        val bobText = sessionToken(register("Bob", 3))
        assertCleared(
            "user session",
            postClear(bobText, clearBody("alice", true)),
            HttpStatusCode.Forbidden,
            """{"error":"forbidden","message":"Admin role required"}""",
        )
    }

    @Test
    fun `a transcribe agent token of the admin gets 403 and changes nothing`() {
        startAdmin()
        val agent = SyncRouteTestSupport.agentToken(tokenStore, adminId)
        assertCleared(
            "transcribe agent",
            postClear(agent, clearBody("alice", true)),
            HttpStatusCode.Forbidden,
            """{"error":"forbidden","message":"Admin role required"}""",
        )
    }

    @Test
    fun `an admin session clears one user and answers the count`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(alice, SyncRouteTestSupport.OTHER_CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val reply = postClear(adminText, clearBody("alice", true))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", """{"cleared":2}""", reply.body)
        assertEquals("live rows zero", 0L, countLive(alice))
        assertEquals("deleted rows two", 2L, deletedTranscriptionRows(alice))
    }

    @Test
    fun `an admin scoped agent token clears one user`() {
        startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(alice, SyncRouteTestSupport.OTHER_CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val reply = postClear(adminAgentText(adminId), clearBody("alice", true))
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", """{"cleared":2}""", reply.body)
        assertEquals("live rows zero", 0L, countLive(alice))
        assertEquals("deleted rows two", 2L, deletedTranscriptionRows(alice))
    }

    @Test
    fun `an unknown name gets 404 with the exact body`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        assertCleared(
            "unknown name",
            postClear(adminText, clearBody("Nobody", true)),
            HttpStatusCode.NotFound,
            """{"error":"not_found","message":"Account not found"}""",
        )
        assertEquals("sync-api: unknown name left Alice", 0L, pendingFlag(alice))
    }

    @Test
    fun `a name with a space or 65 characters or no characters gets the same 404`() {
        val adminText = startAdmin()
        for (name in listOf("a b", "x".repeat(65), "")) {
            assertCleared(
                "name '$name'",
                postClear(adminText, clearBody(name, true)),
                HttpStatusCode.NotFound,
                """{"error":"not_found","message":"Account not found"}""",
            )
        }
        assertEquals("sync-api: the admin was not cleared", 0L, pendingFlag(adminId))
    }

    @Test
    fun `confirm false gets 400 with the contract body`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val before = transcriptionRows(alice)
        assertCleared(
            "confirm false",
            postClear(adminText, clearBody("alice", false)),
            HttpStatusCode.BadRequest,
            """{"error":"bad_request","message":"confirm must be true"}""",
        )
        assertEquals("unchanged", before, transcriptionRows(alice))
    }

    @Test
    fun `confirm absent gets 400`() {
        val adminText = startAdmin()
        register("Alice", 2)
        assertCleared(
            "confirm absent",
            postClear(adminText, "{\"username\":\"alice\"}"),
            HttpStatusCode.BadRequest,
            """{"error":"bad_request","message":"confirm must be true"}""",
        )
    }

    @Test
    fun `confirm as the string true gets 400`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val before = transcriptionRows(alice)
        assertCleared(
            "confirm string true",
            postClear(adminText, "{\"username\":\"alice\",\"confirm\":\"true\"}"),
            HttpStatusCode.BadRequest,
            """{"error":"bad_request","message":"The body is not exactly one JSON object with the members username and confirm"}""",
        )
        assertEquals("unchanged", before, transcriptionRows(alice))
    }

    @Test
    fun `username null with confirm true clears every user`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val bob = register("Bob", 3)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(alice, SyncRouteTestSupport.OTHER_CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
            transcriptionStore.push(bob, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val reply = postClear(adminText, """{"username":null,"confirm":true}""")
        assertEquals("status", HttpStatusCode.OK, reply.status)
        assertEquals("body", """{"cleared":3}""", reply.body)
        assertEquals("alice live rows zero", 0L, countLive(alice))
        assertEquals("bob live rows zero", 0L, countLive(bob))
    }

    @Test
    fun `named user store failure answers 503 and leaves rows untouched`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, id(1), 0, "alice-data")
        }
        assertEquals("alice live before", 1L, countLive(alice))
        createTriggerForAccount(alice)
        val reply = postClear(adminText, clearBody("alice", true))
        assertEquals("named user status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals(
            "named user body",
            """{"error":"unavailable","message":"The service is unavailable, try again later"}""",
            reply.body,
        )
        assertEquals("alice live after", 1L, countLive(alice))
        assertEquals("alice deleted after", 0L, countDeleted(alice))
    }

    @Test
    fun `the delete records are visible through the feed after the clear`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val tokenText = SyncRouteTestSupport.sessionToken(tokenStore, alice)
        val headBefore = changeSeqOf(alice)
        postClear(adminText, clearBody("alice", true))
        val after = SyncRouteTestSupport.getPage(tokenStore, transcriptionStore, tokenText, "$alice-$headBefore")
        assertTrue("feed shows deleted true", after.body.contains("\"deleted\":true"))
        assertTrue("feed carries client id", after.body.contains(SyncRouteTestSupport.CLIENT_ID))
    }

    @Test
    fun `a session that pulled before sees deleted true after`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        runBlocking {
            transcriptionStore.push(alice, SyncRouteTestSupport.CLIENT_ID, 0, SyncRouteTestSupport.ENCRYPTED)
        }
        val tokenText = SyncRouteTestSupport.sessionToken(tokenStore, alice)
        val headBefore = changeSeqOf(alice)
        SyncRouteTestSupport.getPage(tokenStore, transcriptionStore, tokenText, "$alice-$headBefore")
        postClear(adminText, clearBody("alice", true))
        val after = SyncRouteTestSupport.getPage(tokenStore, transcriptionStore, tokenText, "$alice-$headBefore")
        assertTrue("after shows deleted true", after.body.contains("\"deleted\":true"))
    }

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

    private fun deletedTranscriptionRows(accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1").use { statement ->
                    statement.setLong(1, accountId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "no count" }
                        rows.getLong(1)
                    }
                }
            }
        }
}
