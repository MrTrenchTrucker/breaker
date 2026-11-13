package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Tests for TranscriptionPurge.expireDueOn: the server TTL expiry job LOGIC only. */
internal class TranscriptionExpireTest : TempDatabaseTest() {

    private companion object {
        const val T0: Long = 1_700_000_000_000  // TokenFixtures.START = 2023-11-14T22:13:20Z
        const val TTL: Long = 7_776_000_000  // RETENTION_TTL_MS = 90 x 24 h
        const val NOW_MS_EXPIRED: Long = T0 + TTL + 1  // 2024-02-12T22:13:20.001Z
        const val STORED_AT_EXPIRED: String = "2024-02-12T22:13:20.001Z"
    }

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TranscriptionStore
    private lateinit var tokenStore: dev.breaker.server.syncapi.tokens.TokenStore
    private var alice: Long = 0L
    private var aliceToken: String = ""

    @Before
    fun openWorld() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        aliceToken = SyncRouteTestSupport.issuedSession(tokenStore, alice).secret.reveal()
    }

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { store.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun pushWithVersion(accountId: Long, n: Int, keyVersion: Int) {
        val result = runBlocking { store.push(accountId, clientId(n), keyVersion, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n at v$keyVersion", PushResult.Stored, result)
    }

    private fun expire(accountId: Long, nowMs: Long, batchCap: Int, ttlMs: Long = TTL): Int =
        inTransaction(temp) { connection ->
            TranscriptionPurge.expireDueOn(store, connection, accountId, ttlMs, nowMs, batchCap)
        }

    private fun liveRowsOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions WHERE account_id = $accountId AND deleted = 0").single()
    }

    private fun deletedRowsOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions WHERE account_id = $accountId AND deleted = 1").single()
    }

    private fun changeSeqOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT change_seq FROM accounts WHERE id = $accountId").single()
    }

    private fun purgedThroughOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT purged_through FROM accounts WHERE id = $accountId").single()
    }

    private fun prevWrappedDekOf(accountId: Long): ByteArray? = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT prev_wrapped_dek FROM accounts WHERE id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else {
                    val bytes = rows.getBytes(1)
                    if (rows.wasNull()) null else bytes
                }
            }
        }
    }

    private fun changeNoOf(accountId: Long, clientId: String): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT change_no FROM transcriptions WHERE account_id = $accountId AND client_id = '$clientId'").single()
    }

    private fun deletedOf(accountId: Long, clientId: String): Int = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "no row for $clientId" }
                rows.getInt(1)
            }
        }
    }

    private fun storedAtMsOf(accountId: Long, clientId: String): Long = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT stored_at_ms FROM transcriptions WHERE account_id = ? AND client_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "no row for $clientId" }
                rows.getLong(1)
            }
        }
    }

    private fun encryptedOf(accountId: Long, clientId: String): String = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT encrypted FROM transcriptions WHERE account_id = ? AND client_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "no row for $clientId" }
                rows.getString(1)
            }
        }
    }

    private fun setKeyVersion(accountId: Long, version: Int) {
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { statement ->
                statement.setInt(1, version)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    private fun setPrevWrappedDek(accountId: Long, bytes: ByteArray) {
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE accounts SET prev_wrapped_dek = ? WHERE id = ?").use { statement ->
                statement.setBytes(1, bytes)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    // 1. A row exactly at the cutoff (stored_at_ms == nowMs - ttlMs) is kept (strict <).
    @Test
    fun `expireDueOn keeps a row exactly at the cutoff`() {
        push(alice, 0)
        val head = changeSeqOf(alice)
        val marked = expire(alice, T0 + TTL, 10)
        assertEquals("return 0", 0, marked)
        assertEquals("row still live", 0, deletedOf(alice, clientId(0)))
        assertEquals("change_seq unchanged", head, changeSeqOf(alice))
    }

    // 2. A row one ms older than the cutoff is expired.
    @Test
    fun `expireDueOn expires a row one ms older than the cutoff`() {
        push(alice, 0)
        val head = changeSeqOf(alice)
        val marked = expire(alice, NOW_MS_EXPIRED, 10)
        assertEquals("return 1", 1, marked)
        assertEquals("row deleted", 1, deletedOf(alice, clientId(0)))
        assertEquals("body cleared", "-", encryptedOf(alice, clientId(0)))
        assertEquals("stored_at_ms = nowMs", NOW_MS_EXPIRED, storedAtMsOf(alice, clientId(0)))
        assertEquals("change_no = head + 1", head + 1, changeNoOf(alice, clientId(0)))
        assertEquals("change_seq = head + 1", head + 1, changeSeqOf(alice))
    }

    // 3. Rows newer than the cutoff are untouched.
    @Test
    fun `expireDueOn live only changes nothing`() {
        clock.set(Instant.ofEpochMilli(T0 + 1))
        push(alice, 0)
        push(alice, 1)
        val head = changeSeqOf(alice)
        val pt = purgedThroughOf(alice)
        val marked = expire(alice, T0 + TTL, 10)
        assertEquals("return 0", 0, marked)
        assertEquals("both live", 2, liveRowsOf(alice))
        assertEquals("change_seq unchanged", head, changeSeqOf(alice))
        assertEquals("purged_through unchanged", pt, purgedThroughOf(alice))
    }

    // 4. batch cap respected, oldest first, the rest left for the next call.
    @Test
    fun `expireDueOn respects the batch cap and leaves the rest`() {
        push(alice, 0)
        clock.advance(Duration.ofMillis(1))
        push(alice, 1)
        clock.advance(Duration.ofMillis(1))
        push(alice, 2)
        clock.advance(Duration.ofMillis(1))
        push(alice, 3)
        clock.advance(Duration.ofMillis(1))
        push(alice, 4)
        val head = changeSeqOf(alice)
        val marked = expire(alice, T0 + 2 * TTL, 2)
        assertEquals("return 2", 2, marked)
        assertEquals("3 live rows remain", 3, liveRowsOf(alice))
        assertEquals("change_seq = head + 2", head + 2, changeSeqOf(alice))
        assertEquals("clientId(0) expired", 1, deletedOf(alice, clientId(0)))
        assertEquals("clientId(1) expired", 1, deletedOf(alice, clientId(1)))
        assertEquals("clientId(2) live", 0, deletedOf(alice, clientId(2)))
        assertEquals("clientId(3) live", 0, deletedOf(alice, clientId(3)))
        assertEquals("clientId(4) live", 0, deletedOf(alice, clientId(4)))
    }

    // 18. batch cap expires the OLDEST by stored_at_ms, not by insertion/client-id order.
    //     Pushes clientId(0), (1), (2) in that order, then reverses stored_at_ms by SQL so
    //     clientId(2) is OLDEST and clientId(0) is NEWEST, all older than the TTL. expire(alice,
    //     T0 + 2 * TTL, 2) must expire clientId(2) and clientId(1) (oldest first) and keep
    //     clientId(0) (the newest) live. Without ORDER BY stored_at_ms, id the query returns
    //     insertion order (clientId(0), clientId(1)) and the newest row dies instead - the
    //     ORDER BY mutant is caught red.
    @Test
    fun `expireDueOn expires oldest by stored_at_ms not insertion order`() {
        push(alice, 0)
        clock.advance(Duration.ofMillis(1))
        push(alice, 1)
        clock.advance(Duration.ofMillis(1))
        push(alice, 2)
        val head = changeSeqOf(alice)
        // Reverse ages by stored_at_ms: clientId(2) oldest, clientId(1) middle, clientId(0) newest.
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE transcriptions SET stored_at_ms = ? WHERE account_id = ? AND client_id = ?").use { s ->
                s.setLong(1, T0); s.setLong(2, alice); s.setString(3, clientId(2)); s.executeUpdate()
            }
            connection.prepareStatement("UPDATE transcriptions SET stored_at_ms = ? WHERE account_id = ? AND client_id = ?").use { s ->
                s.setLong(1, T0 + 1); s.setLong(2, alice); s.setString(3, clientId(1)); s.executeUpdate()
            }
            connection.prepareStatement("UPDATE transcriptions SET stored_at_ms = ? WHERE account_id = ? AND client_id = ?").use { s ->
                s.setLong(1, T0 + 2); s.setLong(2, alice); s.setString(3, clientId(0)); s.executeUpdate()
            }
        }
        val marked = expire(alice, T0 + 2 * TTL, 2)
        assertEquals("return 2", 2, marked)
        assertEquals("oldest row deleted", 1, deletedOf(alice, clientId(2)))
        assertEquals("middle row deleted", 1, deletedOf(alice, clientId(1)))
        assertEquals("newest row live", 0, deletedOf(alice, clientId(0)))
        assertEquals("change_seq = head + 2", head + 2, changeSeqOf(alice))
    }

    // 5. Change numbers advance by the count of expired rows.
    @Test
    fun `expireDueOn change numbers advance by the count`() {
        push(alice, 0)
        push(alice, 1)
        push(alice, 2)
        val head = changeSeqOf(alice)
        val marked = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("return 3", 3, marked)
        assertEquals("change_seq = head + 3", head + 3, changeSeqOf(alice))
    }

    // 6. purged_through is never raised by the TTL job.
    @Test
    fun `expireDueOn purged_through unchanged`() {
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE accounts SET purged_through = 1000000000 WHERE id = ?").use { s ->
                s.setLong(1, alice)
                s.executeUpdate()
            }
        }
        push(alice, 0)
        val marked = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("return 1", 1, marked)
        assertEquals("purged_through still 10^9", 1000000000L, purgedThroughOf(alice))
    }

    // 7. A second call with nothing due changes nothing.
    @Test
    fun `expireDueOn second call with nothing due changes nothing`() {
        push(alice, 0)
        val first = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("first expires 1", 1, first)
        assertEquals("one delete record", 1L, deletedRowsOf(alice))
        assertEquals("no live rows", 0L, liveRowsOf(alice))
        val headAfterFirst = changeSeqOf(alice)
        val second = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("second returns 0", 0, second)
        assertEquals("still one delete record", 1L, deletedRowsOf(alice))
        assertEquals("no live rows", 0L, liveRowsOf(alice))
        assertEquals("change_seq unchanged after second", headAfterFirst, changeSeqOf(alice))
    }

    // 8. One account never touches another.
    @Test
    fun `expireDueOn one account never touches another`() {
        val bob = TokenFixtures.register(temp, "bob", 2).accountId
        push(bob, 0)
        push(alice, 0)
        val marked = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("return 1 (alice only)", 1, marked)
        assertEquals("alice row deleted", 1, deletedOf(alice, clientId(0)))
        assertEquals("bob row untouched", 0, deletedOf(bob, clientId(0)))
    }

    // 9. Removing the last old row clears prev_wrapped_dek.
    @Test
    fun `expireDueOn clears prev_wrapped_dek when the batch removes the last old row`() {
        push(alice, 0)  // row at key_version 0, account at key_version 0
        setKeyVersion(alice, 1)
        setPrevWrappedDek(alice, KeyPatterns.prevWrappedDek())
        assertArrayEquals("prev held before", KeyPatterns.prevWrappedDek(), prevWrappedDekOf(alice)!!)
        val marked = expire(alice, T0 + TTL + 1, 10)
        assertEquals("return 1", 1, marked)
        assertNull("prev_wrapped_dek cleared", prevWrappedDekOf(alice))
    }

    // 10. Removing only new-key rows keeps prev_wrapped_dek.
    @Test
    fun `expireDueOn keeps prev_wrapped_dek when batch removes only new-key rows`() {
        clock.set(Instant.ofEpochMilli(T0 + 200))
        push(alice, 0)  // old row (key_version 0) at T0+200, holds prev_wrapped_dek
        setKeyVersion(alice, 1)
        setPrevWrappedDek(alice, KeyPatterns.prevWrappedDek())
        clock.set(Instant.ofEpochMilli(T0))
        pushWithVersion(alice, 1, 1)  // new row (key_version 1) at T0, will be expired
        val marked = expire(alice, T0 + TTL + 150, 10)  // cutoff = T0+150
        assertEquals("return 1", 1, marked)
        assertEquals("old row live", 0, deletedOf(alice, clientId(0)))
        assertEquals("new row deleted", 1, deletedOf(alice, clientId(1)))
        assertArrayEquals("prev_wrapped_dek kept", KeyPatterns.prevWrappedDek(), prevWrappedDekOf(alice)!!)
    }

    // 11. batchCap below 1 throws IllegalArgumentException and writes nothing.
    @Test
    fun `expireDueOn refuses a batch cap below one`() {
        push(alice, 0)
        val head = changeSeqOf(alice)
        TestDatabases.expectFailure<IllegalArgumentException>("batch cap 0") {
            expire(alice, T0 + 2 * TTL, 0)
        }
        TestDatabases.expectFailure<IllegalArgumentException>("batch cap -3") {
            expire(alice, T0 + 2 * TTL, -3)
        }
        assertEquals("nothing written", head, changeSeqOf(alice))
        assertEquals("row still live", 0, deletedOf(alice, clientId(0)))
    }

    // Constant pin: a changed TTL constant dies here.
    @Test
    fun `RETENTION_TTL_MS is ninety days in milliseconds`() {
        assertEquals("sync-api: TTL constant pins 90 days", 7_776_000_000L, TranscriptionPurge.RETENTION_TTL_MS)
    }

    // 12. Route level: an expired id then answers 204 with no write on a re-push.
    @Test
    fun `expireDueOn then re-push answers 204 no write`() {
        val cid = clientId(0)
        val pushBody = SyncRouteTestSupport.pushBody(cid, "0", SyncRouteTestSupport.ENCRYPTED)
        clock.set(Instant.ofEpochMilli(T0))
        val first = SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, pushBody)
        assertEquals("first push status 204", HttpStatusCode.NoContent, first.status)

        val head = changeSeqOf(alice)
        val marked = expire(alice, NOW_MS_EXPIRED, 10)
        assertEquals("expire returns 1", 1, marked)

        val second = SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, pushBody)
        assertEquals("re-push status 204", HttpStatusCode.NoContent, second.status)
        assertEquals("row still deleted", 1, deletedOf(alice, cid))
        assertEquals("change_no unchanged", head + 1, changeNoOf(alice, cid))
        assertEquals("stored_at_ms unchanged", NOW_MS_EXPIRED, storedAtMsOf(alice, cid))
    }

    // 13. Route level: a device pulling with since sees the expired row as a delete record.
    @Test
    fun `pull since shows expired rows as delete records`() {
        val cid = clientId(0)
        val pushBody = SyncRouteTestSupport.pushBody(cid, "0", SyncRouteTestSupport.ENCRYPTED)
        clock.set(Instant.ofEpochMilli(T0))
        val first = SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, pushBody)
        assertEquals("first push status 204", HttpStatusCode.NoContent, first.status)

        val marked = expire(alice, NOW_MS_EXPIRED, 10)
        assertEquals("expire returns 1", 1, marked)

        val changeNo = changeNoOf(alice, cid)
        val since = "$alice-0"
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, aliceToken, since)

        assertEquals("pull status 200", HttpStatusCode.OK, reply.status)
        val expected =
            """{"items":[{"client_id":"$cid","change":$changeNo,"deleted":true,"stored_at":"$STORED_AT_EXPIRED"}],"next":"$alice-$changeNo","more":false}"""
        assertEquals("pull body", expected, reply.body)
    }

    // 14. A row that is already a delete record is never touched again (kills a mutant that drops `deleted = 0`).
    @Test
    fun `expireDueOn leaves an already deleted row alone`() {
        push(alice, 0)
        val reply = SyncRouteTestSupport.deleteTranscription(tokenStore, store, aliceToken, clientId(0))
        assertEquals("sync-api: setup delete status", HttpStatusCode.NoContent, reply.status)
        val head = changeSeqOf(alice)
        val marked = expire(alice, T0 + 2 * TTL, 10)
        assertEquals("sync-api: nothing to expire", 0, marked)
        assertEquals("sync-api: change_seq unchanged", head, changeSeqOf(alice))
        assertEquals("sync-api: change_no unchanged", head, changeNoOf(alice, clientId(0)))
        assertEquals("sync-api: stored_at_ms unchanged", T0, storedAtMsOf(alice, clientId(0)))
    }

    // 15. ttlMs below one throws IllegalArgumentException and writes nothing.
    @Test
    fun `expireDueOn refuses a ttl below one`() {
        push(alice, 0)
        val head = changeSeqOf(alice)
        TestDatabases.expectFailure<IllegalArgumentException>("ttl 0") {
            expire(alice, T0 + TTL + 1, 10, ttlMs = 0)
        }
        TestDatabases.expectFailure<IllegalArgumentException>("ttl -100") {
            expire(alice, T0 + TTL + 1, 10, ttlMs = -100)
        }
        assertEquals("nothing written", head, changeSeqOf(alice))
        assertEquals("row still live", 0, deletedOf(alice, clientId(0)))
        assertEquals("no delete record", 0L, deletedRowsOf(alice))
    }

    // 16. ttlMs = the smallest Long (MIN_VALUE) throws IllegalArgumentException and writes nothing.
    @Test
    fun `expireDueOn refuses the smallest possible ttl`() {
        push(alice, 0)
        val head = changeSeqOf(alice)
        TestDatabases.expectFailure<IllegalArgumentException>("ttl Long.MIN_VALUE") {
            expire(alice, T0 + TTL + 1, 10, ttlMs = Long.MIN_VALUE)
        }
        assertEquals("nothing written", head, changeSeqOf(alice))
        assertEquals("row still live", 0, deletedOf(alice, clientId(0)))
        assertEquals("no delete record", 0L, deletedRowsOf(alice))
    }

    // 17. ttlMs = 1: a row older than 1 ms is expired, a row exactly at the cutoff is kept.
    @Test
    fun `expireDueOn accepts ttlMs of 1 and expires old rows`() {
        push(alice, 0)
        clock.advance(Duration.ofMillis(99))
        push(alice, 1)
        val head = changeSeqOf(alice)
        val marked = expire(alice, T0 + 100, 10, ttlMs = 1)
        assertEquals("return 1", 1, marked)
        assertEquals("older row deleted", 1, deletedOf(alice, clientId(0)))
        assertEquals("cutoff row kept", 0, deletedOf(alice, clientId(1)))
        assertEquals("change_seq = head + 1", head + 1, changeSeqOf(alice))
    }
}
