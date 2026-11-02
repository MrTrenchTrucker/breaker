package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A successful registration drops the wrong-password row left for that name. A failed one drops
 * nothing, and no other name is touched.
 */
internal class AccountStoreRegisterDropTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: AccountStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = AccountFixtures.newStore(temp)
    }

    private fun register(name: String, seed: Int): RegisterResult =
        runBlocking {
            store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        }

    // The verifier of seed 99 is never the one an account is registered with in these tests.
    private fun wrongTries(name: String, count: Int) {
        repeat(count) { runBlocking { store.verify(name, AccountFixtures.verifier(99)) } }
    }

    private fun attemptOf(folded: String): SigninAttemptStore.AttemptInfo? =
        runBlocking { store.attempts.attemptOf(folded) }

    private fun verify(name: String, seed: Int): VerifyResult =
        runBlocking { store.verify(name, AccountFixtures.verifier(seed)) }

    @Test
    fun `a name locked before it existed is clean after registration and signs in at once`() {
        wrongTries("carol", 5)
        val before = attemptOf("carol")
        assertNotNull("five wrong tries leave a row", before)
        assertEquals("the lock is set", AccountFixtures.FIXED_MILLIS + 900_000L, before!!.lockedUntilMs)
        assertTrue("the name is locked", verify("carol", 1) is VerifyResult.Locked)

        AccountFixtures.registered(register("carol", 1))

        assertNull("the row is gone", attemptOf("carol"))
        assertTrue("the right password signs in", verify("carol", 1) is VerifyResult.Verified)
    }

    @Test
    fun `a partial count left for a name is dropped by its registration`() {
        wrongTries("carol", 3)
        assertEquals("three wrong tries are counted", 3L, attemptOf("carol")!!.wrongCount)

        AccountFixtures.registered(register("carol", 1))

        assertNull("the partial count is gone", attemptOf("carol"))
    }

    @Test
    fun `registering with capital letters drops the row of the folded name`() {
        wrongTries("carol", 2)

        AccountFixtures.registered(register("Carol", 1))

        assertNull("the row of the lower case name is gone", attemptOf("carol"))
    }

    @Test
    fun `a taken name keeps the row of the live account`() {
        AccountFixtures.registered(register("alice", 1))
        wrongTries("alice", 3)
        val before = attemptOf("alice")
        assertEquals("three wrong tries are counted", 3L, before!!.wrongCount)

        assertEquals("the second registration is refused", RegisterResult.UsernameTaken, register("alice", 2))

        assertEquals("the row is untouched", before, attemptOf("alice"))
    }

    @Test
    fun `a refused registration drops no row`() {
        wrongTries("dave", 2)
        val before = attemptOf("dave")

        val shortSalt = runBlocking {
            store.register("dave", ByteArray(15), AccountFixtures.validKdf, 1, AccountFixtures.verifier(1))
        }
        assertEquals("a short salt is refused", RegisterResult.InvalidField("salt"), shortSalt)
        val badVersion = runBlocking {
            store.register("dave", AccountFixtures.salt(1), AccountFixtures.validKdf, 2, AccountFixtures.verifier(1))
        }
        assertEquals("another kdf version is refused", RegisterResult.InvalidField("kdf_version"), badVersion)
        val badKdf = runBlocking {
            store.register("dave", AccountFixtures.salt(1), KdfParams(1, 1, 1), 1, AccountFixtures.verifier(1))
        }
        assertEquals("a cost below the bounds is refused", RegisterResult.KdfOutOfBounds, badKdf)

        assertEquals("the row is untouched by every refusal", before, attemptOf("dave"))
        assertEquals("no account was created", 0L, AccountFixtures.accountCount(temp))
    }

    @Test
    fun `registering one name leaves the rows of other names alone`() {
        wrongTries("erin", 2)
        wrongTries("frank", 4)
        val frankBefore = attemptOf("frank")

        AccountFixtures.registered(register("erin", 1))

        assertNull("erin's row is gone", attemptOf("erin"))
        assertEquals("frank's row is untouched", frankBefore, attemptOf("frank"))
    }
}
