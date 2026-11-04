package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.reset.CompleteResetRequest
import dev.breaker.server.syncapi.reset.CompleteResetResult
import dev.breaker.server.syncapi.reset.ResetAccountResult
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A hand-off code is never made for a pending account, and one that exists on it is spent and refused. */
internal class HandoffStorePendingTest : TempDatabaseTest() {

    private val clock = MutableClock(HandoffFixtures.START)
    private val challenge = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)

    // 43 characters of the token alphabet, the same shape as the fixture secret.
    private val codeText = "Codetx-".repeat(6) + "C"

    private lateinit var temp: TempDatabase
    private lateinit var accounts: AccountStore
    private lateinit var tokenStore: TokenStore
    private lateinit var store: HandoffStore
    private lateinit var resetStore: ResetStore

    // The first account is the admin, the second is a user.
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp, clock = clock)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        resetStore = ResetStore(accounts, tokenStore)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    // Returns the reset code of the account, which the account needs to finish its reset.
    private fun resetAccount(name: String): String {
        val result = runBlocking { resetStore.resetAccount(name) }
        if (result !is ResetAccountResult.Reset) {
            throw AssertionError("sync-api: expected Reset but got $result")
        }
        return result.code
    }

    private fun start(accountId: Long): StartResult =
        runBlocking { store.start(accountId, challenge, HandoffFixtures.SEALED_PACKAGE_BYTES) }

    private fun redeem(code: String): RedeemResult = runBlocking { store.redeem(code, HandoffFixtures.SECRET, null) }

    // A row for a code text the test knows, as if start had written it a moment before the reset.
    private fun plantRow(accountId: Long) {
        val hash = MessageDigest.getInstance("SHA-256").digest(codeText.toByteArray(StandardCharsets.US_ASCII))
        val createdMs = HandoffFixtures.START.toEpochMilli()
        HandoffFixtures.insertRawRow(temp, hash, accountId, createdMs, createdMs + HandoffStore.CODE_LIFETIME.toMillis())
    }

    private fun finishReset(name: String, resetCode: String) {
        val verifier = runBlocking { accounts.hashVerifier(AccountFixtures.verifier(77)) }
        val request = CompleteResetRequest(ByteArray(16) { i -> (i * 13 + 25).toByte() }, KdfParams(131072, 4, 2), 1, verifier)
        val result = runBlocking { resetStore.complete(name, resetCode, request) }
        assertTrue("sync-api: expected Completed but got $result", result is CompleteResetResult.Completed)
    }

    @Test
    fun `a start for a pending account gives NoSuchAccount and stores no row`() {
        resetAccount("alice")
        assertSame("sync-api: a pending account must get NoSuchAccount", StartResult.NoSuchAccount, start(alice))
        assertEquals("sync-api: no row for the pending account", 0L, HandoffFixtures.rowCountFor(temp, alice))
        assertEquals("sync-api: no row at all", 0L, HandoffFixtures.rowCount(temp))
    }

    @Test
    fun `a start for an account that is not pending still stores a row after another account was reset`() {
        resetAccount("alice")
        assertSame("sync-api: the pending account", StartResult.NoSuchAccount, start(alice))
        val started = start(bob)
        assertTrue("sync-api: expected Started but got $started", started is StartResult.Started)
        assertEquals("sync-api: one row for the other account", 1L, HandoffFixtures.rowCountFor(temp, bob))
        assertEquals("sync-api: still no row for the pending account", 0L, HandoffFixtures.rowCountFor(temp, alice))
    }

    @Test
    fun `a code made before the reset is wiped by the reset and no longer redeems`() {
        val first = start(alice)
        val other = start(bob)
        assertTrue("sync-api: expected Started but got $first", first is StartResult.Started)
        assertTrue("sync-api: expected Started but got $other", other is StartResult.Started)
        assertEquals("sync-api: one row before the reset", 1L, HandoffFixtures.rowCountFor(temp, alice))
        resetAccount("alice")
        assertEquals("sync-api: the reset must delete the row", 0L, HandoffFixtures.rowCountFor(temp, alice))
        assertEquals("sync-api: the reset must leave the other account's row", 1L, HandoffFixtures.rowCountFor(temp, bob))
        val code = (first as StartResult.Started).code
        assertSame("sync-api: the wiped code must be refused", RedeemResult.Refused, redeem(code))
        assertEquals("sync-api: no session may be made", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `a code row on a pending account is spent and refused and makes no session`() {
        resetAccount("alice")
        plantRow(alice)
        assertEquals("sync-api: the planted row must exist before the redeem", 1L, HandoffFixtures.rowCountFor(temp, alice))
        assertSame("sync-api: a code on a pending account must be refused", RedeemResult.Refused, redeem(codeText))
        assertEquals("sync-api: the code row must be gone", 0L, HandoffFixtures.rowCountFor(temp, alice))
        assertEquals("sync-api: no session may be made", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `the same code answers Refused still after the reset is completed`() {
        val resetCode = resetAccount("alice")
        plantRow(alice)
        assertSame("sync-api: the first redeem", RedeemResult.Refused, redeem(codeText))
        finishReset("alice", resetCode)
        val before = TokenFixtures.tokenRowCount(temp)
        assertEquals("sync-api: the completed reset signs the person in with one session", 1L, before)
        assertSame("sync-api: the same code after the reset", RedeemResult.Refused, redeem(codeText))
        assertEquals("sync-api: no row may come back", 0L, HandoffFixtures.rowCountFor(temp, alice))
        assertEquals("sync-api: the second redeem must make no session", before, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `a code row on an account that is not pending still redeems`() {
        resetAccount("alice")
        plantRow(bob)
        val result = redeem(codeText)
        if (result !is RedeemResult.Redeemed) {
            throw AssertionError("sync-api: expected Redeemed but got $result")
        }
        assertEquals("sync-api: the session belongs to the other account", bob, result.accountId)
        assertEquals("sync-api: the code row must be spent", 0L, HandoffFixtures.rowCountFor(temp, bob))
        assertEquals("sync-api: one session", 1L, TokenFixtures.tokenRowCount(temp))
    }
}
