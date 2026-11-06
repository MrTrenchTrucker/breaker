package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.port.HandoffReceipt
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one best-effort logout of the session the server issued when the finish cannot keep it. */
class HandoffLogoutTest {
    private fun refusedPackageRig(): HandoffRig {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.crypto.failure = CryptoRefused()
        return rig
    }

    private fun failedStoreRig(): HandoffRig {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = FakeVaultRefusal()
        return rig
    }

    @Test
    fun `the logout uses the logout instance with the bearer header and no body`() {
        val rig = refusedPackageRig()
        rig.logout.answer(200)
        rig.receive(makeAttempt())
        val request = rig.logout.requests.single()
        assertEquals("POST", request.method)
        assertEquals(LOGOUT_URL, request.url)
        assertEquals(listOf("Authorization" to "Bearer " + GOOD_SESSION_TEXT), request.headers)
        assertNull(request.body)
    }

    @Test
    fun `the exchange instance never receives a logout`() {
        val rigs = listOf(refusedPackageRig(), failedStoreRig())
        for (rig in rigs) {
            rig.logout.answer(200)
            rig.receive(makeAttempt())
            assertEquals(1, rig.exchange.requests.size)
            assertEquals(EXCHANGE_URL, rig.exchange.requests[0].url)
        }
    }

    @Test
    fun `a logout that fails to connect still ends NOT_FINISHED and is not retried`() {
        val rig = refusedPackageRig()
        rig.logout.failWith(IOException("fake connection refused"))
        assertEquals(HandoffReceipt.NOT_FINISHED, rig.receive(makeAttempt()))
        assertEquals(1, rig.logout.requests.size)
    }

    @Test
    fun `a logout port that throws something else still ends NOT_FINISHED and is not retried`() {
        val rig = failedStoreRig()
        rig.logout.failWith(IllegalStateException("fake port failure"))
        assertEquals(HandoffReceipt.NOT_FINISHED, rig.receive(makeAttempt()))
        assertEquals(1, rig.logout.requests.size)
    }

    @Test
    fun `a logout that is answered with an error status is not retried and changes nothing`() {
        for (status in listOf(401, 500, 503)) {
            val rig = failedStoreRig()
            rig.logout.answer(status)
            assertEquals("status " + status, HandoffReceipt.NOT_FINISHED, rig.receive(makeAttempt()))
            assertEquals("status " + status, 1, rig.logout.requests.size)
            assertEquals("status " + status, listOf("saveKeys", "wipeAll"), rig.credentials.calls)
            assertEquals("status " + status, listOf("save", "clear"), rig.sessions.calls)
            assertNull("status " + status, rig.credentials.readKeys())
            assertNull("status " + status, rig.sessions.stored)
        }
    }

    @Test
    fun `the logout is sent after both stores are cleaned and before the receipt`() {
        val rig = failedStoreRig()
        rig.logout.answer(200)
        rig.receive(makeAttempt())
        val expected = listOf("keys.saveKeys", "sessions.save", "keys.wipeAll", "sessions.clear", "logout")
        assertEquals(expected, rig.order)
    }

    @Test
    fun `a good sign-in sends no logout`() {
        val rig = HandoffRig()
        rig.answerExchange()
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
        assertEquals(0, rig.logout.requests.size)
        assertTrue(rig.order.none { it == "logout" })
    }

    @Test
    fun `the logout request text shows no session text`() {
        val rig = refusedPackageRig()
        rig.logout.answer(200)
        rig.receive(makeAttempt())
        assertEquals("AuthHttpRequest(method=POST)", rig.logout.requests.single().toString())
    }
}
