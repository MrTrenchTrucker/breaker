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

/** A session vault that notes each call in the shared list and can be told to fail its clear. */
private class KcsSessions(
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

/** A key vault that notes each call in the shared list and can be told to fail its wipe. */
private class KcsCredentials(
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

/** The decorator that makes a clear of the session also wipe the stored account keys. */
class KeysClearingSessionVaultTest {
    private val calls: MutableList<String> = mutableListOf()
    private val sessions = KcsSessions(calls)
    private val credentials = KcsCredentials(calls)
    private val vault = KeysClearingSessionVault(sessions, credentials)

    @Test
    fun `clear wipes the keys and then clears the session`() {
        vault.clear()
        assertEquals("wiring: clear must run both, keys first", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `a throwing keys wipe still clears the session and the keys failure is rethrown`() {
        val failure = IOException("keys")
        credentials.wipeFailure = failure
        val thrown = assertThrows(IOException::class.java) { vault.clear() }
        assertSame("wiring: the keys failure must be rethrown", failure, thrown)
        assertEquals("wiring: the session must still be cleared", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `a throwing session clear still wipes the keys and the session failure is rethrown`() {
        val failure = IllegalStateException("session")
        sessions.clearFailure = failure
        val thrown = assertThrows(IllegalStateException::class.java) { vault.clear() }
        assertSame("wiring: the session failure must be rethrown", failure, thrown)
        assertEquals("wiring: the keys must still be wiped", listOf("wipeAll", "clear"), calls)
    }

    @Test
    fun `when both fail the first failure is rethrown with the second suppressed`() {
        val keysFailure = IOException("keys")
        val sessionFailure = IllegalStateException("session")
        credentials.wipeFailure = keysFailure
        sessions.clearFailure = sessionFailure
        val thrown = assertThrows(IOException::class.java) { vault.clear() }
        assertSame("wiring: the first failure must be the keys failure", keysFailure, thrown)
        assertEquals("wiring: exactly one failure must be suppressed", 1, thrown.suppressed.size)
        assertSame(
            "wiring: the session failure must be the suppressed one",
            sessionFailure,
            thrown.suppressed[0],
        )
    }

    @Test
    fun `save and load go to the session vault and never touch the keys`() {
        vault.save("the-record")
        assertEquals("wiring: load must return what the session vault holds", "the-record", vault.load())
        assertEquals("wiring: only the session vault may be called", listOf("save", "load"), calls)
    }

    @Test
    fun `a save after a clear leaves the keys alone`() {
        vault.clear()
        calls.clear()
        vault.save("the-record")
        assertEquals("wiring: a save must not wipe the keys", listOf("save"), calls)
    }
}
