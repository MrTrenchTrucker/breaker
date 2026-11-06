package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.GONE_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_ENCRYPTED
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The purge on the pull route: the floor is the lowest number of the live user sessions, the purge runs
// after every successful pull, and purged_through rises only to the highest delete record removed.
// Two accounts (alice, bob) are built first; alice's sessions get token ids other than her account id.
internal class SyncRoutesPullPurgeTest : TempDatabaseTest() {

    private companion object {
        const val STORED_AT = "2023-11-14T22:13:20Z"
    }

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private var bobId: Long = 0L
    private var aliceFirstId: Long = 0L
    private var aliceSecondId: Long = 0L
    private var bobFirstId: Long = 0L
    private lateinit var aliceToken: String
    private lateinit var aliceSecondToken: String

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        aliceId = TokenFixtures.register(temp, "alice", 1).accountId
        bobId = TokenFixtures.register(temp, "bob", 2).accountId
        val bobFirst = SyncRouteTestSupport.issuedSession(tokenStore, bobId)
        SyncRouteTestSupport.issuedSession(tokenStore, bobId)
        val aliceFirst = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        val aliceSecond = SyncRouteTestSupport.issuedSession(tokenStore, aliceId)
        aliceFirstId = aliceFirst.tokenId
        aliceSecondId = aliceSecond.tokenId
        bobFirstId = bobFirst.tokenId
        assertNotEquals("sync-api: setup, alice first id", aliceId, aliceFirstId)
        assertNotEquals("sync-api: setup, alice second id", aliceId, aliceSecondId)
        assertNotEquals("sync-api: setup, the two accounts", aliceId, bobId)
        aliceToken = aliceFirst.secret.reveal()
        aliceSecondToken = aliceSecond.secret.reveal()
    }

    private fun pushRow(token: String, clientId: String, encrypted: String): SyncReply =
        SyncRouteTestSupport.postSync(
            tokenStore, store, token, SyncRouteTestSupport.pushBody(clientId, "0", encrypted),
        )

    private fun deleteRow(token: String, clientId: String): SyncReply =
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, clientId)

    private fun pullSince(token: String, accountId: Long, since: Long): SyncReply =
        SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-$since")

    private fun expectStatus(what: String, expected: HttpStatusCode, reply: SyncReply) =
        assertEquals("sync-api: $what status", expected, reply.status)

    private fun expectBody(what: String, expected: String, reply: SyncReply) =
        assertEquals("sync-api: $what body", expected, reply.body)

    private fun revoke(tokenId: Long): Boolean = runBlocking { tokenStore.revokeSession(tokenId) }

    private fun pulledNumber(tokenId: Long): Long? =
        inTransaction(temp) { connection ->
            val sql = "SELECT last_pulled_change FROM tokens WHERE id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, tokenId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row for the number" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    private fun purgedThroughOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no account row for purged_through" }
                    rows.getLong(1)
                }
            }
        }

    private fun rowCountOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            val sql = "SELECT COUNT(*) FROM transcriptions WHERE account_id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, accountId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: count returned no row" }
                    rows.getLong(1)
                }
            }
        }

    @Test
    fun `two sessions both caught up let a delete record go and a later since below it answers 410`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        val first = pullSince(aliceToken, aliceId, 2)
        expectStatus("first caught-up pull", HttpStatusCode.OK, first)
        assertEquals("sync-api: held while the second session has no number", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: still stored while held", 1L, rowCountOf(aliceId))
        val second = pullSince(aliceSecondToken, aliceId, 2)
        expectStatus("second caught-up pull", HttpStatusCode.OK, second)
        assertEquals("sync-api: purged_through rises to the floor", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: the delete record is purged", 0L, rowCountOf(aliceId))
        assertEquals("sync-api: first session number", 2L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: second session number", 2L, pulledNumber(aliceSecondId))
        val gone = pullSince(aliceToken, aliceId, 1)
        expectStatus("a since below the purged number", HttpStatusCode.Gone, gone)
        expectBody("a since below the purged number", GONE_BODY, gone)
    }

    @Test
    fun `a lagging session holds the delete record and its page still carries it`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        deleteRow(aliceToken, OTHER_CLIENT_ID)
        val reply = pullSince(aliceToken, aliceId, 2)
        expectStatus("lagging pull", HttpStatusCode.OK, reply)
        expectBody(
            "lagging page carries the delete record above the since",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 4, true, null, null, STORED_AT) +
                "],\"next\":\"$aliceId-4\",\"more\":false}",
            reply,
        )
        assertEquals("sync-api: the number lands on the pulling session", 2L, pulledNumber(aliceFirstId))
        assertNull("sync-api: the other session of alice has no number", pulledNumber(aliceSecondId))
        assertNull("sync-api: a session of bob has no number", pulledNumber(bobFirstId))
        assertEquals("sync-api: the lagging session holds the purge", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: both delete records stay stored", 2L, rowCountOf(aliceId))
    }

    @Test
    fun `a since equal to the new purged_through answers 200 and one below answers 410`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("first pull", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        expectStatus("second pull", HttpStatusCode.OK, pullSince(aliceSecondToken, aliceId, 2))
        assertEquals("sync-api: purged_through is 2", 2L, purgedThroughOf(aliceId))
        val equal = pullSince(aliceToken, aliceId, 2)
        expectStatus("a since equal to purged_through", HttpStatusCode.OK, equal)
        expectBody(
            "a since equal to purged_through",
            "{\"items\":[],\"next\":\"$aliceId-2\",\"more\":false}",
            equal,
        )
        val below = pullSince(aliceToken, aliceId, 1)
        expectStatus("a since one below purged_through", HttpStatusCode.Gone, below)
        expectBody("a since one below purged_through", GONE_BODY, below)
    }

    @Test
    fun `a delete record one above the floor arrives in the same page and survives`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        pushRow(aliceToken, OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        deleteRow(aliceToken, OTHER_CLIENT_ID)
        expectStatus("second session pull", HttpStatusCode.OK, pullSince(aliceSecondToken, aliceId, 4))
        assertEquals("sync-api: no purge while the first session has no number", 0L, purgedThroughOf(aliceId))
        val reply = pullSince(aliceToken, aliceId, 3)
        expectStatus("floor pull", HttpStatusCode.OK, reply)
        expectBody(
            "page holds the record above the floor",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 4, true, null, null, STORED_AT) +
                "],\"next\":\"$aliceId-4\",\"more\":false}",
            reply,
        )
        assertEquals("sync-api: purged_through is the floor record 3", 3L, purgedThroughOf(aliceId))
        assertEquals("sync-api: only the record above the floor survives", 1L, rowCountOf(aliceId))
        assertEquals("sync-api: first session number", 3L, pulledNumber(aliceFirstId))
        assertEquals("sync-api: second session number", 4L, pulledNumber(aliceSecondId))
        val again = pullSince(aliceToken, aliceId, 3)
        assertEquals("sync-api: the surviving record is still served", reply.body, again.body)
    }

    @Test
    fun `a revoked lagging session stops holding the delete record`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("pull while held", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: held before the revoke", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: stored before the revoke", 1L, rowCountOf(aliceId))
        assertTrue("sync-api: the second session is revoked", revoke(aliceSecondId))
        expectStatus("pull after the revoke", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: purged_through after the revoke", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: purged after the revoke", 0L, rowCountOf(aliceId))
    }

    @Test
    fun `an expired lagging session stops holding the delete record`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("pull while held", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: held before the expiry", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: stored before the expiry", 1L, rowCountOf(aliceId))
        TokenFixtures.expireSession(temp, aliceSecondId)
        expectStatus("pull after the expiry", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertEquals("sync-api: purged_through after the expiry", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: purged after the expiry", 0L, rowCountOf(aliceId))
    }

    @Test
    fun `a full list purges after the floor rises and a session with no number holds the purge`() {
        pushRow(aliceToken, CLIENT_ID, ENCRYPTED)
        deleteRow(aliceToken, CLIENT_ID)
        expectStatus("first session pull", HttpStatusCode.OK, pullSince(aliceToken, aliceId, 2))
        assertNull("sync-api: the second session has no number yet", pulledNumber(aliceSecondId))
        assertEquals("sync-api: a session with no number holds the purge", 0L, purgedThroughOf(aliceId))
        assertEquals("sync-api: record held", 1L, rowCountOf(aliceId))
        expectStatus("second session pull", HttpStatusCode.OK, pullSince(aliceSecondToken, aliceId, 1))
        assertEquals("sync-api: second session number", 1L, pulledNumber(aliceSecondId))
        val listedHeld = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        expectStatus("full list while held", HttpStatusCode.OK, listedHeld)
        assertEquals("sync-api: floor 1 holds record 2", 0L, purgedThroughOf(aliceId))
        assertTrue("sync-api: the second session is revoked", revoke(aliceSecondId))
        val listed = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        expectStatus("full list after the floor rises", HttpStatusCode.OK, listed)
        expectBody(
            "full list after the floor rises",
            "{\"items\":[],\"next\":\"$aliceId-2\",\"more\":false}",
            listed,
        )
        assertEquals("sync-api: a full list purges up to the floor", 2L, purgedThroughOf(aliceId))
        assertEquals("sync-api: the record is purged by the full list", 0L, rowCountOf(aliceId))
    }

    @Test
    fun `an account with one session purges up to its own since`() {
        val carolId = TokenFixtures.register(temp, "carol", 3).accountId
        val carol = SyncRouteTestSupport.issuedSession(tokenStore, carolId)
        assertNotEquals("sync-api: setup, carol's session id", carolId, carol.tokenId)
        val carolToken = carol.secret.reveal()
        pushRow(carolToken, CLIENT_ID, ENCRYPTED)
        deleteRow(carolToken, CLIENT_ID)
        val reply = pullSince(carolToken, carolId, 2)
        expectStatus("carol pull", HttpStatusCode.OK, reply)
        expectBody("carol pull", "{\"items\":[],\"next\":\"$carolId-2\",\"more\":false}", reply)
        assertEquals("sync-api: carol purged_through is her own since", 2L, purgedThroughOf(carolId))
        assertEquals("sync-api: carol record purged", 0L, rowCountOf(carolId))
        assertEquals("sync-api: carol session number", 2L, pulledNumber(carol.tokenId))
        assertEquals("sync-api: alice purged_through is untouched", 0L, purgedThroughOf(aliceId))
    }
}
