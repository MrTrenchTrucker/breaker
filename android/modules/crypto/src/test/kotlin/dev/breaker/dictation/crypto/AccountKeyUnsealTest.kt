package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.SealedAgentResult
import dev.breaker.dictation.seal.SealedBox
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves [AccountKeyServiceImpl.unsealAndReencrypt]: the real boxes in [UnsealBoxData] open and are
 * encrypted again byte for byte, every refusal is the one plain [CryptoRefused], the nonce source is
 * never asked on a refusal, and the plaintext array the service gets from the opener is zeroed on
 * every path.
 *
 * The seam tests put a recording [BoxOpener] in place of the real opener. They use the REAL valid
 * box, whose tag passes, so the opener returns whatever plaintext the fake chooses and the
 * plaintext rules can be exercised with any bytes.
 */
class AccountKeyUnsealTest : CryptoServiceTestFixture() {
    private val aad = aadFor(clientA, 1)

    /** Counts its calls and hands back the very array it was given, so the test can look at it later. */
    private class HandedOutOpener(private val chosen: ByteArray) : BoxOpener {
        var calls = 0

        override fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
            calls++
            return chosen
        }
    }

    /**
     * Counts its calls and answers with the REAL opener, so a refusal is the module's own.
     *
     * The seam is the whole open call now (the body cipher is internal to the shared module), so a box
     * that passes the cap check DOES reach this opener; the real opener is what refuses it.
     */
    private class CountingRealOpener : BoxOpener {
        var calls = 0

        override fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
            calls++
            return SealedBoxOpener().open(box, recipientPrivateKey)
        }
    }

    private fun recipientKey() = BoxPrivateKey(hex(UnsealBoxData.recipientPrivateKeyHex))

    private fun validSealed() = SealedAgentResult(hex(UnsealBoxData.validBoxHex))

    private fun realService(nonces: NonceSource) =
        AccountKeyServiceImpl(nonces, { size -> ByteArray(size) }, SealedBoxOpener())

    private fun seamService(nonces: NonceSource, fake: BoxOpener) =
        AccountKeyServiceImpl(nonces, { size -> ByteArray(size) }, SealedBoxOpener(fake))

    private fun flipBit(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    /**
     * A genuinely sealed box around [plaintext], made by the shared module's public sealer. The tag
     * is real, so the real opener accepts the box and the test's fake opener then returns whatever
     * plaintext it chooses. The sealer is the one in `shared/modules/seal`; nothing about the box
     * construction is reimplemented here. Test data only, so nothing is zeroed.
     */
    private fun sealedBoxOf(plaintext: ByteArray): ByteArray {
        // The recipient public key of the boxes in [UnsealBoxData], derived from its private key.
        val recipientPublic =
            X25519PrivateKeyParameters(hex(UnsealBoxData.recipientPrivateKeyHex), 0)
                .generatePublicKey().encoded
        // The helper can be asked for a box one byte over the service's box cap, so the sealer's
        // cap is the box cap, not the plaintext cap. The shared sealer refuses an empty plaintext,
        // and every caller here passes at least one byte.
        return SealedBox().seal(recipientPublic, plaintext, UNSEAL_BOX_MAX_BYTES)
    }

    private fun body(text: String, source: String = "server"): String =
        "{\"v\":1,\"text\":\"$text\",\"source\":\"$source\",\"model\":\"small\"," +
            "\"duration_ms\":1500,\"created_at\":\"2026-11-01T12:00:00.000Z\"}"

    /** A valid R1 server body of exactly [size] bytes, padded with a long text. */
    private fun bodyOfSize(size: Int): ByteArray {
        val base = body("").toByteArray(Charsets.UTF_8).size
        return body("a".repeat(size - base)).toByteArray(Charsets.UTF_8)
    }

    private fun assertZero(label: String, bytes: ByteArray) {
        assertTrue("crypto unseal: $label must be zeroed", bytes.all { it == 0.toByte() })
    }

    /** The plain refusal: exact class, the plain text, no cause, no key hex and no body text in it. */
    private fun assertPlainRefusal(label: String, nonces: CountingNonces, block: () -> Unit) {
        val thrown = assertThrows("crypto unseal: $label must be refused", Throwable::class.java) {
            block()
        }
        assertEquals(
            "crypto unseal: $label must be a plain CryptoRefused",
            CryptoRefused::class.java,
            thrown.javaClass,
        )
        assertEquals("crypto unseal: $label message", CryptoRefused().message, thrown.message)
        assertNull("crypto unseal: $label must have no cause", thrown.cause)
        val message = thrown.message.orEmpty()
        val keyHexStart = UnsealBoxData.recipientPrivateKeyHex.substring(0, 16)
        assertFalse("crypto unseal: $label message holds key hex", message.contains(keyHexStart))
        val bodyText = message.contains("caf") || message.contains("small")
        assertFalse("crypto unseal: $label message holds body text", bodyText)
        assertEquals("crypto unseal: $label must not ask for a nonce", 0, nonces.calls)
    }

    private fun assertRealRefused(label: String, box: ByteArray, key: BoxPrivateKey = recipientKey()) {
        val nonces = CountingNonces(nonceOne)
        assertPlainRefusal(label, nonces) {
            realService(nonces).unsealAndReencrypt(SealedAgentResult(box), key, dek, aad)
        }
    }

    /** One refusal through the fake: the fake was reached once, and the array it handed out is zero. */
    private fun assertSeamRefused(label: String, plain: ByteArray) {
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(plain)
        assertPlainRefusal(label, nonces) {
            seamService(nonces, fake).unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)
        }
        assertEquals("crypto unseal: $label must reach the opener plaintext", 1, fake.calls)
        assertZero("$label plaintext array", plain)
    }

    // --- (1) the real box ----------------------------------------------------------------------

    @Test
    fun `the valid real box is opened and encrypted again byte for byte`() {
        val nonces = CountingNonces(nonceOne)
        val expected = String(hex(UnsealBoxData.validPlainHex), Charsets.US_ASCII)

        val result = realService(nonces).unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)

        // The plaintext holds unicode escapes: a re-serialiser would change them and this would differ.
        val decrypted = CryptoServiceImpl().decrypt(result, dek, aad)
        assertEquals("crypto unseal: valid box decrypts to the sealed text", expected, decrypted)
        assertEquals("crypto unseal: valid box must ask for one nonce", 1, nonces.calls)
        assertArrayEquals("crypto unseal: valid box nonce is the source nonce", nonceOne, result.nonce)
        assertEquals("crypto unseal: valid box tag size", 16, result.tag.size)
    }

    @Test
    fun `the result is refused with another aad and with another data key`() {
        val service = realService(CountingNonces(nonceOne))
        val result = service.unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)

        refused("crypto unseal: decrypt with another aad") {
            CryptoServiceImpl().decrypt(result, dek, aadFor(clientB, 1))
        }
        refused("crypto unseal: decrypt with another data key") {
            CryptoServiceImpl().decrypt(result, DataEncryptionKey(patterned(0x11, 32)), aad)
        }
    }

    // --- (2) real box negatives ----------------------------------------------------------------

    @Test
    fun `real boxes with a bad plaintext are refused and no nonce is asked`() {
        assertRealRefused("local source box", hex(UnsealBoxData.localBoxHex))
        assertRealRefused("out of order box", hex(UnsealBoxData.orderBoxHex))
        assertRealRefused("non UTF-8 box", hex(UnsealBoxData.nonUtf8BoxHex))
        assertRealRefused("empty box of 48 bytes", hex(UnsealBoxData.emptyBoxHex))
    }

    @Test
    fun `a wrong recipient key is refused and no nonce is asked`() {
        val wrong = BoxPrivateKey(hex(UnsealBoxData.wrongRecipientPrivateKeyHex))

        assertRealRefused("wrong recipient key", hex(UnsealBoxData.validBoxHex), wrong)
    }

    @Test
    fun `a flipped bit in each part of the valid box is refused and no nonce is asked`() {
        val box = hex(UnsealBoxData.validBoxHex)

        assertRealRefused("flipped ephemeral key bit", flipBit(box, 7))
        assertRealRefused("flipped tag bit", flipBit(box, 40))
        assertRealRefused("flipped body bit", flipBit(box, 60))
    }

    // --- (3) the plaintext cap -----------------------------------------------------------------

    @Test
    fun `a plaintext of exactly 524288 bytes succeeds and 524289 is refused`() {
        val atCap = bodyOfSize(524288)
        val expected = String(atCap, Charsets.UTF_8)
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(atCap)

        val result = seamService(nonces, fake).unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)

        val decrypted = CryptoServiceImpl().decrypt(result, dek, aad)
        assertEquals("crypto unseal: plaintext at the cap decrypts", expected, decrypted)
        assertEquals("crypto unseal: plaintext at the cap asks one nonce", 1, nonces.calls)
        assertZero("plaintext at the cap, array handed out", atCap)

        assertSeamRefused("plaintext of 524289 bytes", ByteArray(524289) { 'a'.code.toByte() })
    }

    @Test
    fun `a valid body of 524289 bytes is refused by the plaintext cap alone`() {
        val overCap = bodyOfSize(524289)
        assertEquals("crypto unseal: test body size", 524289, overCap.size)

        // This body is a valid R1 server body, so the strict reader would accept it: only the
        // plaintext cap can refuse it.
        assertSeamRefused("valid body of 524289 bytes", bodyOfSize(524289))
    }

    // --- (4) the strict reader through the seam ------------------------------------------------

    @Test
    fun `plaintexts the strict reader refuses are refused and zeroed`() {
        val good = body("x")
        val bytes = good.toByteArray(Charsets.UTF_8)
        val textIndex = body("a").indexOf("\"a\"") + 1
        val loneByte = body("a").toByteArray(Charsets.UTF_8)
        loneByte[textIndex] = 0xFF.toByte()
        val cases = linkedMapOf(
            "version 2" to good.replace("\"v\":1", "\"v\":2").toByteArray(Charsets.UTF_8),
            "missing key" to (
                "{\"v\":1,\"text\":\"x\",\"source\":\"server\",\"duration_ms\":1500," +
                    "\"created_at\":\"2026-11-01T12:00:00.000Z\"}"
                ).toByteArray(Charsets.UTF_8),
            "source local" to body("x", "local").toByteArray(Charsets.UTF_8),
            "wrong key order" to (
                "{\"source\":\"server\",\"v\":1,\"text\":\"x\",\"model\":\"small\"," +
                    "\"duration_ms\":1500,\"created_at\":\"2026-11-01T12:00:00.000Z\"}"
                ).toByteArray(Charsets.UTF_8),
            "extra key" to (good.dropLast(1) + ",\"extra\":1}").toByteArray(Charsets.UTF_8),
            "duplicate key" to
                good.replaceFirst("{\"v\":1,", "{\"v\":1,\"v\":1,").toByteArray(Charsets.UTF_8),
            "trailing data" to (good + "x").toByteArray(Charsets.UTF_8),
            "byte order mark" to (byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes),
            "lone non UTF-8 byte" to loneByte,
        )

        for ((label, plain) in cases) {
            assertSeamRefused(label, plain)
        }
    }

    // --- (5) zeroing on success ----------------------------------------------------------------

    @Test
    fun `the plaintext array is zeroed after a success`() {
        // The copies of the box and of the private key are made inside the service and are not
        // observable from outside, so they are not checked here; only the array the opener hands out is.
        val plain = body("x").toByteArray(Charsets.UTF_8)
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(plain)

        seamService(nonces, fake).unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)

        assertEquals("crypto unseal: success asks one nonce", 1, nonces.calls)
        assertZero("plaintext array after success", plain)
    }

    // --- (6) the box cap comes before the copy -------------------------------------------------

    @Test
    fun `a box above the cap is refused before the opener is reached`() {
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(ByteArray(1))

        assertPlainRefusal("box of the cap plus one byte", nonces) {
            seamService(nonces, fake).unsealAndReencrypt(
                SealedAgentResult(ByteArray(UNSEAL_BOX_MAX_BYTES + 1) { 1 }),
                recipientKey(),
                dek,
                aad,
            )
        }
        assertEquals("crypto unseal: oversized box must not reach the opener", 0, fake.calls)
    }

    @Test
    fun `a garbage box of exactly the cap is refused by the open and not by the cap`() {
        // A box of exactly the cap passes the service's cap check, so the seam IS reached: the real
        // opener then refuses it, because these bytes are not a sealed box. A box one byte over the cap
        // never reaches here at all (the test above). The opener answers with the real one, so the
        // refusal is the module's own and the mapping to the plain refusal is exercised.
        val nonces = CountingNonces(nonceOne)
        val opener = CountingRealOpener()

        assertPlainRefusal("garbage box of exactly the cap", nonces) {
            seamService(nonces, opener).unsealAndReencrypt(
                SealedAgentResult(ByteArray(UNSEAL_BOX_MAX_BYTES) { 1 }),
                recipientKey(),
                dek,
                aad,
            )
        }
        assertEquals("crypto unseal: garbage box must reach the opener once", 1, opener.calls)
    }

    /**
     * The control for the two tests below. A failure means the test sealer makes a wrong tag: the open
     * refuses the box before the opener, so the two tests below would prove nothing.
     */
    @Test
    fun `a box made by the test sealer passes the tag and reaches the opener`() {
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(ByteArray(1))
        val box = sealedBoxOf(ByteArray(64) { (it + 1).toByte() })

        assertPlainRefusal("sealed box of 64 bytes of plaintext", nonces) {
            seamService(nonces, fake).unsealAndReencrypt(SealedAgentResult(box), recipientKey(), dek, aad)
        }
        assertEquals("crypto unseal: a sealed box must reach the opener", 1, fake.calls)
    }

    /**
     * A failure that shows 1 call means the box cap is gone or too high: a genuinely sealed box one byte over
     * the cap passed the tag and reached the opener.
     */
    @Test
    fun `a genuinely sealed box one byte over the cap is refused by the cap before the opener`() {
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(ByteArray(1))
        val box = sealedBoxOf(ByteArray(UNSEAL_BOX_MAX_BYTES + 1 - 48) { 1 })
        assertEquals("crypto unseal: test box must be the cap plus one", UNSEAL_BOX_MAX_BYTES + 1, box.size)

        assertPlainRefusal("sealed box of the cap plus one byte", nonces) {
            seamService(nonces, fake).unsealAndReencrypt(SealedAgentResult(box), recipientKey(), dek, aad)
        }
        assertEquals("crypto unseal: oversized sealed box must not reach the opener", 0, fake.calls)
    }

    /**
     * A failure that shows 0 calls means the cap refuses a box of exactly the cap: the comparison is off by
     * one. The call is still refused, later, because the one byte of the fake is not a body.
     */
    @Test
    fun `a genuinely sealed box of exactly the cap passes the cap and reaches the opener`() {
        val nonces = CountingNonces(nonceOne)
        val fake = HandedOutOpener(ByteArray(1))
        val box = sealedBoxOf(ByteArray(UNSEAL_BOX_MAX_BYTES - 48) { 1 })
        assertEquals("crypto unseal: test box must be exactly the cap", UNSEAL_BOX_MAX_BYTES, box.size)

        assertPlainRefusal("sealed box of exactly the cap", nonces) {
            seamService(nonces, fake).unsealAndReencrypt(SealedAgentResult(box), recipientKey(), dek, aad)
        }
        assertEquals("crypto unseal: sealed box of the cap must reach the opener", 1, fake.calls)
    }

    // --- (7) the data key size, (8) no partial result ------------------------------------------

    @Test
    fun `a data key of the wrong size is refused and no nonce is asked`() {
        val nonces = CountingNonces(nonceOne)
        val shortKey = DataEncryptionKey(ByteArray(31) { 1 })

        assertPlainRefusal("data key of 31 bytes", nonces) {
            realService(nonces).unsealAndReencrypt(validSealed(), recipientKey(), shortKey, aad)
        }
    }

    @Test
    fun `every refusal throws so no result value exists`() {
        val nonces = CountingNonces(nonceOne)
        var result: CipherText? = null

        assertThrows("crypto unseal: a refusal must throw", CryptoRefused::class.java) {
            result = realService(nonces).unsealAndReencrypt(
                SealedAgentResult(hex(UnsealBoxData.localBoxHex)),
                recipientKey(),
                dek,
                aad,
            )
        }
        assertNull("crypto unseal: a refusal must leave no result", result)
    }

    // --- (9) the public constructor, (10) the constants ----------------------------------------

    @Test
    fun `the public constructor unseals the valid real box`() {
        val expected = String(hex(UnsealBoxData.validPlainHex), Charsets.US_ASCII)

        val result = AccountKeyServiceImpl().unsealAndReencrypt(validSealed(), recipientKey(), dek, aad)

        val decrypted = CryptoServiceImpl().decrypt(result, dek, aad)
        assertEquals("crypto unseal: default service decrypts", expected, decrypted)
        assertEquals("crypto unseal: default service nonce size", 12, result.nonce.size)
    }

    @Test
    fun `the two caps are the literal numbers`() {
        assertEquals("crypto unseal: plaintext cap", 524288, UNSEAL_PLAINTEXT_MAX_BYTES)
        assertEquals("crypto unseal: box cap", 524336, UNSEAL_BOX_MAX_BYTES)
    }
}
