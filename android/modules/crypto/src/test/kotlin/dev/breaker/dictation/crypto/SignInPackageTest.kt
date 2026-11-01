package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for the sign-in package: its plaintext form and the way [CryptoServiceImpl] opens it.
 *
 * The plaintext is exactly 65 bytes: a version byte of 1, the 32-byte data key, then the 32-byte
 * box private key. The sealed boxes below were made by PyNaCl (libsodium `crypto_box_seal`) to a
 * fixed recipient key, and PyNaCl opened each one again before it was written here. They are
 * independent of the code under test.
 *
 * The first vector is the SHARED one, for the website page's own test. A sealed box is made with a
 * random ephemeral key, so the page cannot reproduce these bytes. What the page tests is to OPEN
 * this box with the secret key below and get the 65 bytes: 0x01, then the data key (counting up
 * from 0xA0), then the box private key (counting up from 0xC0). It also does its own
 * seal-then-open round trip to the public key below. Each hex value sits on one line, so that it
 * can be copied as it is.
 */
class SignInPackageTest {
    private val expectedDekHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
    private val expectedBoxKeyHex = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
    private val recipientPrivateKeyHex = "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"

    // The public key (X25519) that belongs to the secret key above, as libsodium derives it.
    private val recipientPublicKeyHex = "736845d54e87de09d6bb114aa7042c50a4a015bd9901d1a0026f5956533a1519"

    // The shared vector: a sealed box of the 65-byte plaintext, 113 bytes.
    private val sharedBoxHex = "27b7b04894d102d452bd163a817cd6da17377bdd6c9f51dce09e8879faaa5151ee584633ed27a95752a9ad8f465958cb673fe6b1fe22286d90a0b66580202b801dc88b10a41a31f2560311de012713eb048f3d9c55d5caabc02c4f04d6213956fb0402893de9481f41a7b667768a6c7bcc"

    // The same plaintext with the version byte 2, sealed to the same key. Opens fine, then refused.
    private val versionTwoBoxHex = "2773a47d17ca3b1da534b92511e6488bf719398782646c377163229f9c601936d95db20f174a0143ef6d9558b8372166a488961b15c1c51d65a742d036eae7a1019efa5b06e3cbe8434b55fcd746856f08589617a94f70daa03befd8e920e1ac1c0ad00bba145121f6af23d7e1c2c68edc"

    // A plaintext of 64 bytes (version 1, the data key, and only 31 bytes of box key), sealed to the same key. The box is 112 bytes.
    private val plaintext64BoxHex = "201354301122bf45b424e2056421385562c3e3043881fe831441300bcf4e021f305b9824cbfe58e2ba4838431b70742c0c0ab761d08750a45ab0e04a38bca3e52d6530dc2d0c5959a5d39b1f8399e3951ca68b8a4a3267071116501a9aba59dc89fe1636100521f03baf29fd8ddf831e"

    // A plaintext of 66 bytes (the 65 bytes and one more), sealed to the same key. The box is 114 bytes.
    private val plaintext66BoxHex = "05c2cd829afc5710945ee6ce4758fb5bdbde9b85d61f9ca60a8f9b9aa9c6ca50d1afd7c351169e761da44b1eb8b55f031da51ffacf1141edbb1286f8f26f4f4b92a7e674885630426309bb8f71f158a414e2bf7a6874a0eb85fe8cc4606cf733ccf7e206cd59a6a2a171910e46dd7e15b6b6"

    private val dekBytes = patterned(0xA0, 32)
    private val boxKeyBytes = patterned(0xC0, 32)

    private fun plaintext(version: Int, keyBytes: ByteArray = boxKeyBytes): ByteArray =
        byteArrayOf(version.toByte()) + dekBytes + keyBytes

    private fun recipientKey() = SignInPrivateKey(hex(recipientPrivateKeyHex))

    private fun refused(message: String, block: () -> Unit) {
        assertThrows(message, CryptoRefused::class.java) { block() }
    }

    // ---- the shared vector -------------------------------------------------------------------

    @Test
    fun `the shared sign-in vector opens to the expected data key and box private key`() {
        val keys = CryptoServiceImpl().openSignInPackage(SealedSignInPackage(hex(sharedBoxHex)), recipientKey())

        assertArrayEquals("crypto: the opened data key is not the shared vector's", hex(expectedDekHex), keys.dek.bytes)
        assertArrayEquals(
            "crypto: the opened box private key is not the shared vector's",
            hex(expectedBoxKeyHex),
            keys.boxPrivateKey.copyBytes(),
        )
    }

    @Test
    fun `opening leaves the sealed package and the private key object unchanged`() {
        val sealed = SealedSignInPackage(hex(sharedBoxHex))
        val recipient = recipientKey()

        CryptoServiceImpl().openSignInPackage(sealed, recipient)

        assertArrayEquals("crypto: opening changed the sealed package", hex(sharedBoxHex), sealed.copyBytes())
        assertArrayEquals("crypto: opening wiped the private key", hex(recipientPrivateKeyHex), recipient.copyBytes())
    }

