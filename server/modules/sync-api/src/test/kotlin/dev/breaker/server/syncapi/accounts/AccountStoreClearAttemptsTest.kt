package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** clearAttempts is the hook the admin reset and the password change call. */
internal class AccountStoreClearAttemptsTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun registerAlice() {
        AccountFixtures.registered(
            runBlocking {
                store.register("alice", AccountFixtures.salt(1), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1))
            },
        )
    }

    private fun wrongTries(name: String, count: Int) {
        repeat(count) { runBlocking { store.verify(name, AccountFixtures.verifier(99)) } }
    }

    private fun attemptOf(folded: String): SigninAttemptStore.AttemptInfo? =
        runBlocking { store.attempts.attemptOf(folded) }

    private fun verify(name: String, seed: Int): VerifyResult =
        runBlocking { store.verify(name, AccountFixtures.verifier(seed)) }

    private fun insertRow(folded: String, wrongCount: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES (?, ?, 0, ?)",
                ).use { statement ->
                    statement.setString(1, folded)
                    statement.setLong(2, wrongCount)
                    statement.setLong(3, AccountFixtures.FIXED_MILLIS)
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `a locked account signs in at once after its attempts are cleared`() {
        registerAlice()
        wrongTries("alice", 5)
        assertTrue("the account is locked", verify("alice", 1) is VerifyResult.Locked)

        runBlocking { store.clearAttempts("alice") }

        assertNull("the row is gone", attemptOf("alice"))
        assertTrue("the right password signs in", verify("alice", 1) is VerifyResult.Verified)
    }

    @Test
    fun `clearAttempts folds the name`() {
        registerAlice()
        wrongTries("alice", 2)

        runBlocking { store.clearAttempts("ALICE") }

        assertNull("the row of the lower case name is gone", attemptOf("alice"))
    }

    @Test
    fun `a malformed name touches nothing`() {
        registerAlice()
        wrongTries("alice", 2)
        // The table accepts any lower case text, so a row can sit under a malformed name.
        insertRow("bad name", 3L)

        runBlocking { store.clearAttempts("bad name") }
        runBlocking { store.clearAttempts("") }

        assertNotNull("the row under the malformed name is kept", attemptOf("bad name"))
        assertEquals("another row is kept", 2L, attemptOf("alice")!!.wrongCount)
    }

    @Test
    fun `a name without a row is a no op`() {
        registerAlice()
        wrongTries("alice", 2)

        runBlocking { store.clearAttempts("nobody") }

        assertNull("nothing was created", attemptOf("nobody"))
        assertEquals("another row is kept", 2L, attemptOf("alice")!!.wrongCount)
    }

    @Test
    fun `a session issued before the clear still resolves after it`() {
        val alice = TokenFixtures.register(temp, "alice", 1)
        val tokenStore: TokenStore = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        val session = TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(alice.accountId) })
        wrongTries("alice", 5)

        runBlocking { store.clearAttempts("alice") }

        val resolved = runBlocking { tokenStore.resolve(session.secret.reveal()) }
        assertNotNull("the session still resolves", resolved)
        assertEquals("it is still alice's session", alice.accountId, resolved!!.accountId)
    }
}
