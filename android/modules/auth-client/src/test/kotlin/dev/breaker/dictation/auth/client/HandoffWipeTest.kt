package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.SignInAttempt
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the finish zeroes: the attempt, the request body, the opened keys and the package. */
class HandoffWipeTest {
    /** A failure that is an Error and not an Exception, to show the wipe does not depend on a catch. */
    private class PortError : Error("the fake port broke")

    private fun assertAttemptWiped(attempt: SignInAttempt, label: String) {
        assertTrue("attempt " + label, attempt.isWiped)
        assertTrue("private key " + label, allZero(attempt.privateKey.copyBytes()))
        assertTrue("secret " + label, allZero(attempt.copySecretBytes()))
        assertTrue("public key " + label, allZero(attempt.copyPublicKey()))
    }

    private fun rigWithStoreFailure(): HandoffRig {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.sessions.saveFailure = FakeVaultRefusal()
        rig.logout.answer(200)
        return rig
    }

    @Test
    fun `the attempt is wiped on every path`() {
        val paths = mapOf<String, (HandoffRig) -> Unit>(
            "good answer" to { rig: HandoffRig -> rig.answerExchange() },
            "refused status" to { rig: HandoffRig -> rig.exchange.answer(401, "") },
            "network failure" to { rig: HandoffRig ->
                rig.exchange.failWith(IOException("fake connection refused"))
            },
            "unreadable answer" to { rig: HandoffRig -> rig.exchange.answer(200, "not json") },
            "package refused" to { rig: HandoffRig ->
                rig.answerExchange()
                rig.crypto.failure = CryptoRefused()
            },
            "key store cannot save" to { rig: HandoffRig ->
                rig.answerExchange()
                rig.credentials.saveFailure = IllegalStateException("fake save refused")
            },
            "session vault cannot save" to { rig: HandoffRig ->
                rig.answerExchange()
                rig.sessions.saveFailure = FakeVaultRefusal()
            },
            "refused address" to { rig: HandoffRig -> rig.base = "ftp://box.example" },
            "name provider throws" to { rig: HandoffRig ->
                rig.nameFailure = IllegalStateException("fake name")
            },
            "invalid name" to { rig: HandoffRig -> rig.deviceName = "" },
        )
        for ((label, script) in paths) {
            val rig = HandoffRig()
            script(rig)
            val attempt = makeAttempt()
            rig.receive(attempt)
            assertAttemptWiped(attempt, label)
        }
    }

    @Test
    fun `the attempt is wiped when the address provider throws`() {
        val rig = HandoffRig()
        rig.addressFailure = IllegalStateException("fake address refused")
        val attempt = makeAttempt()
        rig.receive(attempt)
        assertAttemptWiped(attempt, "address provider")
    }

    @Test
    fun `an attempt that was wiped before the call stays wiped`() {
        val rig = HandoffRig()
        val attempt = makeAttempt()
        attempt.wipe()
        rig.receive(attempt)
        assertAttemptWiped(attempt, "already wiped")
    }

    @Test
    fun `the attempt is wiped when the port throws an error and the error is not hidden`() {
        val rig = HandoffRig()
        rig.exchange.beforeSend = { throw PortError() }
        val attempt = makeAttempt()
        assertThrows(PortError::class.java) { rig.receive(attempt) }
        assertAttemptWiped(attempt, "error")
    }

    @Test
    fun `the request body is zeroed after it is sent`() {
        val rig = HandoffRig()
        rig.answerExchange()
        var sent: ByteArray? = null
        var liveAtSend = false
        rig.exchange.beforeSend = { request ->
            sent = request.body
            liveAtSend = request.body?.any { it != 0.toByte() } == true
        }
        rig.receive(makeAttempt())
        assertTrue("the body was empty when it was sent", liveAtSend)
        assertNotNull(sent)
        assertTrue("the body was not zeroed", allZero(sent!!))
    }

    @Test
    fun `the request body is zeroed when the exchange send throws`() {
        val rig = HandoffRig()
        rig.exchange.failWith(IOException("fake connection refused"))
        var sent: ByteArray? = null
        rig.exchange.beforeSend = { request -> sent = request.body }
        rig.receive(makeAttempt())
        assertNotNull(sent)
        assertTrue("the body was not zeroed", allZero(sent!!))
    }

    @Test
    fun `the request body is zeroed when the port throws an error`() {
        val rig = HandoffRig()
        var sent: ByteArray? = null
        rig.exchange.beforeSend = { request ->
            sent = request.body
            throw PortError()
        }
        assertThrows(PortError::class.java) { rig.receive(makeAttempt()) }
        assertNotNull(sent)
        assertTrue("the body was not zeroed", allZero(sent!!))
    }

    @Test
    fun `the opened keys are wiped after a good save and the stored copy stays whole`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.receive(makeAttempt())
        val opened = rig.crypto.handedOut.single()
        assertTrue("the data key was not wiped", allZero(opened.dek.bytes))
        assertTrue("the box key was not wiped", allZero(opened.boxPrivateKey.copyBytes()))
        val held = rig.credentials.readKeys()
        assertNotNull(held)
        assertTrue(fakeDekBytes().contentEquals(held!!.keys.dek.bytes))
        assertTrue(fakeBoxBytes().contentEquals(held.keys.boxPrivateKey.copyBytes()))
    }

    @Test
    fun `the opened keys are wiped when a store fails`() {
        val rigs = mapOf(
            "session vault cannot save" to rigWithStoreFailure(),
            "key store cannot save" to rigWithStoreFailure().also {
                it.sessions.saveFailure = null
                it.credentials.saveFailure = IllegalStateException("fake save refused")
            },
        )
        for ((label, rig) in rigs) {
            rig.receive(makeAttempt())
            val opened = rig.crypto.handedOut.single()
            assertTrue("data key " + label, allZero(opened.dek.bytes))
            assertTrue("box key " + label, allZero(opened.boxPrivateKey.copyBytes()))
        }
    }

    @Test
    fun `the sealed package copy is zero after a good open`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.receive(makeAttempt())
        assertTrue("the package held no bytes", sealedPackageBytes().contentEquals(rig.crypto.sealedSeen[0]))
        assertTrue("the package copy was not zeroed", allZero(rig.crypto.sealedObjects.single().copyBytes()))
    }

    @Test
    fun `the sealed package copy is zero after a refused open`() {
        val rig = HandoffRig()
        rig.answerExchange()
        rig.crypto.failure = CryptoRefused()
        rig.logout.answer(200)
        rig.receive(makeAttempt())
        assertEquals(1, rig.crypto.sealedObjects.size)
        assertTrue("the package copy was not zeroed", allZero(rig.crypto.sealedObjects[0].copyBytes()))
    }
}
