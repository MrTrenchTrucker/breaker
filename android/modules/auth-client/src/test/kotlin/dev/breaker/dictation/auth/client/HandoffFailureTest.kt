package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.port.HandoffReceipt
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way the finish can fail, one test per step of its table: the receipt, what was stored,
 * the wipe and the number of logout requests. Steps 0 to 5 end before any session exists, so they
 * send no logout. Steps 6 to 8 follow a session the server issued, so they send one when its
 * session text can be found. Step 3 (the request body build) cannot be made to fail from here.
 */
class HandoffFailureTest {
    private val notFinished = HandoffReceipt.NOT_FINISHED

    private fun assertNothingSentOrStored(rig: HandoffRig, attempt: SignInAttempt) {
        assertEquals(0, rig.exchange.requests.size)
        assertEquals(0, rig.logout.requests.size)
        assertNothingStored(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    private fun assertAnswerRefusedWithoutLogout(rig: HandoffRig, attempt: SignInAttempt) {
        assertEquals(1, rig.exchange.requests.size)
        assertEquals(0, rig.logout.requests.size)
        assertEquals(0, rig.crypto.sealedSeen.size)
        assertNothingStored(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    private fun assertOneLogoutOfTheGoodSession(rig: HandoffRig) {
        assertEquals(1, rig.logout.requests.size)
        val request = rig.logout.requests[0]
        assertEquals("POST", request.method)
        assertEquals(LOGOUT_URL, request.url)
        assertEquals(listOf("Authorization" to "Bearer " + GOOD_SESSION_TEXT), request.headers)
        assertNull(request.body)
    }

    private fun assertStatusRefused(status: Int) {
        val rig = HandoffRig()
        rig.exchange.answer(status, bodyOf(goodMembers()))
        val attempt = makeAttempt()
        assertEquals("status " + status, notFinished, rig.receive(attempt))
        assertAnswerRefusedWithoutLogout(rig, attempt)
    }

    // step 0
    @Test
    fun `step 0 an attempt that is already wiped sends nothing`() {
        val rig = HandoffRig()
        rig.answerExchange()
        val attempt = makeAttempt()
        attempt.wipe()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(0, rig.baseReads)
        assertNothingSentOrStored(rig, attempt)
    }

    // step 1
    @Test
    fun `step 1 a device name provider that throws sends nothing`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.nameFailure = IllegalStateException("fake name refused")
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(0, rig.baseReads)
        assertNothingSentOrStored(rig, attempt)
    }

    @Test
    fun `step 1 every invalid device name sends nothing and the case is named`() {
        val cases = mapOf(
            "empty" to "",
            "65 code points" to "n".repeat(65),
            "65 pairs" to "\uD83D\uDE00".repeat(65),
            "bell" to "a\u0007b",
            "newline" to "a\nb",
            "tab" to "a\tb",
            "c1 control" to "a\u0085b",
            "lone high surrogate" to "a\uD800b",
            "lone low surrogate" to "a\uDC00b",
        )
        for ((label, name) in cases) {
            val rig = HandoffRig()
            rig.answerExchange()
            rig.deviceName = name
            val attempt = makeAttempt()
            assertEquals("case " + label, notFinished, rig.receive(attempt))
            assertEquals("case " + label, 0, rig.baseReads)
            assertNothingSentOrStored(rig, attempt)
        }
    }

    // step 2
    @Test
    fun `step 2 an address provider that throws sends nothing`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.addressFailure = IllegalStateException("fake address refused")
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(1, rig.baseReads)
        assertNothingSentOrStored(rig, attempt)
    }

    @Test
    fun `step 2 a refused address sends nothing and is read once`() {
        for (address in listOf("ftp://box.example", "", "box.example", "https://box.example/x?y")) {
            val rig = HandoffRig()
            rig.answerExchange()
            rig.base = address
            val attempt = makeAttempt()
            assertEquals("address " + address, notFinished, rig.receive(attempt))
            assertEquals("address " + address, 1, rig.baseReads)
            assertNothingSentOrStored(rig, attempt)
        }
    }

    // step 4
    @Test
    fun `step 4 a network failure stores nothing and sends no logout`() {
        val rig = HandoffRig()
        rig.exchange.failWith(IOException("fake connection refused"))
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertAnswerRefusedWithoutLogout(rig, attempt)
    }

    @Test
    fun `step 4 a port failure that is not a network failure stores nothing and sends no logout`() {
        val rig = HandoffRig()
        rig.exchange.failWith(IllegalStateException("fake port failure"))
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertAnswerRefusedWithoutLogout(rig, attempt)
    }

    // step 5
    @Test
    fun `step 5 status 400 stores nothing and sends no logout`() {
        assertStatusRefused(400)
    }

    @Test
    fun `step 5 status 401 stores nothing and sends no logout`() {
        assertStatusRefused(401)
    }

    @Test
    fun `step 5 status 413 stores nothing and sends no logout`() {
        assertStatusRefused(413)
    }

    @Test
    fun `step 5 status 503 stores nothing and sends no logout`() {
        assertStatusRefused(503)
    }

    @Test
    fun `step 5 every other status that is not 200 stores nothing and sends no logout`() {
        for (status in listOf(201, 204, 301, 403, 404, 500, 502)) {
            assertStatusRefused(status)
        }
    }

    // step 6
    @Test
    fun `step 6 an answer that is not JSON sends no logout because no session text is known`() {
        val rig = HandoffRig()
        rig.exchange.answer(200, "not json")
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertAnswerRefusedWithoutLogout(rig, attempt)
    }

    @Test
    fun `step 6 an answer with a session text of the wrong shape sends no logout`() {
        val rig = HandoffRig()
        rig.exchange.answer(200, bodyOf(goodMembers()).replace(GOOD_SESSION_TEXT, "x".repeat(42)))
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertAnswerRefusedWithoutLogout(rig, attempt)
    }

    @Test
    fun `step 6 an unreadable answer whose session text can be found sends one logout`() {
        val broken = listOf(
            Triple("role missing", "role", null),
            Triple("role is a number", "role", "5"),
            Triple("role is unknown", "role", quoted("root")),
            Triple("key version below zero", "key_version", "-1"),
            Triple("package is not base64", "sealed_package", quoted("!!!!")),
            Triple("package is empty", "sealed_package", quoted("")),
            Triple("username is blank", "username", quoted(" ")),
            Triple("account id is a string", "account_id", quoted("7")),
        )
        for ((label, name, value) in broken) {
            val rig = HandoffRig()
            val members = goodMembers()
            if (value == null) members.remove(name) else members[name] = value
            rig.exchange.answer(200, bodyOf(members))
            rig.logout.answer(200)
            val attempt = makeAttempt()
            assertEquals("case " + label, notFinished, rig.receive(attempt))
            assertEquals("case " + label, 1, rig.exchange.requests.size)
            assertOneLogoutOfTheGoodSession(rig)
            assertEquals("case " + label, 0, rig.crypto.sealedSeen.size)
            assertNothingStored(rig)
            assertTrue("the attempt was not wiped", attempt.isWiped)
        }
    }

    // step 7
    @Test
    fun `step 7 a package that is refused stores nothing and sends one logout`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.crypto.failure = CryptoRefused()
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(1, rig.crypto.sealedSeen.size)
        assertOneLogoutOfTheGoodSession(rig)
        assertNothingStored(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 7 a failure of the crypto port that is not a refusal also sends one logout`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.crypto.failure = IllegalStateException("fake crypto failure")
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertOneLogoutOfTheGoodSession(rig)
        assertNothingStored(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    // step 8
    @Test
    fun `step 8 a record that cannot be encoded saves no keys then cleans both stores and logs out`() {
        val rig = HandoffRig()
        rig.base = "https://box.example/" + "p".repeat(17000)
        rig.answerExchange()
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(listOf("keys.wipeAll", "sessions.clear", "logout"), rig.order)
        assertEquals(listOf("wipeAll"), rig.credentials.calls)
        assertEquals(listOf("clear"), rig.sessions.calls)
        assertEquals(1, rig.logout.requests.size)
        assertTrue(rig.logout.requests[0].url.endsWith("/v1/auth/logout"))
        assertEquals(
            listOf("Authorization" to "Bearer " + GOOD_SESSION_TEXT),
            rig.logout.requests[0].headers,
        )
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 8 a key store that cannot save then cleans both stores and logs out`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.credentials.saveFailure = IllegalStateException("fake save refused")
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(listOf("keys.saveKeys", "keys.wipeAll", "sessions.clear", "logout"), rig.order)
        assertEquals(listOf("saveKeys", "wipeAll"), rig.credentials.calls)
        assertEquals(listOf("clear"), rig.sessions.calls)
        assertNull(rig.sessions.stored)
        assertNull(rig.credentials.readKeys())
        assertOneLogoutOfTheGoodSession(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 8 a session vault that cannot save wipes the keys it just stored and logs out`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = FakeVaultRefusal()
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        val expected = listOf("keys.saveKeys", "sessions.save", "keys.wipeAll", "sessions.clear", "logout")
        assertEquals(expected, rig.order)
        assertNull(rig.credentials.readKeys())
        assertNull(rig.sessions.stored)
        assertOneLogoutOfTheGoodSession(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 8 a keys wipe that throws does not stop the session clear or the logout`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = FakeVaultRefusal()
        rig.credentials.wipeFailure = IllegalStateException("fake wipe refused")
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        val expected = listOf("keys.saveKeys", "sessions.save", "keys.wipeAll", "sessions.clear", "logout")
        assertEquals(expected, rig.order)
        assertNull(rig.sessions.stored)
        assertOneLogoutOfTheGoodSession(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 8 a session clear that throws does not stop the keys wipe or the logout`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = FakeVaultRefusal()
        rig.failing.failClear = IllegalStateException("fake clear refused")
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        val expected = listOf("keys.saveKeys", "sessions.save", "keys.wipeAll", "sessions.clear", "logout")
        assertEquals(expected, rig.order)
        assertNull(rig.credentials.readKeys())
        assertOneLogoutOfTheGoodSession(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `step 8 when both cleans throw the logout is still sent once`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.credentials.saveFailure = IllegalStateException("fake save refused")
        rig.credentials.wipeFailure = IllegalStateException("fake wipe refused")
        rig.failing.failClear = IllegalStateException("fake clear refused")
        rig.logout.answer(200)
        val attempt = makeAttempt()
        assertEquals(notFinished, rig.receive(attempt))
        assertEquals(listOf("keys.saveKeys", "keys.wipeAll", "sessions.clear", "logout"), rig.order)
        assertOneLogoutOfTheGoodSession(rig)
        assertTrue("the attempt was not wiped", attempt.isWiped)
    }

    @Test
    fun `an unexpected failure in a later step never leaves the finish as an exception`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = IllegalArgumentException("fake refusal")
        rig.logout.failWith(IOException("fake connection refused"))
        assertEquals(notFinished, rig.receive(makeAttempt()))
        assertEquals(1, rig.logout.requests.size)
    }
}
