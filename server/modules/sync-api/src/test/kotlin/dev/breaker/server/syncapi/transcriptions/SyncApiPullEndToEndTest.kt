package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.GONE_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_ENCRYPTED
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

// The public module with real sign-ins: alice (three sessions) and bob (one) sign in through the login
// route, and every push, delete, pull and sign-out goes through the module. SQL only reads values.
internal class SyncApiPullEndToEndTest : TempDatabaseTest() {

    private companion object {
        const val ROW_STORED_AT = "2023-11-14T22:13:20.000Z"
        const val THIRD_CLIENT_ID = "2c1e" + "6f70-8a3b-4e5f-" + "9a0b-" + "3c4d5e" + "6f7a8b"
        const val ROW_COUNT_SQL = "SELECT COUNT(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
        const val ALICE_SEED = 1
        const val BOB_SEED = 2
        const val LOGIN_KEY = "\"token\":\""
    }

    private class PullStores(
        val temp: TempDatabase,
        val tokenStore: TokenStore,
        val accounts: AccountStore,
        val store: TranscriptionStore,
        val handoffs: HandoffStore,
        val aliceId: Long,
        val bobId: Long,
    )

    private class SessionRef(val token: String, val id: Long)

    private class PullWorld(val s1: SessionRef, val s2: SessionRef, val s3: SessionRef, val bob: SessionRef)

    private fun openStores(): PullStores {
        val temp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val aliceId = TokenFixtures.register(temp, "alice", ALICE_SEED).accountId
        val bobId = TokenFixtures.register(temp, "bob", BOB_SEED).accountId
        assertEquals("sync-api: alice is the first account", 1L, aliceId)
        val handoffs = HandoffFixtures.handoffStore(temp, tokenStore, AccountFixtures.fixedClock)
        val store = SyncRouteTestSupport.newStore(temp, clock)
        return PullStores(temp, tokenStore, AccountFixtures.newStore(temp), store, handoffs, aliceId, bobId)
    }

    // The token text is the value of the token field of the login answer; the token alphabet has no quote.
    private fun loginTokenOf(body: String): String {
        val start = body.indexOf(LOGIN_KEY)
        check(start >= 0) { "sync-api test: the login answer has no token field" }
        val from = start + LOGIN_KEY.length
        val end = body.indexOf('"', from)
        check(end > from) { "sync-api test: the login token is empty" }
        return body.substring(from, end)
    }

