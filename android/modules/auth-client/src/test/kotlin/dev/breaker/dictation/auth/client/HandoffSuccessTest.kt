package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.HandoffReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A good exchange: the keys and then the session are stored and the receipt is TAKEN. */
class HandoffSuccessTest {
    private fun goodRig(): HandoffRig {
        val rig = HandoffRig()
        rig.answerExchange()
        return rig
    }

    @Test
    fun `a good answer gives TAKEN and sends no logout`() {
        val rig = goodRig()
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
        assertEquals(1, rig.exchange.requests.size)
        assertEquals(0, rig.logout.requests.size)
    }

    @Test
    fun `the package is opened once with the private key of the attempt`() {
        val rig = goodRig()
        rig.receive(makeAttempt())
        assertEquals(1, rig.crypto.sealedSeen.size)
        assertTrue(sealedPackageBytes().contentEquals(rig.crypto.sealedSeen[0]))
        assertEquals(1, rig.crypto.privateSeen.size)
        assertTrue(attemptPrivateBytes().contentEquals(rig.crypto.privateSeen[0]))
    }

    @Test
    fun `the keys are saved before the session`() {
        val rig = goodRig()
        rig.receive(makeAttempt())
        assertEquals(listOf("keys.saveKeys", "sessions.save"), rig.order)
    }

    @Test
    fun `the calls on both stores are exactly one save each`() {
        val rig = goodRig()
        rig.receive(makeAttempt())
        assertEquals(listOf("saveKeys"), rig.credentials.calls)
        assertEquals(listOf("save"), rig.sessions.calls)
    }

    @Test
    fun `the stored record decodes to the session with the address that was read`() {
        val rig = goodRig()
        rig.base = "https://Box.Example/"
        rig.receive(makeAttempt())
        val stored = rig.sessions.stored
        assertNotNull(stored)
        val record = SessionRecord.decode(stored!!)
        assertNotNull(record)
        assertEquals(goodSession(), record!!.session)
        assertEquals("https://Box.Example/", record.server)
        assertEquals(1, rig.baseReads)
    }

    @Test
    fun `the admin role is stored with both scopes`() {
        val rig = HandoffRig()
        val members = goodMembers()
        members["role"] = quoted("admin")
        rig.answerExchange(bodyOf(members))
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
        val record = SessionRecord.decode(rig.sessions.stored!!)
        val expected = AuthSession(
            USERNAME_TEXT, USERNAME_TEXT, GOOD_SESSION_TEXT, UserRole.ADMIN,
            setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN),
        )
        assertEquals(expected, record!!.session)
    }

    @Test
    fun `the stored keys are the opened keys with the version of the answer`() {
        val rig = goodRig()
        rig.receive(makeAttempt())
        val held = rig.credentials.readKeys()
        assertNotNull(held)
        assertEquals(GOOD_KEY_VERSION, held!!.keyVersion)
        assertTrue(fakeDekBytes().contentEquals(held.keys.dek.bytes))
        assertTrue(fakeBoxBytes().contentEquals(held.keys.boxPrivateKey.copyBytes()))
    }

    @Test
    fun `key version zero is stored as zero`() {
        val rig = HandoffRig()
        val members = goodMembers()
        members["key_version"] = "0"
        rig.answerExchange(bodyOf(members))
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
        assertEquals(0, rig.credentials.readKeys()!!.keyVersion)
    }

    @Test
    fun `extra members in the answer do not stop the sign-in`() {
        val rig = HandoffRig()
        val members = goodMembers()
        members["surprise"] = quoted("x")
        rig.answerExchange(bodyOf(members))
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
    }

    @Test
    fun `a good answer with a name that is valid is TAKEN`() {
        val rig = goodRig()
        rig.deviceName = "n".repeat(64)
        assertEquals(HandoffReceipt.TAKEN, rig.receive(makeAttempt()))
    }
}
