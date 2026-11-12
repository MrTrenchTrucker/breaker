package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.WrappedDek
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the shared crypto vectors (the same values as examples/crypto_vectors.json, copied in
 * [CryptoVectorData]) against the phone code: the Argon2id and HKDF split, the wrapped data key
 * and the X25519 public key. The website proves the same file, so the two sides agree.
 */
class CryptoVectorFileTest : CryptoServiceTestFixture() {
    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return joinToString("") { b ->
            val v = b.toInt() and 0xff
            "" + digits[v shr 4] + digits[v and 0x0f]
        }
    }

    private fun kdfNamed(name: String): KdfVector = CryptoVectorData.kdf.single { it.name == name }

    private fun wrapNamed(name: String): WrapVector = CryptoVectorData.wrap.single { it.name == name }

    /** The wrap entries that unwrapDek can open: it uses empty associated data. */
    private val emptyAadWraps: List<WrapVector> get() = CryptoVectorData.wrap.filter { it.aadHex.isEmpty() }

    /** The platform cipher used directly: AES-256-GCM, the first 12 bytes of the blob are the nonce. */
    private fun openDirect(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, blob.copyOfRange(0, 12))
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    /** A failure means the Argon2id output, the key or the verifier is not the shared value. */
    @Test
    fun `every kdf vector gives the shared argon2id, key and verifier`() {
        for (v in CryptoVectorData.kdf) {
            val salt = hex(v.saltHex)
            val tag = argon2id(
                passwordBytes(v.password), salt, v.memoryKib, v.iterations, v.parallelism, 32,
            )
            assertEquals("crypto vector ${v.name}: argon2id", v.argon2idHex, tag.toHex())

            val params = KdfParams(v.memoryKib, v.iterations, v.parallelism, 32, 1)
            val keys = KdfImpl().deriveKeys(v.password.toCharArray(), salt, params)
            assertEquals("crypto vector ${v.name}: key", v.kekHex, keys.kek.copyBytes().toHex())
            val verifierHex = keys.authVerifier.copyBytes().toHex()
            assertEquals("crypto vector ${v.name}: verifier", v.verifierHex, verifierHex)
        }
    }

    /** A failure means the data table lost the rule that both spellings give the same three values. */
    @Test
    fun `the composed and decomposed vectors hold the same three values`() {
        val composed = kdfNamed("v2-composed")
        val decomposed = kdfNamed("v2-decomposed")
        assertEquals("crypto vector v2: argon2id differs", composed.argon2idHex, decomposed.argon2idHex)
        assertEquals("crypto vector v2: key strings differ", composed.kekHex, decomposed.kekHex)
        assertEquals("crypto vector v2: verifier differs", composed.verifierHex, decomposed.verifierHex)
    }

    /** A failure means the shared wrapped data key does not open to the shared plain key. */
    @Test
    fun `the wrapped data key opens to the shared plain key`() {
        for (v in emptyAadWraps) {
            val kek = KeyEncryptionKey(hex(v.wrapperKeyHex))
            val opened = CryptoServiceImpl().unwrapDek(wrapped(hex(v.wrappedHex)), kek)
            assertArrayEquals("crypto vector ${v.name}: plain key", hex(v.plainHex), opened.bytes)
        }
    }

    /** A failure means a wrapped key with one damaged byte, or a wrong wrapper key, was accepted. */
    @Test
    fun `the wrapped data key is refused with one byte flipped in it or in the wrapper key`() {
        for (v in emptyAadWraps) {
            val damaged = hex(v.wrappedHex)
            damaged[40] = (damaged[40].toInt() xor 1).toByte()
            refused("crypto vector ${v.name}: a flipped wrapped byte was accepted") {
                val kek = KeyEncryptionKey(hex(v.wrapperKeyHex))
                CryptoServiceImpl().unwrapDek(WrappedDek(damaged, ByteArray(16) { 7 }), kek)
            }

            val wrongKey = hex(v.wrapperKeyHex)
            wrongKey[0] = (wrongKey[0].toInt() xor 1).toByte()
            refused("crypto vector ${v.name}: a flipped wrapper key byte was accepted") {
                CryptoServiceImpl().unwrapDek(wrapped(hex(v.wrappedHex)), KeyEncryptionKey(wrongKey))
            }
        }
    }

    /** A failure means a wrap entry does not open with its own associated data to its plain key. */
    @Test
    fun `every wrap vector opens with its own associated data to its plain key`() {
        for (v in CryptoVectorData.wrap) {
            val opened = openDirect(hex(v.wrapperKeyHex), hex(v.wrappedHex), hex(v.aadHex))
            assertArrayEquals("crypto vector ${v.name}: plain key", hex(v.plainHex), opened)
            val nonce = hex(v.wrappedHex).copyOfRange(0, 12)
            assertArrayEquals("crypto vector ${v.name}: nonce", hex(v.nonceHex), nonce)
        }
    }

    /** A failure means a purpose string changed, or the dek entry gained associated data. */
    @Test
    fun `the associated data of each wrap vector is its purpose string`() {
        val box = String(hex(wrapNamed("box-private-key-under-dek").aadHex), Charsets.US_ASCII)
        val prev = String(hex(wrapNamed("previous-dek-under-new-dek").aadHex), Charsets.US_ASCII)
        assertEquals("crypto vector box-private-key-under-dek: aad", "breaker-box-privkey-v1", box)
        assertEquals("crypto vector previous-dek-under-new-dek: aad", "breaker-prev-dek-v1", prev)
        assertEquals("crypto vector dek-under-kek: aad", "", wrapNamed("dek-under-kek").aadHex)
    }

    /** A failure means a refusal vector opened, failed another way, or does not match a wrap entry. */
    @Test
    fun `every wrap refusal fails the tag check and belongs to one wrap entry`() {
        for (r in CryptoVectorData.wrapRefusals) {
            val message = "crypto vector ${r.name}: it must fail the tag check"
            assertThrows(message, AEADBadTagException::class.java) {
                openDirect(hex(r.wrapperKeyHex), hex(r.wrappedHex), hex(r.aadHex))
            }
            val owners = CryptoVectorData.wrap.filter { it.wrappedHex == r.wrappedHex }
            assertEquals("crypto vector ${r.name}: wrap entries with the same blob", 1, owners.size)
            assertNotEquals("crypto vector ${r.name}: aad equals its entry", owners[0].aadHex, r.aadHex)
        }
    }

    /** A failure means the X25519 public key of a shared private key is not the shared public key. */
    @Test
    fun `every x25519 vector gives the shared public key`() {
        for (v in CryptoVectorData.x25519) {
            var asked = 0
            val starter = SignInStarterImpl { size ->
                asked++
                if (asked == 1) ByteArray(size) else hex(v.privateHex)
            }
            val attempt = starter.begin()
            val publicKey = attempt.copyPublicKey()
            assertArrayEquals("crypto vector ${v.name}: public key", hex(v.publicHex), publicKey)
        }
    }
}
