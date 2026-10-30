package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefusal.BAD_SALT_LENGTH
import dev.breaker.dictation.core.model.KdfRefusal.ITERATIONS_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.ITERATIONS_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.MEMORY_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.MEMORY_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.OUTPUT_LENGTH_NOT_32
import dev.breaker.dictation.core.model.KdfRefusal.PARALLELISM_ABOVE_CEILING
import dev.breaker.dictation.core.model.KdfRefusal.PARALLELISM_BELOW_FLOOR
import dev.breaker.dictation.core.model.KdfRefusal.UNSUPPORTED_VERSION
import dev.breaker.dictation.core.model.KdfRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Known-answer tests for [KdfImpl], the Argon2id + HKDF-SHA256 derivation.
 *
 * The expected values are the R21 vectors, produced by independent oracles (argon2-cffi
 * and PyNaCl for Argon2id, the standard-library HMAC for HKDF), not recomputed here.
 * V1 and V2 are at the ADR-006 floor (64 MiB, 3 passes, 1 lane); V3 uses a different
 * accepted cost (128 MiB, 4 passes) to prove the caller's parameters reach Argon2id
 * unchanged. V2's password is the same "caf e with an accent" spelled composed and decomposed, and both
 * spellings must derive the same bytes.
 *
 * RED first. Every test names [KdfImpl], [argon2id], [passwordBytes] or
 * [checkKdfParams], none of which exist until [KdfImpl] is written. On a tree without
 * the implementation the test source set does not compile, which is the RED state here
 * (nothing is compiled in the container); the known-answer assertions above are what
 * then run green against the real Bouncy Castle Argon2id and HKDF on the proof rig.
 */
class KdfImplTest {

    private class R21Vector(
        val password: String,
        val saltHex: String,
        val params: KdfParams,
        val argon2id: String,
        val kek: String,
        val verifier: String,
    )

    private val v1Main = R21Vector(
        password = "correct horse battery staple",
        saltHex = "000102030405060708090a0b0c0d0e0f",
        params = KdfParams(65536, 3, 1, 32, 1),
        argon2id = "0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e",
        kek = "76a6b3fae64de941e43727dc27e4a5ffa76edf3419397ea82e4e8a0efafe733e",
        verifier = "1de335d09f78dc3756cedc0468908cda3c406eb1c28dd9a220c5a94320116a6b",
    )

    private val v3AltParams = R21Vector(
        password = "breaker unit test three",
        saltHex = "101112131415161718191a1b1c1d1e1f",
        params = KdfParams(131072, 4, 1, 32, 1),
        argon2id = "448cd664f1186c5925617160b2ea8fb4100d713bdda1cce9722f9b29001ac410",
        kek = "5afc922cafa44ad28538317144cdbacf3d85fea4988b4af65785da0edd53d84a",
        verifier = "d9d46aab9b89cc743a6cecc1aadd955d76bf3129d72757cf7a50f818364d0070",
    )

    /** The "caf e with an accent" password the two ways it can arrive from a keyboard. */
    private val v2Composed = "caf\u00E9" // c a f e with an accent (U+00E9)
    private val v2Decomposed = "cafe\u0301" // c a f e + U+0301 combining acute

    private val v2Argon2id = "fc711035f0842d8457a49821412dd7b5cb177b60dc8d6d4f7a958df34c47e927"
    private val v2Kek = "72f9ce458994fcd7beb55f1fa4d305997ca289a542915db54f2300b40040db04"
    private val v2Verifier = "d19a9dfc73751af0e7106f60ff33ef0d6ef38ab6970e3bf5ea1345f29d4983fd"

    /**
     * The parallelism-2 known-answer vector. ADR-006 accepts parallelism 1 to 4, so a lane
     * count other than 1 is in scope, but V1-V3 only ever ran one lane. The three hex values
     * are the independent oracle, not recomputed here: argon2-cffi 25.1.0 for Argon2id and
     * RFC 5869 HKDF in Python for the split. They are not produced by KdfImpl or by Bouncy Castle.
     */
    private val p2Argon2id = "80d08e5c4456f32f427938aa21088a6df6141ab0f0ced7b9ddbe14e5cfce9a7f"
    private val p2Kek = "d617353ac58a55671b5b4459a89554b404c0477bb3d04278c64134f89f6a551d"
    private val p2Verifier = "a8ba9a3b92b71c4518c0bee3cc133488d69a1db08fa48ea898bb0995ea9174b5"

    private fun salt(hex: String): ByteArray = hexToBytes(hex)

