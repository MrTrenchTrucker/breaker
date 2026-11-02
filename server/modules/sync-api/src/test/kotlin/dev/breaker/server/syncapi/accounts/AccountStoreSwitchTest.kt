package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.settings.ServerSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class AccountStoreSwitchTest : TempDatabaseTest() {

    private val start = Instant.ofEpochMilli(AccountFixtures.FIXED_MILLIS)

    private fun storeWithSwitch(temp: TempDatabase, enabled: Boolean): AccountStore {
        val settings = ServerSettingsStore(temp.db)
        runBlocking { settings.setWrongPwLimitEnabled(enabled) }
        return AccountStore(
            temp.db,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
            settings,
        )
    }

    private fun register(store: AccountStore, name: String, seed: Int) =
        AccountFixtures.registered(runBlocking {
            store.register(name, AccountFixtures.salt(seed), AccountFixtures.validKdf, 1, AccountFixtures.verifier(seed))
        })

    private fun attemptOf(store: AccountStore, foldedName: String) =
        runBlocking { store.attempts.attemptOf(foldedName) }

    @Test
    fun `switch off five wrong tries all return Rejected and signin_attempts has no row`() {
        val temp = openTemp()
        val store = storeWithSwitch(temp, false)
        register(store, "alice", 1)
        repeat(5) {
            val result = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
            assertEquals("wrong try is Rejected with switch off", VerifyResult.Rejected, result)
        }
        assertNull("no signin_attempts row with switch off", attemptOf(store, "alice"))
    }

    @Test
    fun `switch off right password returns Verified`() {
        val temp = openTemp()
        val store = storeWithSwitch(temp, false)
        register(store, "alice", 1)
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password with switch off is Verified", result is VerifyResult.Verified)
    }

    @Test
    fun `switch off after locked returns Verified and keeps the locked row`() {
        val temp = openTemp()
        val settings = ServerSettingsStore(temp.db)
        runBlocking { settings.setWrongPwLimitEnabled(true) }
        val store = AccountStore(
            temp.db,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
            settings,
        )
        register(store, "alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val lockedBefore = attemptOf(store, "alice")!!.lockedUntilMs
        val countBefore = attemptOf(store, "alice")!!.wrongCount

        // Turn the switch off.
        runBlocking { settings.setWrongPwLimitEnabled(false) }
        val result = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password with switch off is Verified", result is VerifyResult.Verified)
        assertEquals("locked row count unchanged", countBefore, attemptOf(store, "alice")!!.wrongCount)
        assertEquals("locked_until unchanged", lockedBefore, attemptOf(store, "alice")!!.lockedUntilMs)
    }

    @Test
    fun `switch back on right password is Locked again at same instant`() {
        val temp = openTemp()
        val settings = ServerSettingsStore(temp.db)
        runBlocking { settings.setWrongPwLimitEnabled(true) }
        val store = AccountStore(
            temp.db,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
            settings,
        )
        register(store, "alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val lockedBefore = attemptOf(store, "alice")!!.lockedUntilMs

        // Turn off, then back on.
        runBlocking { settings.setWrongPwLimitEnabled(false) }
        val resultOff = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password with switch off is Verified", resultOff is VerifyResult.Verified)
        runBlocking { settings.setWrongPwLimitEnabled(true) }
        val resultOn = runBlocking { store.verify("alice", AccountFixtures.verifier(1)) }
        assertTrue("right password with switch on is Locked again", resultOn is VerifyResult.Locked)
        val locked = (resultOn as VerifyResult.Locked).secondsRemaining
        assertTrue("locked again at same instant", locked > 0L)
        assertEquals("locked_until unchanged", lockedBefore, attemptOf(store, "alice")!!.lockedUntilMs)
    }

    @Test
    fun `switch off wrong try leaves existing row unchanged`() {
        val temp = openTemp()
        val settings = ServerSettingsStore(temp.db)
        runBlocking { settings.setWrongPwLimitEnabled(true) }
        val store = AccountStore(
            temp.db,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
            settings,
        )
        register(store, "alice", 1)
        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val countBefore = attemptOf(store, "alice")!!.wrongCount
        val lockedBefore = attemptOf(store, "alice")!!.lockedUntilMs

        runBlocking { settings.setWrongPwLimitEnabled(false) }
        val wrong = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        assertEquals("wrong try with switch off is Rejected", VerifyResult.Rejected, wrong)
        assertEquals("wrong count unchanged with switch off", countBefore, attemptOf(store, "alice")!!.wrongCount)
        assertEquals("locked_until unchanged with switch off", lockedBefore, attemptOf(store, "alice")!!.lockedUntilMs)
    }

    @Test
    fun `switch off unknown name returns Rejected with no row`() {
        val temp = openTemp()
        val store = storeWithSwitch(temp, false)
        val result = runBlocking { store.verify("nobody", AccountFixtures.verifier(1)) }
        assertEquals("unknown name with switch off is Rejected", VerifyResult.Rejected, result)
        assertNull("no signin_attempts row for unknown name", attemptOf(store, "nobody"))
    }

    @Test
    fun `drop table server_settings behaves as on five wrong tries still lock`() {
        val temp = openTemp()
        val settings = ServerSettingsStore(temp.db)
        val store = AccountStore(
            temp.db,
            VerifierHasher(iterations = AccountFixtures.FAST_ITERATIONS),
            Clock.fixed(start, ZoneOffset.UTC),
            settings,
        )
        register(store, "alice", 1)

        // Drop the settings table: the read fails and counts as ON.
        runBlocking {
            temp.db.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE server_settings")
                }
            }
        }

        repeat(5) { runBlocking { store.verify("alice", AccountFixtures.verifier(2)) } }
        val sixth = runBlocking { store.verify("alice", AccountFixtures.verifier(2)) }
        assertTrue("sixth try after DROP TABLE is Locked", sixth is VerifyResult.Locked)
    }
}
