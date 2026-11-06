package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.getPage
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.getPageWithAuthorization
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.sessionToken
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderSyncRoutesPullGuardTest : TempDatabaseTest() {

    private lateinit var clock: MutableClock
    private lateinit var temp: TempDatabase
    private var accountId = 0L
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private lateinit var token: String

    private fun cursor(since: Long): String = "$accountId-$since"

    @org.junit.Before
    fun openRoutes() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        token = sessionToken(tokenStore, accountId)
    }

    private fun pushAndDelete() {
        assertEquals("sync-api: push answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.postSync(
                tokenStore, store, token,
                "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"}").status)
        assertEquals("sync-api: delete answers 204",
            HttpStatusCode.NoContent,
            dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.deleteTranscription(
                tokenStore, store, token, CLIENT_ID).status)
    }

    private fun lastPulled(): Long? =
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT last_pulled_change FROM tokens WHERE account_id = ? AND kind = 'session'"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val value = rows.getLong(1)
                        if (rows.wasNull()) null else value
                    }
                }
            }
        }

    private fun purgedThrough(): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no floor row" }
                    rows.getLong(1)
                }
            }
        }

    private fun deleteRecordCount(): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    rows.getLong(1)
                }
            }
        }

    // Accept control for the stale-since refusal: since at purged_through answers a page.
    @Test
    fun `since at purged through answers a page`() {
        pushAndDelete() // change 1 push, change 2 delete record
        // Pull at since 2: number set to 2, floor = 2, since == purged_through is a page.
        val first = getPage(tokenStore, store, token, cursor(2))
        assertEquals("sync-api: first pull at since 2 answers 200", HttpStatusCode.OK, first.status)
        assertEquals("sync-api: number set to 2", 2L, lastPulled()!!)
        // Pull again at since 2 (== purged_through 2): still a page, number stays 2.
        val second = getPage(tokenStore, store, token, cursor(2))
        assertEquals("sync-api: second pull at since 2 answers 200", HttpStatusCode.OK, second.status)
        assertEquals("sync-api: number stays 2", 2L, lastPulled()!!)
        // Since 1 (below purged_through 2) gives Gone.
        val third = getPage(tokenStore, store, token, cursor(1))
        assertEquals("sync-api: since below purged_through answers Gone", HttpStatusCode.Gone, third.status)
    }

    @Test
    fun `a since below purged through answers 410 and writes nothing`() {
        pushAndDelete() // change 1 push, change 2 delete record
        // Pull at since 2 sets number 2, floor = 2, purge removes delete record at 2,
        // purged_through = 2.
        assertEquals("sync-api: pull at since 2 answers 200", HttpStatusCode.OK,
            getPage(tokenStore, store, token, cursor(2)).status)
        // Now since 1 (below purged_through 2) -> 410, and writes nothing:
        // number stays 2, purged_through stays 2, delete record count stays 0.
        val numberBefore = lastPulled()!!
        val floorBefore = purgedThrough()
        val deletesBefore = deleteRecordCount()
        val reply = getPage(tokenStore, store, token, cursor(1))
        assertEquals("sync-api: since below purged_through answers 410",
            HttpStatusCode.Gone, reply.status)
        assertEquals("sync-api: number unchanged", numberBefore, lastPulled()!!)
        assertEquals("sync-api: purged_through unchanged", floorBefore, purgedThrough())
        assertEquals("sync-api: delete record count unchanged", deletesBefore, deleteRecordCount())
    }

    @Test
    fun `a malformed cursor answers 400 and writes nothing`() {
        pushAndDelete() // change 1 push, change 2 delete record
        val numberBefore = lastPulled()
        val floorBefore = purgedThrough()
        val deletesBefore = deleteRecordCount()
        val reply = getPage(tokenStore, store, token, "not-a-cursor")
        assertEquals("sync-api: malformed cursor answers 400",
            HttpStatusCode.BadRequest, reply.status)
        assertNull("sync-api: number not set by a bad cursor", lastPulled())
        assertEquals("sync-api: purged_through unchanged", floorBefore, purgedThrough())
        assertEquals("sync-api: delete record count unchanged", deletesBefore, deleteRecordCount())
    }

    @Test
    fun `a foreign account cursor answers 400 and writes nothing`() {
        pushAndDelete() // change 1 push, change 2 delete record
        val bobId = TokenFixtures.register(temp, "bob", 2).accountId
        val numberBefore = lastPulled()
        val floorBefore = purgedThrough()
        val deletesBefore = deleteRecordCount()
        val reply = getPage(tokenStore, store, token, "$bobId-1")
        assertEquals("sync-api: foreign account cursor answers 400",
            HttpStatusCode.BadRequest, reply.status)
        assertNull("sync-api: number not set by a foreign cursor", lastPulled())
        assertEquals("sync-api: purged_through unchanged", floorBefore, purgedThrough())
        assertEquals("sync-api: delete record count unchanged", deletesBefore, deleteRecordCount())
    }

    @Test
    fun `an agent token answers 403 and writes nothing`() {
        pushAndDelete() // change 1 push, change 2 delete record
        val agent = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent", null) })
        val numberBefore = lastPulled()
        val floorBefore = purgedThrough()
        val deletesBefore = deleteRecordCount()
        val reply = getPageWithAuthorization(tokenStore, store, "Bearer ${agent.secret.reveal()}", cursor(2))
        assertEquals("sync-api: agent token answers 403",
            HttpStatusCode.Forbidden, reply.status)
        assertNull("sync-api: number not set by an agent token", lastPulled())
        assertEquals("sync-api: purged_through unchanged", floorBefore, purgedThrough())
        assertEquals("sync-api: delete record count unchanged", deletesBefore, deleteRecordCount())
    }
}
