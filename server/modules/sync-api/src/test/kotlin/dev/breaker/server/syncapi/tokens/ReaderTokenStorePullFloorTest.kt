package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderTokenStorePullFloorTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun newStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun issueSession(store: TokenStore, accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    // Raise the head with REAL pushes so setPulledChangeOn does not clamp every number to 0.
    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun pushRows(temp: TempDatabase, accountId: Long, count: Int) {
        runBlocking {
            val transcriptionStore = SyncRouteTestSupport.newStore(temp, clock)
            repeat(count) { i ->
                transcriptionStore.push(accountId, clientId(i), 0, SyncRouteTestSupport.ENCRYPTED)
            }
        }
    }

    private fun setNumber(store: TokenStore, temp: TempDatabase, tokenId: Long,
                          accountId: Long, since: Long) {
        inTransaction(temp) { connection ->
            store.setPulledChangeOn(connection, tokenId, accountId, since)
        }
    }

    @Test
    fun `the floor over two sessions is the lowest live number`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val b = issueSession(store, owner.accountId)
        // 7 pushes -> head 7. Session a pulls at 7, session b pulls at 3.
        // Floor = 3 (lowest live number).
        pushRows(temp, owner.accountId, 7)
        setNumber(store, temp, a, owner.accountId, 7)
        setNumber(store, temp, b, owner.accountId, 3)
        inTransaction(temp) { connection ->
            assertEquals("sync-api: floor of 7 and 3 is 3", 3L,
                store.pullFloorOn(connection, owner.accountId))
        }
    }

    @Test
    fun `a live session with no number means no floor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val b = issueSession(store, owner.accountId)
        // 4 pushes -> head 4. Session a pulls at 4 (stored min(4,4)=4). Session b never pulled (NULL).
        pushRows(temp, owner.accountId, 4)
        setNumber(store, temp, a, owner.accountId, 4)
        // b never pulled: withNumber < live, so no floor.
        inTransaction(temp) { connection ->
            assertNull("sync-api: a session without a number means no floor",
                store.pullFloorOn(connection, owner.accountId))
        }
    }

    @Test
    fun `a revoked session does not hold the floor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val b = issueSession(store, owner.accountId)
        // 5 pushes -> head 5. Session a holds 5 (live), session b holds 1 (to be revoked).
        pushRows(temp, owner.accountId, 5)
        setNumber(store, temp, a, owner.accountId, 5)
        setNumber(store, temp, b, owner.accountId, 1)
        runBlocking { store.revokeSession(b) }
        // Only a is live now, holding 5; floor = 5.
        inTransaction(temp) { connection ->
            assertEquals("sync-api: revoked session b drops out, floor = 5", 5L,
                store.pullFloorOn(connection, owner.accountId))
        }
        // Accept control: same world with b still live, floor = 1 (the lowest live number).
        val temp2 = openTemp()
        val store2 = newStore(temp2)
        val owner2 = TokenFixtures.register(temp2, "alice", 1)
        val a2 = issueSession(store2, owner2.accountId)
        val b2 = issueSession(store2, owner2.accountId)
        pushRows(temp2, owner2.accountId, 5)
        setNumber(store2, temp2, a2, owner2.accountId, 5)
        setNumber(store2, temp2, b2, owner2.accountId, 1)
        inTransaction(temp2) { connection ->
            assertEquals("sync-api: both live, floor = lowest = 1", 1L,
                store2.pullFloorOn(connection, owner2.accountId))
        }
    }

    @Test
    fun `an expired session does not hold the floor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val b = issueSession(store, owner.accountId)
        // 5 pushes -> head 5. Session a holds 5 (live), session b holds 1 (to be expired).
        pushRows(temp, owner.accountId, 5)
        setNumber(store, temp, a, owner.accountId, 5)
        setNumber(store, temp, b, owner.accountId, 1)
        TokenFixtures.expireSession(temp, b)
        // Only a is live now; floor = 5.
        inTransaction(temp) { connection ->
            assertEquals("sync-api: expired session b drops out, floor = 5", 5L,
                store.pullFloorOn(connection, owner.accountId))
        }
        // Accept control: same world with b live, floor = 1.
        val temp2 = openTemp()
        val store2 = newStore(temp2)
        val owner2 = TokenFixtures.register(temp2, "alice", 1)
        val a2 = issueSession(store2, owner2.accountId)
        val b2 = issueSession(store2, owner2.accountId)
        pushRows(temp2, owner2.accountId, 5)
        setNumber(store2, temp2, a2, owner2.accountId, 5)
        setNumber(store2, temp2, b2, owner2.accountId, 1)
        inTransaction(temp2) { connection ->
            assertEquals("sync-api: both live, floor = lowest = 1", 1L,
                store2.pullFloorOn(connection, owner2.accountId))
        }
    }

    @Test
    fun `a session past the 180 day cap does not hold the floor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val b = issueSession(store, owner.accountId)
        // 5 pushes -> head 5. Session a holds 5 (live), session b holds 1 (past the cap).
        pushRows(temp, owner.accountId, 5)
        setNumber(store, temp, a, owner.accountId, 5)
        setNumber(store, temp, b, owner.accountId, 1)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE tokens SET expires_at_ms = ? WHERE id = ?"
                ).use { s ->
                    s.setLong(1, TokenFixtures.START.toEpochMilli() + 15_552_000_001L)
                    s.setLong(2, b)
                    s.executeUpdate()
                }
            }
        }
        // b is past the cap, not live; floor = 5.
        inTransaction(temp) { connection ->
            assertEquals("sync-api: session b past the 180 day cap drops out, floor = 5", 5L,
                store.pullFloorOn(connection, owner.accountId))
        }
        // Accept control: same world with b live, floor = 1.
        val temp2 = openTemp()
        val store2 = newStore(temp2)
        val owner2 = TokenFixtures.register(temp2, "alice", 1)
        val a2 = issueSession(store2, owner2.accountId)
        val b2 = issueSession(store2, owner2.accountId)
        pushRows(temp2, owner2.accountId, 5)
        setNumber(store2, temp2, a2, owner2.accountId, 5)
        setNumber(store2, temp2, b2, owner2.accountId, 1)
        inTransaction(temp2) { connection ->
            assertEquals("sync-api: both live, floor = lowest = 1", 1L,
                store2.pullFloorOn(connection, owner2.accountId))
        }
    }

    @Test
    fun `an agent token never counts toward the floor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val a = issueSession(store, owner.accountId)
        val agent = TokenFixtures.issuedOf(
            runBlocking { store.issueAgent(owner.accountId,
                AgentScope.TRANSCRIBE, "agent", null) })
        // 4 pushes -> head 4. Session a holds 4 (stored min(4,4)=4). Agent token has no number.
        pushRows(temp, owner.accountId, 4)
        setNumber(store, temp, a, owner.accountId, 4)
        // Floor = 4 from the one live session; the agent token never counts.
        inTransaction(temp) { connection ->
            assertEquals("sync-api: agent token ignored, floor = 4", 4L,
                store.pullFloorOn(connection, owner.accountId))
        }
    }
}
