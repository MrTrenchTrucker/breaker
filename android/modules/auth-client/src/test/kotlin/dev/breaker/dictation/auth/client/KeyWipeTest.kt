package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.shared.api.LoginResponse
import java.io.IOException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every secret the key fakes handed out or were given is zero after the call, on success and on each
 * failure. The arrays are the very objects the flow held, kept in the fakes' public lists. The box
 * public key and the salt copy inside a wrapped data key are not secret and are not wiped by the
 * model classes, so they are not asserted here.
 */
class KeyWipeTest {
    private fun heldArrays(kr: KeyRig): Map<String, List<ByteArray>> = mapOf(
        "dek" to kr.accountKeys.deks.map { it.bytes },
        "pair" to kr.accountKeys.pairs.map { it.privateKey.copyBytes() },
        "wrappedDek" to kr.accountKeys.wrappedDeks.map { it.bytes },
        "wrappedBox" to kr.accountKeys.wrappedBoxes.map { it.bytes },
        "openedDek" to kr.crypto.deks.map { it.bytes },
        "openedBox" to kr.accountKeys.unwrappedBoxes.map { it.copyBytes() },
        "boxWrapSeen" to kr.accountKeys.boxWrapsSeen.map { it.bytes },
        "dekWrapSeen" to kr.crypto.wrappedSeen.map { it.bytes },
        "publicSeen" to kr.accountKeys.publicSeen.toList(),
    )

    /** The lists in [nonEmpty] must hold something, then every array in every list must be zero. */
    private fun assertAllZero(case: String, kr: KeyRig, nonEmpty: Set<String>) {
        val held = heldArrays(kr)
        for (name in nonEmpty) {
            assertTrue(case + ": nothing was handed out in " + name, held.getValue(name).isNotEmpty())
        }
        for ((name, arrays) in held) {
            for ((index, bytes) in arrays.withIndex()) {
                assertTrue(case + ": " + name + "[" + index + "] is not zero", allZero(bytes))
            }
        }
        assertWiped(kr.derive)
    }

    private val bootstrapHeld = setOf("dek", "pair", "wrappedDek", "wrappedBox")
    private val openedHeld = setOf("openedDek", "openedBox", "boxWrapSeen", "dekWrapSeen", "publicSeen")

    private fun queueKeyed(kr: KeyRig, body: String) {
        kr.rig.answerSaltOk()
        kr.http.answer(200, body)
    }

    @Test
    fun `a successful bootstrap leaves every secret zero`() {
        val kr = KeyRig()
        kr.queueBootstrap()
        kr.login()
        assertAllZero("bootstrap success", kr, bootstrapHeld)
    }

    @Test
    fun `a successful unwrap leaves every secret zero`() {
        val kr = KeyRig()
        queueKeyed(kr, keyedLoginBody("tok-k", 3))
        kr.login()
        assertAllZero("unwrap success", kr, openedHeld)
    }

    @Test
    fun `a public key mismatch leaves every secret zero`() {
        val kr = KeyRig()
        val body = loginReplyWithKeysBody("tok-k", keyWrappedDek(), keyWrappedBox(), keyPattern(32, 0x71), 3)
        queueKeyed(kr, body)
        kr.failure()
        assertAllZero("public key mismatch", kr, openedHeld)
    }

    @Test
    fun `a refused data key leaves every secret zero`() {
        val kr = KeyRig()
        val changed = keyWrappedDek()
        changed[40] = (changed[40].toInt() xor 1).toByte()
        queueKeyed(kr, loginReplyWithKeysBody("tok-k", changed, keyWrappedBox(), keyPublic(), 3))
        kr.failure()
        // the refusal comes before any key is opened, so only the wrapped data key was seen
        assertAllZero("changed data key", kr, setOf("dekWrapSeen"))
    }

