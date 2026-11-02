package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class HandoffStoreRedeemGuardTest {

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    private companion object {
        const val SECRET: String = "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "Secret-" + "S"
    }

    // start a code, then redeem with a bad device name: IllegalArgumentException and the row is
    // still there; redeem with the right secret and no name succeeds.
    @Test
    fun `redeem refuses a 65-character device name and still redeems with no name`() {
        val temp = TestDatabases.openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val accountId = insertAccount(temp)
        val store = HandoffStore(temp.db, tokenStore, clock, SecureRandom())
        runRedeemGuardScenario(temp, store, accountId, "n".repeat(65))
    }

    @Test
    fun `redeem refuses a zero-character device name and still redeems with no name`() {
        val temp = TestDatabases.openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val accountId = insertAccount(temp)
        val store = HandoffStore(temp.db, tokenStore, clock, SecureRandom())
        runRedeemGuardScenario(temp, store, accountId, "")
    }

    private fun runRedeemGuardScenario(temp: TempDatabase, store: HandoffStore, accountId: Long, badName: String?) {
        val challenge = challengeOf(SECRET)
        val packageBytes = ByteArray(113) { index -> (index * 3 + 1).toByte() }

        // Start a code for the account.
        val code = runBlockingStart(store, accountId, challenge, packageBytes)

        // Redeem with the bad device name: IllegalArgumentException, code not consumed.
        val thrown = assertThrows("sync-api: invalid device name", IllegalArgumentException::class.java) {
            runBlockingRedeem(store, code, SECRET, badName)
        }
        assertEquals("sync-api: invalid device name", thrown.message)

        // The row is still there: redeem with the right secret and no name succeeds.
        val result = runBlockingRedeem(store, code, SECRET, null)
        assertEquals("sync-api: redeemed with no name after a refused name",
            "Redeemed", result::class.simpleName)
    }

    private fun runBlockingStart(store: HandoffStore, accountId: Long, challenge: String, packageBytes: ByteArray): String {
        var result: String? = null
        runBlocking {
            val started = store.start(accountId, challenge, packageBytes)
            if (started is StartResult.Started) {
                result = started.code
            } else {
                throw AssertionError("sync-api: start did not return Started, got $started")
            }
        }
        return result!!
    }

    private fun runBlockingRedeem(store: HandoffStore, code: String, secret: String, deviceName: String?): RedeemResult {
        var result: RedeemResult? = null
        runBlocking {
            result = store.redeem(code, secret, deviceName)
        }
        return result!!
    }

    private fun insertAccount(temp: TempDatabase): Long {
        var id: Long? = null
        runBlocking {
            temp.db.transaction { connection ->
                TestDatabases.insertAccountRow(
                    connection,
                    TestDatabases.validAccountRow(mapOf("username" to "alice", "username_lower" to "alice")),
                )
                id = TestDatabases.longs(connection, "SELECT id FROM accounts WHERE username_lower = 'alice'").single()
            }
        }
        return id!!
    }

    private fun challengeOf(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
