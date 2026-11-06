package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.port.HandoffReceipt
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decided failure behaviour of the hand-off finish. At an unreadable answer whose session text
 * can be found, and at a package that does not open, nothing of this attempt has been stored, so
 * a session and keys the phone already holds stay, and one logout of the new session is sent.
 * Only a failed store clears both stores, each in its own try, and then sends the one logout.
 */
class ReaderHandoffFinishFailurePathSecondTest {
    private val http = ReaderFinishAuthHttp()
    private val crypto = ReaderFinishCrypto()
    private val credentials = ReaderFinishCredentialVault()
    private val sessions = ReaderFinishSessionVault()

    private fun finish(): HandoffFinish = HandoffFinish(
        { "https://box.example" },
        { http.sendExchange(it) },
        { http.sendLogout(it) },
        crypto,
        credentials,
        sessions,
    )

    /** What the phone already holds before the hand-off: one session and one key set. */
    private fun holdSomething() {
        sessions.save("existing-session")
        credentials.saveKeys(
            AccountKeys(DataEncryptionKey(ByteArray(32) { 1 }), BoxPrivateKey(ByteArray(32) { 2 })),
            1,
        )
    }

    private fun assertHeldAndUntouched(why: String) {
        assertEquals("$why: the session stays", "existing-session", sessions.stored)
        assertEquals("$why: no session clear", 0, sessions.clearCalls)
        assertEquals("$why: no keys wipe", 0, credentials.wipeCalls)
        assertEquals("$why: only the keys saved before the hand-off", 1, credentials.saveCalls)
        assertEquals("$why: only the session saved before the hand-off", 1, sessions.saveCalls)
    }

    private fun assertOneLogoutOfNewSession(why: String) {
        assertEquals("$why: one logout", 1, http.logoutRequests.size)
        assertTrue(
            "$why: the logout carries the new session text",
            http.logoutRequests[0].headers.any { it.second == "Bearer $SESSION_TEXT_43" },
        )
    }

    @Test
    fun `a package that does not open keeps what the phone holds and sends one logout`() {
        holdSomething()
        crypto.throwOnOpen = true
        http.exchangeBody = exchangeBody()
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertHeldAndUntouched("refused package")
        assertOneLogoutOfNewSession("refused package")
    }

    @Test
    fun `an answer with a role outside the contract keeps what the phone holds and sends one logout`() {
        holdSomething()
        http.exchangeBody = exchangeBody().replace("\"role\":\"user\"", "\"role\":\"root\"")
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertHeldAndUntouched("unreadable answer")
        assertOneLogoutOfNewSession("unreadable answer")
    }

    @Test
    fun `an answer with no session text sends no logout and keeps what the phone holds`() {
        holdSomething()
        http.exchangeBody = "not json"
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertHeldAndUntouched("no session text")
        assertEquals("nothing to revoke", 0, http.logoutRequests.size)
    }

    @Test
    fun `a session store that cannot save clears both stores and sends one logout`() {
        holdSomething()
        http.exchangeBody = exchangeBody()
        sessions.saveFailure = Exception("store fails")
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("keys were saved first", 2, credentials.saveCalls)
        assertEquals("the keys are wiped", 1, credentials.wipeCalls)
        assertEquals("the session store is cleared", 1, sessions.clearCalls)
        assertOneLogoutOfNewSession("failed store")
    }

    @Test
    fun `a key store that cannot save clears both stores and sends one logout`() {
        holdSomething()
        http.exchangeBody = exchangeBody()
        credentials.saveFailure = Exception("keys fail")
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("the session was never saved by the finish", 1, sessions.saveCalls)
        assertEquals("the keys are wiped", 1, credentials.wipeCalls)
        assertEquals("the session store is cleared", 1, sessions.clearCalls)
        assertOneLogoutOfNewSession("failed key store")
    }

    @Test
    fun `the same world without a failure stores the new session and sends no logout`() {
        holdSomething()
        http.exchangeBody = exchangeBody()
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.TAKEN, receipt)
        assertEquals("keys saved once more", 2, credentials.saveCalls)
        assertEquals("session saved once more", 2, sessions.saveCalls)
        assertEquals("no wipe on success", 0, credentials.wipeCalls)
        assertEquals("no clear on success", 0, sessions.clearCalls)
        assertEquals("no logout on success", 0, http.logoutRequests.size)
    }

    @Test
    fun `a logout that throws changes neither the receipt nor the count of logouts`() {
        holdSomething()
        crypto.throwOnOpen = true
        http.exchangeBody = exchangeBody()
        http.throwOnLogout = IOException("cannot reach")
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("one try and no retry", 1, http.logoutRequests.size)
    }

    @Test
    fun `a keys wipe that throws does not stop the session clear or the logout`() {
        holdSomething()
        http.exchangeBody = exchangeBody()
        sessions.saveFailure = Exception("store fails")
        credentials.wipeFailure = Exception("wipe fails")
        val receipt = finish().onHandoff(HANDOFF_CODE, wipingAttempt())
        assertEquals(HandoffReceipt.NOT_FINISHED, receipt)
        assertEquals("the session store is still cleared", 1, sessions.clearCalls)
        assertOneLogoutOfNewSession("wipe failure")
    }

    @Test
    fun `the attempt is wiped on a failed store and on a successful finish`() {
        http.exchangeBody = exchangeBody()
        val taken = wipingAttempt()
        finish().onHandoff(HANDOFF_CODE, taken)
        assertTrue("wiped after success", taken.isWiped)
        sessions.saveFailure = Exception("store fails")
        val failed = wipingAttempt()
        finish().onHandoff(HANDOFF_CODE, failed)
        assertTrue("wiped after a failed store", failed.isWiped)
    }
}