    @Test
    fun `a data key that is not base64 hands nothing out and leaves the derived keys wiped`() {
        val kr = KeyRig()
        val boxText = base64Of(keyWrappedBox())
        queueKeyed(kr, loginReplyWithKeyTexts("tok-k", "!!!!", boxText, base64Of(keyPublic()), 3))
        kr.failure()
        // the decode refuses first, so no list holds anything; only the derived keys are checked
        assertAllZero("not base64", kr, emptySet())
        assertTrue("a key fake was reached: " + kr.log, kr.log.none { it == "unwrapDek" })
    }

    @Test
    fun `a failing key store after an unwrap leaves every secret zero`() {
        val kr = KeyRig()
        kr.credentials.saveFailure = FakeVaultRefusal()
        queueKeyed(kr, keyedLoginBody("tok-k", 3))
        kr.failure()
        assertAllZero("key store failure after unwrap", kr, openedHeld)
    }

    @Test
    fun `a failing key store after the keys POST leaves every secret zero`() {
        val kr = KeyRig()
        kr.credentials.saveFailure = FakeVaultRefusal()
        kr.queueBootstrap()
        kr.failure()
        assertAllZero("key store failure after upload", kr, bootstrapHeld)
    }

    @Test
    fun `an unreachable server on the keys POST leaves every secret zero`() {
        val kr = KeyRig()
        kr.rig.answerSaltOk()
        kr.rig.answerLoginOk("tok-1")
        kr.http.failWith(IOException("fake connection refused"))
        kr.failure()
        assertAllZero("keys POST IOException", kr, bootstrapHeld)
    }

    @Test
    fun `the keys request body is zero after settle returns, with an uploader that does not wipe`() {
        val kr = KeyRig()
        val flow = AccountKeyFlow(kr.ports)
        val kek = KeyEncryptionKey(FAKE_KEK_BYTES.copyOf())
        val first = LoginResponse(
            token = "tok-1", role = "user", wrappedDek = null, wrappedBoxPrivkey = null, boxPubkey = null,
            keyVersion = 0, prevWrappedDek = null, resetAt = null, rekeyedAt = null,
        )
        var captured: ByteArray? = null
        var seen: ByteArray? = null
        flow.settle(
            kek, FAKE_SALT_BYTES.copyOf(), first,
            upload = { _, body ->
                captured = body
                seen = body.copyOf()
                1
            },
            relogin = { throw AssertionError("no relogin") },
            dropToken = { },
        )
        val sentCopy = seen
        val sentBody = captured
        assertNotNull("the upload was never called", sentCopy)
        assertTrue("the body was empty when it was sent", sentCopy!!.any { it != 0.toByte() })
        assertNotNull("the upload was never called", sentBody)
        assertTrue("the body is not zero after settle returned", allZero(sentBody!!))
    }

    @Test
    fun `the keys request body is zero after settle throws, with an uploader that does not wipe`() {
        val kr = KeyRig()
        val flow = AccountKeyFlow(kr.ports)
        val kek = KeyEncryptionKey(FAKE_KEK_BYTES.copyOf())
        val first = LoginResponse(
            token = "tok-1", role = "user", wrappedDek = null, wrappedBoxPrivkey = null, boxPubkey = null,
            keyVersion = 0, prevWrappedDek = null, resetAt = null, rekeyedAt = null,
        )
        var captured: ByteArray? = null
        var seen: ByteArray? = null
        assertThrows(AuthFailure::class.java) {
            flow.settle(
                kek, FAKE_SALT_BYTES.copyOf(), first,
                upload = { _, body ->
                    captured = body
                    seen = body.copyOf()
                    throw AuthFailure(AuthFailureReason.SERVER_ERROR)
                },
                relogin = { throw AssertionError("no relogin") },
                dropToken = { },
            )
        }
        val sentCopy = seen
        val sentBody = captured
        assertNotNull("the upload was never called", sentCopy)
        assertTrue("the body was empty when it was sent", sentCopy!!.any { it != 0.toByte() })
        assertNotNull("the upload was never called", sentBody)
        assertTrue("the body is not zero after settle threw", allZero(sentBody!!))
    }
}
