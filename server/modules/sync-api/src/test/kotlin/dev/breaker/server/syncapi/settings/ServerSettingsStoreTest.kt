package dev.breaker.server.syncapi.settings

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ServerSettingsStoreTest : TempDatabaseTest() {

    private fun newStore(temp: TempDatabase): ServerSettingsStore =
        ServerSettingsStore(temp.db)

    @Test
    fun `absent row reads as enabled true`() {
        val temp = openTemp()
        val store = newStore(temp)
        val result = runBlocking { store.wrongPwLimitEnabled() }
        assertTrue("absent row means the switch is on", result)
    }

    @Test
    fun `set false then false again is still one row`() {
        val temp = openTemp()
        val store = newStore(temp)
        runBlocking { store.setWrongPwLimitEnabled(false) }
        runBlocking { store.setWrongPwLimitEnabled(false) }
        val count = runBlocking {
            temp.db.transaction { connection ->
                TestDatabases.longs(connection, "SELECT count(*) FROM server_settings").single()
            }
        }
        assertEquals("one settings row after two sets", 1L, count)
    }

    @Test
    fun `set false reads false then set true reads true`() {
        val temp = openTemp()
        val store = newStore(temp)
        runBlocking { store.setWrongPwLimitEnabled(false) }
        assertFalse("set false reads false", runBlocking { store.wrongPwLimitEnabled() })
        runBlocking { store.setWrongPwLimitEnabled(true) }
        assertTrue("set true reads true", runBlocking { store.wrongPwLimitEnabled() })
    }

    @Test
    fun `sql int_value of 5 reads true and 0 reads false`() {
        val temp = openTemp()
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO server_settings (setting_key, int_value) VALUES ('wrong_pw_limit', 5)",
                ).use { statement -> statement.executeUpdate() }
            }
        }
        val store = newStore(temp)
        assertTrue("sql value 5 reads true", runBlocking { store.wrongPwLimitEnabled() })

        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE server_settings SET int_value = 0 WHERE setting_key = 'wrong_pw_limit'",
                ).use { statement -> statement.executeUpdate() }
            }
        }
        assertFalse("sql value 0 reads false", runBlocking { store.wrongPwLimitEnabled() })
    }

    @Test
    fun `a value wider than an int still reads true`() {
        val temp = openTemp()
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO server_settings (setting_key, int_value) VALUES ('wrong_pw_limit', 65536 * 65536)",
                ).use { statement -> statement.executeUpdate() }
            }
        }
        assertTrue("a wide non-zero value reads true", runBlocking { newStore(temp).wrongPwLimitEnabled() })
    }

    @Test
    fun `the read is live flip read flip read`() {
        val temp = openTemp()
        val store = newStore(temp)

        val first = runBlocking { store.wrongPwLimitEnabled() }
        assertTrue("first read is on", first)

        runBlocking { store.setWrongPwLimitEnabled(false) }
        val second = runBlocking { store.wrongPwLimitEnabled() }
        assertFalse("after set false reads false", second)

        runBlocking { store.setWrongPwLimitEnabled(true) }
        val third = runBlocking { store.wrongPwLimitEnabled() }
        assertTrue("after set true reads true again", third)
    }
}