    private fun DerivedKeys.kekHex() = kek.copyBytes().toHex()

    private fun DerivedKeys.verifierHex() = authVerifier.copyBytes().toHex()

    /** A failure means the Argon2id output, the key or the verifier is not the independent value. */
    @Test
    fun `V1 main gives the independent argon2id, key and verifier`() {
        val v = v1Main
        val tag = argon2id(
            passwordBytes(v.password), salt(v.saltHex),
            v.params.memoryKib, v.params.iterations, v.params.parallelism, v.params.outputLength,
        )
        assertEquals("V1 argon2id", v.argon2id, tag.toHex())

        val keys = KdfImpl().deriveKeys(v.password, salt(v.saltHex), v.params)
        assertEquals("V1 key-encryption key", v.kek, keys.kekHex())
        assertEquals("V1 verifier", v.verifier, keys.verifierHex())
        assertNotEquals("V1 key and verifier differ", v.kek, v.verifier)
    }

    /**
     * A failure means the composed and decomposed spellings did not normalise to the
     * same bytes, or the split is not the independent value. A dropped NFC step gives
     * the decomposed bytes as-is (63 61 66 65 cc 81) and so other values.
     */
    @Test
    fun `V2 nfc composed and decomposed give the same independent argon2id, key and verifier`() {
        val composedBytes = "636166c3a9"
        assertEquals("composed spells to those bytes", composedBytes, passwordBytes(v2Composed).toHex())
        assertEquals("decomposed normalises to the same bytes", composedBytes, passwordBytes(v2Decomposed).toHex())

        val saltBytes = salt("101112131415161718191a1b1c1d1e1f")
        val params = KdfParams(65536, 3, 1, 32, 1)

        val fromComposed = argon2id(passwordBytes(v2Composed), saltBytes, 65536, 3, 1, 32).toHex()
        val fromDecomposed = argon2id(passwordBytes(v2Decomposed), saltBytes, 65536, 3, 1, 32).toHex()
        assertEquals("argon2id for the composed spelling", v2Argon2id, fromComposed)
        assertEquals("argon2id for the decomposed spelling", v2Argon2id, fromDecomposed)
        assertEquals("the two spellings derive the same tag", fromComposed, fromDecomposed)

        val keysComposed = KdfImpl().deriveKeys(v2Composed, saltBytes, params)
        val keysDecomposed = KdfImpl().deriveKeys(v2Decomposed, saltBytes, params)
        assertEquals("key for the composed spelling", v2Kek, keysComposed.kekHex())
        assertEquals("key for the decomposed spelling", v2Kek, keysDecomposed.kekHex())
        assertEquals("verifier for the composed spelling", v2Verifier, keysComposed.verifierHex())
        assertEquals("verifier for the decomposed spelling", v2Verifier, keysDecomposed.verifierHex())
        assertEquals("the two spellings give the same key", keysComposed.kekHex(), keysDecomposed.kekHex())
        assertEquals("the two spellings give the same verifier", keysComposed.verifierHex(), keysDecomposed.verifierHex())
    }

    /** A failure means the caller's cost settings did not reach Argon2id, or the split is wrong. */
    @Test
    fun `V3 alt params gives the independent argon2id, key and verifier`() {
        val v = v3AltParams
        val tag = argon2id(
            passwordBytes(v.password), salt(v.saltHex),
            v.params.memoryKib, v.params.iterations, v.params.parallelism, v.params.outputLength,
        )
        assertEquals("V3 argon2id", v.argon2id, tag.toHex())

        val keys = KdfImpl().deriveKeys(v.password, salt(v.saltHex), v.params)
        assertEquals("V3 key-encryption key", v.kek, keys.kekHex())
        assertEquals("V3 verifier", v.verifier, keys.verifierHex())
    }

    /**
     * ADR-006 accepts parallelism 1 to 4, but V1-V3 only ran one lane; this pins parallelism 2 so a
     * hard-coded lane count cannot pass unseen. The three hex values are argon2-cffi 25.1.0 and RFC 5869 HKDF in Python, computed outside KdfImpl (not by KdfImpl or Bouncy Castle). A failure means
     * the two-lane Argon2id output, the split, or one HKDF label is not the value the independent
     * oracle gives.
     */
    @Test
    fun `parallelism two gives the independent argon2id, key and verifier`() {
        val password = "breaker-kdf-parallelism-two"
        val saltBytes = salt("000102030405060708090a0b0c0d0e0f")
        val params = KdfParams(65536, 3, 2, 32, 1)

        val tag = argon2id(passwordBytes(password), saltBytes, 65536, 3, 2, 32)
        assertEquals("parallelism-two argon2id", p2Argon2id, tag.toHex())

        val keys = KdfImpl().deriveKeys(password, saltBytes, params)
        assertEquals("parallelism-two key-encryption key", p2Kek, keys.kekHex())
        assertEquals("parallelism-two verifier", p2Verifier, keys.verifierHex())
        assertNotEquals("parallelism-two key and verifier differ", p2Kek, p2Verifier)
    }

