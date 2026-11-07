package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.SessionVault
import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.port.CredentialVault
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** KeysClearingSessionVault: save/load go to the session store and never touch the keys;
 * clear wipes keys first then clears the session, each in its own try; both fail throws
 * the first with the second suppressed; a single failure never stops the other side. */
class GapKeysClearingRulesTest {

    private class GapSessions(
        val calls: MutableList<String>,
        var clearFailure: Exception? = null,
    ) : SessionVault {
        var saved: String? = null
        override fun save(record: String) {
            calls.add("save")
            saved = record
        }
        override fun load(): String? {
            calls.add("load")
            return saved
        }
        override fun clear() {
            calls.add("clear")
            clearFailure?.let { throw it }
        }
    }

    private class GapCredentials(
        val calls: MutableList<String>,
        var wipeFailure: Exception? = null,
    ) : CredentialVault {
        override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
            calls.add("saveKeys")
        }
        override fun readKeys(): StoredAccountKeys? {
            calls.add("readKeys")
            return null
        }
        override fun wipeAll() {
            calls.add("wipeAll")
            wipeFailure?.let { throw it }
        }
    }

    @Test
    fun `clear wipes keys first then session`() {
        val calls = mutableListOf<String>()
        val vault = KeysClearingSessionVault(GapSessions(calls), GapCredentials(calls))
        vault.clear()
        assertEquals("keys first, then session", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `keys wipe failure still clears session and is rethrown`() {
        val calls = mutableListOf<String>()
        val failure = IOException("keys")
        val vault = KeysClearingSessionVault(GapSessions(calls), GapCredentials(calls, failure))
        val thrown = assertThrows(IOException::class.java) { vault.clear() }
        assertSame("the keys failure is rethrown", failure, thrown)
        assertEquals("the session is still cleared", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `session clear failure still wipes keys and is rethrown`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("session")
        val vault = KeysClearingSessionVault(GapSessions(calls, failure), GapCredentials(calls))
        val thrown = assertThrows(IllegalStateException::class.java) { vault.clear() }
        assertSame("the session failure is rethrown", failure, thrown)
        assertEquals("the keys are still wiped", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `both fail first failure thrown second suppressed`() {
        val calls = mutableListOf<String>()
        val keysFailure = IOException("keys")
        val sessionFailure = IllegalStateException("session")
        val vault = KeysClearingSessionVault(
            GapSessions(calls, sessionFailure),
            GapCredentials(calls, keysFailure),
        )
        val thrown = assertThrows(IOException::class.java) { vault.clear() }
        assertSame("the first failure is the keys failure", keysFailure, thrown)
        assertEquals("exactly one failure is suppressed", 1, thrown.suppressed.size)
        assertSame("the session failure is the suppressed one", sessionFailure, thrown.suppressed[0])
    }

    @Test
    fun `keys wipe failure does not stop session clear`() {
        val calls = mutableListOf<String>()
        val vault = KeysClearingSessionVault(GapSessions(calls), GapCredentials(calls, IOException("keys")))
        try { vault.clear() } catch (e: IOException) {}
        assertEquals("both ran", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `session clear failure does not stop keys wipe`() {
        val calls = mutableListOf<String>()
        val vault = KeysClearingSessionVault(
            GapSessions(calls, IllegalStateException("session")),
            GapCredentials(calls))
        try { vault.clear() } catch (e: IllegalStateException) {}
        assertEquals("both ran", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `save and load touch only the session store`() {
        val calls = mutableListOf<String>()
        val vault = KeysClearingSessionVault(GapSessions(calls), GapCredentials(calls))
        vault.save("the-record")
        assertEquals("load returns what the session vault holds", "the-record", vault.load())
        assertEquals("only the session vault was called", listOf("save", "load"), calls)
    }

    @Test
    fun `clear with nothing stored does not throw`() {
        val calls = mutableListOf<String>()
        val vault = KeysClearingSessionVault(GapSessions(calls), GapCredentials(calls))
        vault.clear()
        assertEquals("clear ran both sides", listOf("wipeAll", "clear"), calls)
    }
}
