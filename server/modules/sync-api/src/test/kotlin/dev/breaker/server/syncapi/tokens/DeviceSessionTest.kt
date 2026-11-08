package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Types
import java.time.Duration

internal class DeviceSessionTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TokenStore
    private val clock = MutableClock(TokenFixtures.START)

    @Before
    fun setUp() {
        temp = openTemp()
        store = TokenFixtures.tokenStore(temp, clock)
    }

    private fun account(): Long = TokenFixtures.register(temp, "alice", 1).accountId

    private fun freshSession(accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    private fun insertRow(
        accountId: Long,
        createdMs: Long,
        expiresMs: Long?,
        name: String?,
        seed: Byte = 0,
    ): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, ByteArray(32) { seed })
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, expiresMs)
                if (name == null) statement.setNull(5, Types.VARCHAR) else statement.setString(5, name)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: the row id could not be read back" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    @Test
    fun `a live session is returned`() {
        val accountId = account()
        val id = freshSession(accountId)
        val now = TokenFixtures.START.toEpochMilli()
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals("one live row", listOf(DeviceSession(id, null, now)), rows)
    }

    @Test
    fun `a revoked session is not listed`() {
        val accountId = account()
        val id = freshSession(accountId)
        assertTrue("the revoke must land", runBlocking { store.revokeSession(id) })
        assertTrue("the list is empty", runBlocking { store.deviceSessions(accountId) }.isEmpty())
    }

    @Test
    fun `an expired session is not listed`() {
        val accountId = account()
        val id = freshSession(accountId)
        TokenFixtures.expireSession(temp, id)
        assertTrue("the list is empty", runBlocking { store.deviceSessions(accountId) }.isEmpty())
    }

    @Test
    fun `a session past the 180 day cap is not listed`() {
        val accountId = account()
        val now = TokenFixtures.START.toEpochMilli()
        val id = freshSession(accountId)
        insertRow(accountId, now, now + Duration.ofDays(180).toMillis() + 1, null)
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals("only the live row", listOf(DeviceSession(id, null, now)), rows)
    }

    @Test
    fun `another account's sessions are never returned`() {
        val alice = account()
        val bob = TokenFixtures.register(temp, "bob", 2).accountId
        val aliceId = freshSession(alice)
        val bobId = freshSession(bob)
        val now = TokenFixtures.START.toEpochMilli()
        val aliceRows = runBlocking { store.deviceSessions(alice) }
        val bobRows = runBlocking { store.deviceSessions(bob) }
        assertEquals("alice sees her own row only", listOf(DeviceSession(aliceId, null, now)), aliceRows)
        assertEquals("bob sees his own row only", listOf(DeviceSession(bobId, null, now)), bobRows)
    }

    @Test
    fun `last seen is the expiry minus 30 days and never before the creation`() {
        val accountId = account()
        val now = TokenFixtures.START.toEpochMilli()
        val early = insertRow(accountId, now, now + Duration.ofDays(60).toMillis(), null, 1)
        val late = insertRow(
            accountId,
            now + Duration.ofDays(60).toMillis(),
            now + Duration.ofDays(70).toMillis(),
            null,
            2,
        )
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals(
            "last seen follows the rule",
            listOf(
                DeviceSession(late, null, now + Duration.ofDays(60).toMillis()),
                DeviceSession(early, null, now + Duration.ofDays(30).toMillis()),
            ),
            rows,
        )
    }

    @Test
    fun `a null expiry reads the creation time`() {
        val accountId = account()
        val now = TokenFixtures.START.toEpochMilli()
        val id = insertRow(accountId, now, null, null)
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals("creation time", listOf(DeviceSession(id, null, now)), rows)
    }

    @Test
    fun `51 live sessions return 50`() {
        val accountId = account()
        val ids = (1..51).map { freshSession(accountId) }
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals("fifty rows", 50, rows.size)
        assertEquals("newest first", ids.reversed().dropLast(1), rows.map { it.id })
        assertFalse("the oldest session is dropped", ids.first() in rows.map { it.id })
    }

    @Test
    fun `the rows are newest first at the store`() {
        val accountId = account()
        val first = freshSession(accountId)
        val second = freshSession(accountId)
        val third = freshSession(accountId)
        val now = TokenFixtures.START.toEpochMilli()
        val rows = runBlocking { store.deviceSessions(accountId) }
        assertEquals(
            "newest first",
            listOf(
                DeviceSession(third, null, now),
                DeviceSession(second, null, now),
                DeviceSession(first, null, now),
            ),
            rows,
        )
    }
}