    /** A failure means a refused setting was accepted, or reported with another reason. */
    @Test
    fun `memory below the floor is refused`() {
        assertRefused(MEMORY_BELOW_FLOOR, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65535, 3, 1, 32, 1))
    }

    /** A failure means the inclusive memory limits were refused. */
    @Test
    fun `memory at the floor and at the ceiling is accepted`() {
        val saltBytes = salt("000102030405060708090a0b0c0d0e0f")
        checkKdfParams(saltBytes, KdfParams(65536, 3, 1, 32, 1))
        checkKdfParams(saltBytes, KdfParams(262144, 3, 1, 32, 1))
    }

    /** A failure means a memory cost over the ceiling was accepted or reported with another reason. */
    @Test
    fun `memory above the ceiling is refused`() {
        assertRefused(MEMORY_ABOVE_CEILING, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(262145, 3, 1, 32, 1))
    }

    /** A failure means fewer than 3 passes were accepted or reported with another reason. */
    @Test
    fun `iterations below the floor are refused`() {
        assertRefused(ITERATIONS_BELOW_FLOOR, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 2, 1, 32, 1))
    }

    /** A failure means more than 10 passes were accepted or reported with another reason. */
    @Test
    fun `iterations above the ceiling are refused`() {
        assertRefused(ITERATIONS_ABOVE_CEILING, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 11, 1, 32, 1))
    }

    /** A failure means fewer than 1 lane was accepted or reported with another reason. */
    @Test
    fun `parallelism below the floor is refused`() {
        assertRefused(PARALLELISM_BELOW_FLOOR, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 3, 0, 32, 1))
    }

    /** A failure means more than 4 lanes were accepted or reported with another reason. */
    @Test
    fun `parallelism above the ceiling is refused`() {
        assertRefused(PARALLELISM_ABOVE_CEILING, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 3, 5, 32, 1))
    }

    /** A failure means an output length other than 32 was accepted or reported with another reason. */
    @Test
    fun `output length other than 32 is refused`() {
        assertRefused(OUTPUT_LENGTH_NOT_32, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 3, 1, 31, 1))
    }

    /** A failure means a salt that is not 16 bytes was accepted or reported with another reason. */
    @Test
    fun `a salt that is not 16 bytes is refused`() {
        assertRefused(BAD_SALT_LENGTH, ByteArray(15) { it.toByte() }, KdfParams(65536, 3, 1, 32, 1))
    }

    /** A failure means the version rule is missing, or a claimed version relaxed a range. */
    @Test
    fun `a version other than 1 is refused`() {
        assertRefused(UNSUPPORTED_VERSION, salt("000102030405060708090a0b0c0d0e0f"), KdfParams(65536, 3, 1, 32, 2))
    }

    /** A failure means the first bad setting was not the one reported. */
    @Test
    fun `everything bad at once reports the memory floor`() {
        assertRefused(MEMORY_BELOW_FLOOR, ByteArray(0), KdfParams(0, 0, 0, 0, 0))
    }

    /** A failure means the version was reported before the salt, so a version never relaxes a range. */
    @Test
    fun `only salt and version bad reports the salt before the version`() {
        assertRefused(BAD_SALT_LENGTH, ByteArray(17) { it.toByte() }, KdfParams(65536, 3, 1, 32, 7))
    }

    private fun assertRefused(reason: KdfRefusal, salt: ByteArray, params: KdfParams) {
        val e = assertThrows("refusal for $reason", KdfRefused::class.java) {
            KdfImpl().deriveKeys("correct horse battery staple", salt, params)
        }
        assertEquals("refusal reason for $reason", reason, e.reason)
    }

    // --- hex, local so this file is self-contained ------------------------------------------------

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return joinToString("") { b ->
            val v = b.toInt() and 0xff
            "" + digits[v shr 4] + digits[v and 0x0f]
        }
    }

    private fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        val hi = hexDigit(s[i * 2])
        val lo = hexDigit(s[i * 2 + 1])
        ((hi shl 4) or lo).toByte()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
