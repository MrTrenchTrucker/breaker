package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** What redeem returns, deletes and inserts, and that every failure is the one Refused object. */
internal class HandoffStoreRedeemTest : TempDatabaseTest() {

    private val clock = MutableClock(HandoffFixtures.START)
    private val challenge = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val fiveMinutesMs = 5L * 60L * 1000L

    // 43 characters of the token alphabet that differ from the secret of the fixtures.
    private val wrongSecret = "Wrong-".repeat(7) + "W"

    // The first registered account is the ADMIN, the second is a USER.
    private class World(
        val temp: TempDatabase,
        val tokenStore: TokenStore,
        val store: HandoffStore,
        val admin: Long,
        val user: Long,
    )

    private class TokenRow(
        val id: Long,
        val kind: String,
        val accountId: Long,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    private fun world(): World {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val first = TokenFixtures.register(temp, "alice", 1)
        val second = TokenFixtures.register(temp, "bob", 2)
        return World(temp, tokenStore, store, first.accountId, second.accountId)
    }

    private fun startCode(w: World, accountId: Long): String {
        val result = runBlocking { w.store.start(accountId, challenge, HandoffFixtures.SEALED_PACKAGE_BYTES) }
        if (result !is StartResult.Started) {
            throw AssertionError("sync-api: expected Started but got $result")
        }
        return result.code
    }

    private fun redeem(w: World, code: String, secret: String, deviceName: String?): RedeemResult =
        runBlocking { w.store.redeem(code, secret, deviceName) }

    private fun redeemed(result: RedeemResult): RedeemResult.Redeemed {
        if (result !is RedeemResult.Redeemed) {
            throw AssertionError("sync-api: expected Redeemed but got $result")
        }
        return result
    }

    private fun setKeyVersion(temp: TempDatabase, accountId: Long, keyVersion: Int) {
        inTransaction(temp) { connection -> connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { statement -> statement.setInt(1, keyVersion); statement.setLong(2, accountId); statement.executeUpdate() } }
    }

    private fun tokenRows(temp: TempDatabase): List<TokenRow> =
        inTransaction(temp) { connection ->
            val sql = "SELECT id, kind, account_id, created_at_ms, expires_at_ms FROM tokens ORDER BY id"
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows ->
                    val values = ArrayList<TokenRow>()
                    while (rows.next()) {
                        values.add(TokenRow(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)))
                    }
                    values
                }
            }
        }

    @Test
    fun `a redeem returns the session, role, key version, package, username and account and uses the code up`() {
        val w = world()
        setKeyVersion(w.temp, w.admin, 3)
        val code = startCode(w, w.admin)
        val startMs = HandoffFixtures.START.toEpochMilli()
        val result = redeemed(redeem(w, code, HandoffFixtures.SECRET, "Pixel 8"))
        assertEquals("sync-api: length of the session token", 43, result.sessionToken.length)
        assertFalse("sync-api: the session token must not be the code", result.sessionToken == code)
        assertEquals("sync-api: role of the first account", Role.ADMIN, result.role)
        assertEquals("sync-api: key version of the account", 3, result.keyVersion)
        assertEquals("sync-api: length of the package", 113, result.sealedPackage.size)
        assertArrayEquals("sync-api: the package must be the bytes stored at start", HandoffFixtures.SEALED_PACKAGE_BYTES, result.sealedPackage)
        assertEquals("sync-api: username", "alice", result.username)
        assertEquals("sync-api: account id", w.admin, result.accountId)
        assertEquals("sync-api: the hand-off row must be deleted", 0L, HandoffFixtures.rowCount(w.temp))
        val rows = tokenRows(w.temp)
        assertEquals("sync-api: exactly one token row must be inserted", 1, rows.size)
        val row = rows.single()
        assertEquals("sync-api: kind of the new row", TokenKind.SESSION.dbValue, row.kind)
        assertEquals("sync-api: account of the new row", w.admin, row.accountId)
        assertEquals("sync-api: created time of the new row", startMs, row.createdAtMs)
        assertEquals("sync-api: the session expires 30 days after the redeem", startMs + Duration.ofDays(30).toMillis(), row.expiresAtMs)
        assertEquals("sync-api: device name of the new row", "Pixel 8", HandoffFixtures.tokenDeviceName(w.temp, row.id))
        val resolved = checkNotNull(runBlocking { w.tokenStore.resolve(result.sessionToken) })
        assertEquals("sync-api: the issued token must be the new row", row.id, resolved.tokenId)
        assertEquals("sync-api: the issued token must belong to the account", w.admin, resolved.accountId)
        assertEquals("sync-api: the issued token must be a session", TokenKind.SESSION, resolved.kind)
    }

    @Test
    fun `a redeem for the second account reports role user and its own username and id`() {
        val w = world()
        val code = startCode(w, w.user)
        val result = redeemed(redeem(w, code, HandoffFixtures.SECRET, null))
        assertEquals("sync-api: role of the second account", Role.USER, result.role)
        assertEquals("sync-api: username of the second account", "bob", result.username)
        assertEquals("sync-api: id of the second account", w.user, result.accountId)
    }

    @Test
    fun `a redeem without a device name inserts a session with no device name`() {
        val w = world()
        val code = startCode(w, w.admin)
        redeemed(redeem(w, code, HandoffFixtures.SECRET, null))
        val rows = tokenRows(w.temp)
        assertEquals("sync-api: exactly one token row must be inserted", 1, rows.size)
        assertNull("sync-api: a null device name must stay NULL", HandoffFixtures.tokenDeviceName(w.temp, rows.single().id))
    }

    @Test
    fun `a second redeem of the same code is Refused`() {
        val w = world()
        val code = startCode(w, w.admin)
        redeemed(redeem(w, code, HandoffFixtures.SECRET, null))
        val second = redeem(w, code, HandoffFixtures.SECRET, null)
        assertSame("sync-api: a used code must be refused", RedeemResult.Refused, second)
        assertEquals("sync-api: a refused redeem must not insert a session", 1L, TokenFixtures.tokenRowCount(w.temp))
    }

    @Test
    fun `a wrong secret is Refused and the same code then redeems with the right secret`() {
        val w = world()
        val code = startCode(w, w.admin)
        val wrong = redeem(w, code, wrongSecret, null)
        assertSame("sync-api: a wrong secret must be refused", RedeemResult.Refused, wrong)
        assertEquals("sync-api: a wrong secret must leave the row", 1L, HandoffFixtures.rowCount(w.temp))
        assertEquals("sync-api: a wrong secret must not insert a session", 0L, TokenFixtures.tokenRowCount(w.temp))
        val right = redeemed(redeem(w, code, HandoffFixtures.SECRET, null))
        assertEquals("sync-api: the right secret must redeem the same code", w.admin, right.accountId)
        assertEquals("sync-api: the redeem must delete the row", 0L, HandoffFixtures.rowCount(w.temp))
        assertEquals("sync-api: the redeem must insert one session", 1L, TokenFixtures.tokenRowCount(w.temp))
    }

    @Test
    fun `a code is dead at the instant of its expiry`() {
        val w = world()
        val code = startCode(w, w.admin)
        clock.set(HandoffFixtures.START.plusMillis(fiveMinutesMs))
        val result = redeem(w, code, HandoffFixtures.SECRET, null)
        assertSame("sync-api: a code at its expiry instant must be refused", RedeemResult.Refused, result)
        assertEquals("sync-api: a refused redeem must not insert a session", 0L, TokenFixtures.tokenRowCount(w.temp))
    }

    @Test
    fun `a code is still live one millisecond before its expiry`() {
        val w = world()
        val code = startCode(w, w.admin)
        clock.set(HandoffFixtures.START.plusMillis(fiveMinutesMs - 1L))
        val result = redeem(w, code, HandoffFixtures.SECRET, null)
        assertEquals("sync-api: a live code must redeem", w.admin, redeemed(result).accountId)
    }

    @Test
    fun `every kind of failure gives the same Refused object and consumes nothing`() {
        val w = world()
        val code = startCode(w, w.admin)
        val cases = listOf(
            "an unknown code" to redeem(w, "Q".repeat(43), HandoffFixtures.SECRET, null),
            "a code of the wrong length" to redeem(w, "Q".repeat(42), HandoffFixtures.SECRET, null),
            "a code with a bad character" to redeem(w, "Q".repeat(42) + "!", HandoffFixtures.SECRET, null),
            "an empty code" to redeem(w, "", HandoffFixtures.SECRET, null),
            "a secret of the wrong length" to redeem(w, code, HandoffFixtures.SECRET.dropLast(1), null),
            "a secret with a bad character" to redeem(w, code, HandoffFixtures.SECRET.dropLast(1) + "!", null),
            "an empty secret" to redeem(w, code, "", null),
            "a wrong secret" to redeem(w, code, wrongSecret, null),
        )
        for ((label, result) in cases) {
            assertSame("sync-api: $label must give the one Refused object", RedeemResult.Refused, result)
        }
        assertEquals("sync-api: no failure may delete the row", 1L, HandoffFixtures.rowCount(w.temp))
        assertEquals("sync-api: no failure may insert a session", 0L, TokenFixtures.tokenRowCount(w.temp))
        val control = redeemed(redeem(w, code, HandoffFixtures.SECRET, null))
        assertEquals("sync-api: the code must still redeem after every failure", w.admin, control.accountId)
    }
}