    // One value of one row, read with SQL; null when the column is NULL.
    private suspend fun readCount(temp: TempDatabase, sql: String, key: Long): Long? =
        temp.db.transaction<Long?> { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, key)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: the read returned no row" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    private suspend fun latestSessionId(temp: TempDatabase, accountId: Long): Long {
        val sql = "SELECT MAX(id) FROM tokens WHERE account_id = ? AND kind = 'session'"
        return checkNotNull(readCount(temp, sql, accountId))
    }

    private suspend fun numberOf(temp: TempDatabase, session: SessionRef): Long? =
        readCount(temp, "SELECT last_pulled_change FROM tokens WHERE id = ?", session.id)

    private suspend fun purgedOf(temp: TempDatabase, accountId: Long): Long =
        checkNotNull(readCount(temp, "SELECT purged_through FROM accounts WHERE id = ?", accountId))

    private suspend fun deleteRowsOf(temp: TempDatabase, accountId: Long): Long =
        checkNotNull(readCount(temp, ROW_COUNT_SQL, accountId))

    private suspend fun HttpClient.signInAs(
        stores: PullStores,
        accountId: Long,
        username: String,
        seed: Int,
    ): SessionRef {
        val verifier = Base64.getEncoder().encodeToString(AccountFixtures.verifierBytes(seed))
        val reply = post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{\"username\":\"$username\",\"auth_verifier\":\"$verifier\"}")
        }
        assertEquals("sync-api: sign-in status", HttpStatusCode.OK, reply.status)
        return SessionRef(loginTokenOf(reply.bodyAsText()), latestSessionId(stores.temp, accountId))
    }

    // Sign-ins in the stated order: bob once (token id 1), then alice three times (token ids 2, 3, 4).
    private suspend fun HttpClient.signInWorld(stores: PullStores): PullWorld {
        val bob = signInAs(stores, stores.bobId, "bob", BOB_SEED)
        val s1 = signInAs(stores, stores.aliceId, "alice", ALICE_SEED)
        val s2 = signInAs(stores, stores.aliceId, "alice", ALICE_SEED)
        val s3 = signInAs(stores, stores.aliceId, "alice", ALICE_SEED)
        assertEquals("sync-api: bob's session id", 1L, bob.id)
        assertEquals("sync-api: alice's first session id", 2L, s1.id)
        assertEquals("sync-api: alice's second session id", 3L, s2.id)
        assertEquals("sync-api: alice's third session id", 4L, s3.id)
        for (session in listOf(s1, s2, s3)) {
            assertNotEquals("sync-api: alice's session id is not her account id", stores.aliceId, session.id)
        }
        return PullWorld(s1, s2, s3, bob)
    }

    private suspend fun HttpClient.pushOne(
        session: SessionRef,
        clientId: String,
        encrypted: String,
    ): HttpResponse =
        post("/v1/sync") {
            header("Authorization", "Bearer ${session.token}")
            setBody(SyncRouteTestSupport.pushBody(clientId, "0", encrypted))
            contentType(ContentType.Application.Json)
        }

    private suspend fun HttpClient.deleteOne(session: SessionRef, clientId: String): HttpResponse =
        delete("/v1/transcriptions/$clientId") {
            header("Authorization", "Bearer ${session.token}")
        }

    private suspend fun HttpClient.pullAt(session: SessionRef, since: String): HttpResponse =
        get("/v1/transcriptions?since=$since") {
            header("Authorization", "Bearer ${session.token}")
        }

    private suspend fun HttpClient.listFull(session: SessionRef): HttpResponse =
        get("/v1/transcriptions") {
            header("Authorization", "Bearer ${session.token}")
        }

    private suspend fun HttpClient.signOut(session: SessionRef): HttpResponse =
        post("/v1/auth/logout") {
            header("Authorization", "Bearer ${session.token}")
        }

    // Push A (change 1), push B (2), push C (3), delete A (4), delete B (5); bob pushes and deletes one row.
    private suspend fun HttpClient.writeWorld(world: PullWorld) {
        expectNoContent("push A", pushOne(world.s1, CLIENT_ID, ENCRYPTED))
        expectNoContent("push B", pushOne(world.s1, OTHER_CLIENT_ID, OTHER_ENCRYPTED))
        expectNoContent("push C", pushOne(world.s1, THIRD_CLIENT_ID, ENCRYPTED))
        expectNoContent("delete A", deleteOne(world.s1, CLIENT_ID))
        expectNoContent("delete B", deleteOne(world.s1, OTHER_CLIENT_ID))
        expectNoContent("bob push", pushOne(world.bob, CLIENT_ID, ENCRYPTED))
        expectNoContent("bob delete", deleteOne(world.bob, CLIENT_ID))
    }

    private fun expectNoContent(what: String, reply: HttpResponse) {
        assertEquals("sync-api: $what status", HttpStatusCode.NoContent, reply.status)
    }

    private suspend fun expectReply(what: String, code: HttpStatusCode, body: String, reply: HttpResponse) {
        assertEquals("sync-api: $what status", code, reply.status)
        assertEquals("sync-api: $what body", body, reply.bodyAsText())
    }

    // The alice records as a page holds them: C live, A and B delete records.
    private fun cRecord(): String =
        SyncRouteTestSupport.pageRecordJson(THIRD_CLIENT_ID, 3L, false, 0, ENCRYPTED, ROW_STORED_AT)

    private fun aGone(): String =
        SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 4L, true, null, null, ROW_STORED_AT)

    private fun bGone(): String =
        SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 5L, true, null, null, ROW_STORED_AT)

    private fun pageBody(next: String, vararg records: String): String =
        "{\"items\":[" + records.joinToString(",") + "],\"next\":\"$next\",\"more\":false}"

    @Test
    fun `a delete record goes once every session has pulled past it and an old cursor answers 410`() =
        runBlocking<Unit> {
            val stores = openStores()
            val ok = HttpStatusCode.OK
            val gone = HttpStatusCode.Gone
            testApplication {
                application {
                    syncApiModule(stores.accounts, stores.tokenStore, stores.store, stores.handoffs)
                }
                val world = client.signInWorld(stores)
                client.writeWorld(world)
                val temp = stores.temp
                val alice = stores.aliceId
                val s1 = world.s1
                val s2 = world.s2
                val s3 = world.s3
                val full = pageBody("1-5", cRecord(), aGone(), bGone())

                expectReply("s1 first pull", ok, full, client.pullAt(s1, "1-0"))
                assertEquals("sync-api: s1 number at since 0", 0L, numberOf(temp, s1))
                expectReply("s2 pull", ok, full, client.pullAt(s2, "1-0"))
                assertEquals("sync-api: s2 number at since 0", 0L, numberOf(temp, s2))
                expectReply("s3 pull", ok, full, client.pullAt(s3, "1-0"))
                assertEquals("sync-api: s3 number at since 0", 0L, numberOf(temp, s3))
                assertEquals("sync-api: alice delete rows still 2", 2L, deleteRowsOf(temp, alice))

                expectReply("s1 pull at 5", ok, pageBody("1-5"), client.pullAt(s1, "1-5"))
                assertEquals("sync-api: s1 number is 5", 5L, numberOf(temp, s1))
                expectReply("s2 pull at 5", ok, pageBody("1-5"), client.pullAt(s2, "1-5"))
                assertEquals("sync-api: s2 number is 5", 5L, numberOf(temp, s2))
                assertEquals("sync-api: s3 still holds everything", 0L, numberOf(temp, s3))
                assertEquals("sync-api: alice delete rows still 2", 2L, deleteRowsOf(temp, alice))
                assertEquals("sync-api: purged_through still 0", 0L, purgedOf(temp, alice))

                val atFour = pageBody("1-5", bGone())
                expectReply("s3 pull at 4", ok, atFour, client.pullAt(s3, "1-4"))
                assertEquals("sync-api: alice delete rows after the floor", 1L, deleteRowsOf(temp, alice))
                assertEquals("sync-api: purged_through is 4", 4L, purgedOf(temp, alice))
                assertEquals("sync-api: s3 number is 4", 4L, numberOf(temp, s3))

                assertEquals("sync-api: s1 number before the refusal", 5L, numberOf(temp, s1))
                assertEquals("sync-api: purged_through before the refusal", 4L, purgedOf(temp, alice))
                expectReply("s1 pull below the floor", gone, GONE_BODY, client.pullAt(s1, "1-3"))
                assertEquals("sync-api: s1 number after the refusal", 5L, numberOf(temp, s1))
                assertEquals("sync-api: purged_through after the refusal", 4L, purgedOf(temp, alice))
                assertEquals("sync-api: delete rows after the refusal", 1L, deleteRowsOf(temp, alice))
                expectReply("s3 pull at the floor", ok, atFour, client.pullAt(s3, "1-4"))
                assertEquals("sync-api: s3 number stays 4", 4L, numberOf(temp, s3))

                assertEquals("sync-api: bob delete rows still 1", 1L, deleteRowsOf(temp, stores.bobId))
                assertEquals("sync-api: bob purged_through still 0", 0L, purgedOf(temp, stores.bobId))
            }
        }

    @Test
    fun `a full list purges after a lagging session signs out and an old cursor answers 410`() =
        runBlocking<Unit> {
            val stores = openStores()
            val ok = HttpStatusCode.OK
            val gone = HttpStatusCode.Gone
            val noContent = HttpStatusCode.NoContent
            testApplication {
                application {
                    syncApiModule(stores.accounts, stores.tokenStore, stores.store, stores.handoffs)
                }
                val world = client.signInWorld(stores)
                client.writeWorld(world)
                val temp = stores.temp
                val alice = stores.aliceId
                val s1 = world.s1
                val s2 = world.s2
                val s3 = world.s3

                expectReply("s1 pull at 5", ok, pageBody("1-5"), client.pullAt(s1, "1-5"))
                expectReply("s2 pull at 5", ok, pageBody("1-5"), client.pullAt(s2, "1-5"))
                expectReply("s3 pull at 4", ok, pageBody("1-5", bGone()), client.pullAt(s3, "1-4"))
                assertEquals("sync-api: s3 number is 4", 4L, numberOf(temp, s3))
                assertEquals("sync-api: alice delete rows after the floor", 1L, deleteRowsOf(temp, alice))
                assertEquals("sync-api: purged_through is 4", 4L, purgedOf(temp, alice))

                expectReply("s3 sign-out", noContent, "", client.signOut(s3))
                expectReply("s1 plain list", ok, pageBody("1-5", cRecord()), client.listFull(s1))
                assertEquals("sync-api: plain list purges the record at 5", 0L, deleteRowsOf(temp, alice))
                assertEquals("sync-api: purged_through rises to 5", 5L, purgedOf(temp, alice))

                expectReply("s1 pull below the new floor", gone, GONE_BODY, client.pullAt(s1, "1-4"))
                expectReply("s1 pull at the new floor", ok, pageBody("1-5"), client.pullAt(s1, "1-5"))
                assertEquals("sync-api: bob delete rows still 1", 1L, deleteRowsOf(temp, stores.bobId))
                assertEquals("sync-api: bob purged_through still 0", 0L, purgedOf(temp, stores.bobId))
            }
        }
}