    @Test
    fun `opening the shared vector twice gives the same keys`() {
        val service = CryptoServiceImpl()
        val sealed = SealedSignInPackage(hex(sharedBoxHex))

        val first = service.openSignInPackage(sealed, recipientKey())
        val second = service.openSignInPackage(sealed, recipientKey())

        assertArrayEquals("crypto: second open gave another data key", first.dek.bytes, second.dek.bytes)
        assertArrayEquals(
            "crypto: second open gave another box private key",
            first.boxPrivateKey.copyBytes(),
            second.boxPrivateKey.copyBytes(),
        )
    }

    // ---- refusals through the service --------------------------------------------------------

    @Test
    fun `a sealed package with version two is refused`() {
        refused("crypto: a package with version byte 2 was accepted") {
            CryptoServiceImpl().openSignInPackage(SealedSignInPackage(hex(versionTwoBoxHex)), recipientKey())
        }
    }

    @Test
    fun `a sealed package whose plaintext is 64 bytes is refused`() {
        refused("crypto: a 64-byte package plaintext was accepted") {
            CryptoServiceImpl().openSignInPackage(SealedSignInPackage(hex(plaintext64BoxHex)), recipientKey())
        }
    }

    @Test
    fun `a sealed package whose plaintext is 66 bytes is refused`() {
        refused("crypto: a 66-byte package plaintext was accepted") {
            CryptoServiceImpl().openSignInPackage(SealedSignInPackage(hex(plaintext66BoxHex)), recipientKey())
        }
    }

    @Test
    fun `a wrong sign-in private key is refused`() {
        refused("crypto: a package was opened with the wrong private key") {
            CryptoServiceImpl().openSignInPackage(
                SealedSignInPackage(hex(sharedBoxHex)),
                SignInPrivateKey(patterned(0xD0, 32)),
            )
        }
    }

    @Test
    fun `a sealed package with one flipped bit is refused`() {
        val damaged = hex(sharedBoxHex)
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 1).toByte()

        refused("crypto: a damaged sealed package was opened") {
            CryptoServiceImpl().openSignInPackage(SealedSignInPackage(damaged), recipientKey())
        }
    }

    // ---- the plaintext form, directly --------------------------------------------------------

    @Test
    fun `parse reads the data key from bytes 1 to 32 and the box key from bytes 33 to 64`() {
        val keys = SignInPackage.parse(plaintext(1))

        assertArrayEquals("crypto: parse took the wrong bytes for the data key", dekBytes, keys.dek.bytes)
        assertArrayEquals("crypto: parse took the wrong bytes for the box key", boxKeyBytes, keys.boxPrivateKey.copyBytes())
    }

    @Test
    fun `parse refuses 64 bytes 66 bytes and an empty array`() {
        refused("crypto: parse accepted 64 bytes") { SignInPackage.parse(plaintext(1).copyOf(64)) }
        refused("crypto: parse accepted 66 bytes") { SignInPackage.parse(plaintext(1) + byteArrayOf(0)) }
        refused("crypto: parse accepted an empty array") { SignInPackage.parse(ByteArray(0)) }
    }

    @Test
    fun `parse refuses version 0 and version 2 and a version byte with the top bit set`() {
        refused("crypto: parse accepted version 0") { SignInPackage.parse(plaintext(0)) }
        refused("crypto: parse accepted version 2") { SignInPackage.parse(plaintext(2)) }
        refused("crypto: parse accepted version 129") { SignInPackage.parse(plaintext(129)) }
    }

    @Test
    fun `parse zeroes its input after success and the keys it returned stay intact`() {
        val input = plaintext(1)

        val keys = SignInPackage.parse(input)

        assertArrayEquals("crypto: parse left the plaintext in memory after success", ByteArray(65), input)
        assertArrayEquals("crypto: zeroing the input damaged the data key", dekBytes, keys.dek.bytes)
        assertArrayEquals("crypto: zeroing the input damaged the box key", boxKeyBytes, keys.boxPrivateKey.copyBytes())
    }

    @Test
    fun `parse zeroes its input after a refusal`() {
        val wrongVersion = plaintext(2)
        val tooShort = plaintext(1).copyOf(64)
        val tooLong = plaintext(1) + byteArrayOf(9)

        refused("crypto: parse accepted version 2") { SignInPackage.parse(wrongVersion) }
        refused("crypto: parse accepted 64 bytes") { SignInPackage.parse(tooShort) }
        refused("crypto: parse accepted 66 bytes") { SignInPackage.parse(tooLong) }

        assertArrayEquals("crypto: a refused version-2 plaintext stayed in memory", ByteArray(65), wrongVersion)
        assertArrayEquals("crypto: a refused short plaintext stayed in memory", ByteArray(64), tooShort)
        assertArrayEquals("crypto: a refused long plaintext stayed in memory", ByteArray(66), tooLong)
    }

    @Test
    fun `the plaintext size and version constants are the ruled ones`() {
        assertEquals("crypto: the package plaintext size changed", 65, SignInPackage.SIZE_BYTES)
        assertEquals("crypto: the package format version changed", 1, SignInPackage.FORMAT_VERSION)
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex text must have an even length" }
        return ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    private companion object {
        /** [size] bytes counting up from [base], wrapping at 256. */
        fun patterned(base: Int, size: Int): ByteArray = ByteArray(size) { i -> (base + i).toByte() }
    }
}
