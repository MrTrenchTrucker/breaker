package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.accounts.VerifyResult
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.reset.ResetAccountResult
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A session or an agent token is never made for an account that a reset marked pending. */
internal class TokenStoreIssuePendingTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var accounts: AccountStore
    private lateinit var store: TokenStore
    private lateinit var resetStore: ResetStore

    // The first account is the admin, the other two are users. The third one has keys.
    private var alice: Long = 0L
    private var bob: Long = 0L
    private var carol: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        val clock = MutableClock(TokenFixtures.START)
        accounts = AccountFixtures.newStore(temp, clock = clock)
        store = TokenFixtures.tokenStore(temp, clock)
        resetStore = ResetStore(accounts, store)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
        carol = TokenFixtures.register(temp, "carol", 3).accountId
        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val stored = runBlocking { accounts.storeKeys(carol, keys) }
        assertTrue("sync-api: the keys of the third account must be stored, got $stored", stored is StoreKeysResult.Stored)
    }

    private fun resetAccount(name: String) {
        val result = runBlocking { resetStore.resetAccount(name) }
        assertTrue("sync-api: expected Reset but got $result", result is ResetAccountResult.Reset)
    }

    private fun rowsOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM tokens WHERE account_id = $accountId").single()
        }

    private fun pendingOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT reset_pending FROM accounts WHERE id = $accountId").single()
        }

    private fun issueAgent(owner: Long, scope: AgentScope): IssueResult =
        runBlocking { store.issueAgent(owner, scope, "bot", null) }

    private fun assertNothingStored(case: String, accountId: Long, attempt: () -> IssueResult) {
        assertSame("sync-api: result for $case", IssueResult.ResetPending, attempt())
        assertEquals("sync-api: $case must store no token row for the account", 0L, rowsOf(accountId))
    }

    @Test
    fun `a session is refused after a reset that came between the verify and the issue`() {
        val verified = runBlocking { accounts.verify("bob", AccountFixtures.verifier(2)) }
        assertTrue("sync-api: the verify before the reset must pass, got $verified", verified is VerifyResult.Verified)
        resetAccount("bob")
        assertEquals("sync-api: the reset must have marked the account", 1L, pendingOf(bob))
        assertNothingStored("a session for the pending account", bob) { runBlocking { store.issueSession(bob) } }
        assertEquals("sync-api: no token row may exist at all", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `a pending account does not stop a session for another account`() {
        resetAccount("bob")
        assertNothingStored("a session for the pending account", bob) { runBlocking { store.issueSession(bob) } }
        val issued = issuedOf(runBlocking { store.issueSession(alice) })
        assertEquals("sync-api: kind of the other account's session", TokenKind.SESSION, issued.kind)
        assertEquals("sync-api: the other account must hold one row", 1L, rowsOf(alice))
        assertEquals("sync-api: the pending account must still hold none", 0L, rowsOf(bob))
    }

    @Test
    fun `a keyed account that is not pending still gets a session after another account was reset`() {
        resetAccount("bob")
        assertEquals("sync-api: the keyed account is not pending", 0L, pendingOf(carol))
        val issued = issuedOf(runBlocking { store.issueSession(carol) })
        assertEquals("sync-api: kind of the keyed account's session", TokenKind.SESSION, issued.kind)
        assertEquals("sync-api: the keyed account must hold one row", 1L, rowsOf(carol))
    }

    @Test
    fun `an agent token is refused for a pending account for both scopes and no row is stored`() {
        resetAccount("alice")
        assertEquals("sync-api: the reset must have marked the admin", 1L, pendingOf(alice))
        assertNothingStored("an admin scope for the pending admin", alice) { issueAgent(alice, AgentScope.ADMIN) }
        assertNothingStored("a transcribe scope for the pending admin", alice) {
            issueAgent(alice, AgentScope.TRANSCRIBE)
        }
        assertEquals("sync-api: no token row may exist at all", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `the pending refusal of an agent token comes before the scope check`() {
        resetAccount("bob")
        assertNothingStored("an admin scope asked by a pending user", bob) { issueAgent(bob, AgentScope.ADMIN) }
        val control = issueAgent(carol, AgentScope.ADMIN)
        assertSame("sync-api: a user who is not pending asking the admin scope", IssueResult.ScopeNotAllowed, control)
    }

    @Test
    fun `an agent token is still issued for accounts that are not pending`() {
        resetAccount("alice")
        val user = issuedOf(issueAgent(bob, AgentScope.TRANSCRIBE))
        assertEquals("sync-api: kind of the user's token", TokenKind.AGENT, user.kind)
        assertEquals("sync-api: scope of the user's token", AgentScope.TRANSCRIBE, user.scope)
        val keyed = issuedOf(issueAgent(carol, AgentScope.TRANSCRIBE))
        assertEquals("sync-api: kind of the keyed account's token", TokenKind.AGENT, keyed.kind)
        assertEquals("sync-api: rows of the user", 1L, rowsOf(bob))
        assertEquals("sync-api: rows of the keyed account", 1L, rowsOf(carol))
        assertEquals("sync-api: rows of the pending admin", 0L, rowsOf(alice))
    }
}
