package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal class ReaderTokenStorePullNumberTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun newStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun issueSession(store: TokenStore, accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    // Raise the head with REAL pushes so setPulledChangeOn clamps to a non-zero head.
    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun pushRows(temp: TempDatabase, accountId: Long, count: Int) {
        runBlocking {
            val transcriptionStore = SyncRouteTestSupport.newStore(temp, clock)
            repeat(count) { i ->
                transcriptionStore.push(accountId, clientId(i), 0, SyncRouteTestSupport.ENCRYPTED)
            }
        }
    }

    @Test
    fun `a since above the head stores the head`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val tokenId = issueSession(store, owner.accountId)
        // 7 pushes -> head 7. A since of 9 clamps to min(9, 7) = 7.
        pushRows(temp, owner.accountId, 7)
        inTransaction(temp) { connection ->
            assertEquals("sync-api: since above head clamps to head", 7L,
                store.setPulledChangeOn(connection, tokenId, owner.accountId, 9L))
        }
        // Accept control: read the stored number back - "stores" must be true, not just the return.
        inTransaction(temp) { connection ->
            assertEquals("sync-api: stored number read back is 7", 7L,
                store.pullFloorOn(connection, owner.accountId))
        }
    }

    @Test
    fun `a later smaller since lowers the stored number`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val tokenId = issueSession(store, owner.accountId)
        // 7 pushes -> head 7. Pull at since 9 clamps to 7, then at since 2 stores 2.
        // Set, not max: the second pull lowers the number.
        pushRows(temp, owner.accountId, 7)
        inTransaction(temp) { connection ->
            assertEquals("sync-api: first pull stores 7", 7L,
                store.setPulledChangeOn(connection, tokenId, owner.accountId, 9L))
            assertEquals("sync-api: second pull lowers to 2", 2L,
                store.setPulledChangeOn(connection, tokenId, owner.accountId, 2L))
        }
    }

    @Test
    fun `a since equal to the head stores that number`() {
        val temp = openTemp()
        val store = newStore(temp)
        val owner = TokenFixtures.register(temp, "alice", 1)
        val tokenId = issueSession(store, owner.accountId)
        // 3 pushes -> head 3. A since of 3 clamps to min(3, 3) = 3.
        pushRows(temp, owner.accountId, 3)
        inTransaction(temp) { connection ->
            assertEquals("sync-api: since at head stores head", 3L,
                store.setPulledChangeOn(connection, tokenId, owner.accountId, 3L))
        }
    }
}
