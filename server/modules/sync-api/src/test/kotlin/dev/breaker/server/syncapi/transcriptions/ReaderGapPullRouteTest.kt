package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderGapPullRouteTest : TempDatabaseTest() {

    private lateinit var clock: MutableClock
    private lateinit var temp: TempDatabase
    private var accountId = 0L
    private var bobId = 0L
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore

    private fun cursor(since: Long): String = "$accountId-$since"

    @org.junit.Before
    fun openRoutes() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
        bobId = TokenFixtures.register(temp, "bob", 2).accountId
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
    }

    private fun postSync(tokenStore: TokenStore, store: TranscriptionStore, token: String, clientId: String,
                         keyVersion: Int, encrypted: String) =
        SyncRouteTestSupport.postSync(tokenStore, store, token,
            """{"client_id":"$clientId","key_version":$keyVersion,"encrypted":"$encrypted"}""")

    private fun deleteTranscription(token: String, clientId: String) =
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, clientId)

    private fun getPage(token: String, since: String?) =
        SyncRouteTestSupport.getPage(tokenStore, store, token, since)

    @Test
    fun `a full list pull through the route purges the delete records under the floor`() {
        // n06 at route level: two Alice sessions X and Y. Earlier pulls don't purge;
        // the full list pull is what purges.
        // World: Alice pushes c1=1, deletes c1=2 (delete record at change 2, head 2).
        // X pulls since cursor(1): number MIN(1, 2) = 1, floor 1 -> purge at 1: nothing.
        // Y pulls since cursor(2): number MIN(2, 2) = 2, floor = lowest(1, 2) = 1 -> purge at 1: nothing.
        // Revoke X (TokenStore.kt:149 revokeSession) -> floor = Y's 2.
        // Y pulls full list: original floor 2 -> purge at 2: count 0, purged_through 2.
        // Break: since null -> floor null -> no purge -> count 1, purged_through 0.
        // Alice has exactly two sessions, X and Y: a live session that never pulled has no number
        // and would mean no floor at all (then the original does not purge either).
        // Session X pushes and deletes, then pulls since cursor(1).
        val xIssued = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val xToken = xIssued.secret.reveal()
        val xSessionId = xIssued.tokenId
        val xClientId = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + "1"

        assertEquals("sync-api: push answers 204", 204,
            postSync(tokenStore, store, xToken, xClientId, 0, SyncRouteTestSupport.ENCRYPTED).status.value)
        assertEquals("sync-api: delete answers 204", 204,
            deleteTranscription(xToken, xClientId).status.value)
        assertEquals("sync-api: X pull since cursor(1) answers 200", 200,
            getPage(xToken, cursor(1)).status.value)

        // Session Y: pulls since cursor(2)
        val yIssued = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val yToken = yIssued.secret.reveal()
        val ySessionId = yIssued.tokenId
        assertEquals("sync-api: Y pull since cursor(2) answers 200", 200,
            getPage(yToken, cursor(2)).status.value)

        // Revoke X -> floor is now Y's 2.
        runBlocking { tokenStore.revokeSession(xSessionId) }

        // Y pulls full list (no since):
        val reply = getPage(yToken, null)
        assertEquals("sync-api: Y full list pull answers 200", 200, reply.status.value)

        val purged = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

        assertEquals("sync-api: full list pull purges, purged_through = 2", 2L, purged)
    }

    @Test
    fun `a pull never moves the number of a session of another account`() {
        // n09: register issues no token. Register Alice (account id 1), then Bob (account id 2).
        // Issue Bob's two sessions first -> token ids 1, 2. Then Alice's session -> token id 3.
        // Alice's account id 1 is NOT her session id 3
        // ("register issues no token; Bob 1, 2; Alice 3; account id 1 is not 3").
        // Alice pushes 5 -> head 5 on Alice's account.
        // Alice pulls via route at cursor(1-5):
        //   original -> setPulledChangeOn(session 3, account 1, since 5) -> stores MIN(5, 5) = 5 on Alice row
        //   break -> setPulledChangeOn(account 1, account 1, since 5) -> WHERE id = 1 AND account_id = 1 ->
        //            Bob's session id 1 has account_id 2 -> no match -> Alice's session 3 stays NULL
        // Read Alice's number by her session id from IssuedToken.tokenId.
        val bobIssued1 = SyncRouteTestSupport.issuedSession(tokenStore, bobId)      // token id 1
        val bobIssued2 = SyncRouteTestSupport.issuedSession(tokenStore, bobId)      // token id 2
        val aliceIssued = SyncRouteTestSupport.issuedSession(tokenStore, accountId) // token id 3
        val aliceToken = aliceIssued.secret.reveal()
        val aliceSessionId = aliceIssued.tokenId

        // Alice pushes 5 -> head 5 on Alice's account.
        repeat(5) { i ->
            val cid = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + (i + 1).toString()
            assertEquals("sync-api: push answers 204", 204,
                postSync(tokenStore, store, aliceToken, cid, 0, SyncRouteTestSupport.ENCRYPTED).status.value)
        }

        // Alice pulls via route at since cursor(1-5).
        val reply = getPage(aliceToken, cursor(5))
        assertEquals("sync-api: route pull at since cursor(1-5) answers 200", 200, reply.status.value)

        // Read Alice's stored number by her session id.
        val storedNumber = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { s ->
                s.setLong(1, aliceSessionId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

        assertEquals("sync-api: route stores number on the pulling session id", 5L, storedNumber)
    }

    @Test
    fun `a route pull with no since stores nothing`() {
        // Accept control for n09: Alice pulls with no since query.
        // Both original and break: since = null -> setPulledChangeOn not called ->
        // no number set, page returned 200.
        val aliceToken = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
        val cid = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + "1"

        assertEquals("sync-api: push answers 204", 204,
            postSync(tokenStore, store, aliceToken, cid, 0, SyncRouteTestSupport.ENCRYPTED).status.value)

        val reply = getPage(aliceToken, null)
        assertEquals("sync-api: route pull with no since answers 200", 200, reply.status.value)
        val sql = "SELECT last_pulled_change FROM tokens WHERE account_id = ? AND kind = 'session'"
        assertNull("sync-api: no number set by a pull without since",
            inTransaction(temp) { connection ->
                connection.prepareStatement(sql).use { s ->
                    s.setLong(1, accountId)
                    s.executeQuery().use { rows ->
                        if (!rows.next()) null else {
                            val v = rows.getLong(1)
                            if (rows.wasNull()) null else v
                        }
                    }
                }
            })
    }
}
