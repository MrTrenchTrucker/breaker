package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Known-answer tests for [hsalsa20], the one primitive of the sealed-box opener that Bouncy
 * Castle does not have.
 *
 * Both expected values come from outside this code. The first was made by libsodium
 * (`crypto_box_beforenm`, through PyNaCl 1.6.2) from a shared secret that the separate
 * `cryptography` library computed. The second is the published NaCl HSalsa20 core vector.
 * Neither is recomputed here.
 */
class HSalsa20Test {
    /** The X25519 shared secret of two fixed key pairs, computed by the `cryptography` library. */
    private val sharedSecret = hex(
        "f988_0973_9994_2d0b_c38e_b644_76b4_75a0",
        "5429_acd2_d9f2_799d_d4f1_4c04_646a_d77a",
    )

    /** What libsodium crypto_box_beforenm returns for that shared secret. */
    private val beforenmExpected = hex(
        "8f24_f236_ec84_9b15_49d5_089a_b59a_53e7",
        "b640_6490_59f3_b979_7fd4_4979_845c_da9a",
    )

    /** The published NaCl core vector: key, 16-byte input and the 32-byte output. */
    private val naclCoreKey = hex(
        "1b27_5564_73e9_85d4_62cd_5119_7a9a_46c7",
        "6009_549e_ac64_74f2_06c4_ee08_44f6_8389",
    )
    private val naclCoreInput = hex("6969_6ee9_55b6_2b73_cd62_bda8_75fc_73d6")
    private val naclCoreOutput = hex(
        "dc90_8dda_0b93_44a9_5362_9b73_3820_7788",
        "80f3_ceb4_21bb_61b9_1cbd_4c3e_6625_6ce4",
    )

    /**
     * A failure means hsalsa20 is not what libsodium computes for a sealed box: a final
     * addition of the input state, a wrong output word, a wrong round count or a wrong state
     * layout all change the 32 bytes. This pins hsalsa20 alone, because nothing else sits
     * between the shared secret and the output here (no BLAKE2b, no Poly1305, no tag), and
     * the shared secret is not a value a wrong layout could happen to map to the same bytes.
     */
    @Test
    fun `hsalsa20 of a shared secret gives the libsodium beforenm key`() {
        val actual = hsalsa20(sharedSecret, ByteArray(16))

        assertArrayEquals(
            "crypto module: hsalsa20 must equal libsodium crypto_box_beforenm for the same shared secret",
            beforenmExpected,
            actual,
        )
    }

    /** A failure means hsalsa20 differs from the published NaCl vector, which has a non-zero input. */
    @Test
    fun `hsalsa20 reproduces the published NaCl core vector`() {
        val actual = hsalsa20(naclCoreKey, naclCoreInput)

        assertArrayEquals(
            "crypto module: hsalsa20 must reproduce the published NaCl HSalsa20 vector",
            naclCoreOutput,
            actual,
        )
    }

    @Test
    fun `hsalsa20 refuses a key that is not 32 bytes`() {
        for (size in intArrayOf(0, 31, 33)) {
            val e = assertThrows("crypto module: a key of $size bytes must be refused", CryptoRefused::class.java) {
                hsalsa20(ByteArray(size), ByteArray(16))
            }
            assertEquals("crypto module: the refusal must be the plain one", CryptoRefused().message, e.message)
        }
    }

    @Test
    fun `hsalsa20 refuses an input that is not 16 bytes`() {
        for (size in intArrayOf(0, 15, 17)) {
            val e = assertThrows("crypto module: an input of $size bytes must be refused", CryptoRefused::class.java) {
                hsalsa20(ByteArray(32), ByteArray(size))
            }
            assertEquals("crypto module: the refusal must be the plain one", CryptoRefused().message, e.message)
        }
    }

    /** A failure means hsalsa20 changed the caller's arrays or returned something that is not 32 bytes. */
    @Test
    fun `hsalsa20 returns 32 bytes and leaves the arguments as they were`() {
        val key = naclCoreKey.copyOf()
        val input = naclCoreInput.copyOf()

        val actual = hsalsa20(key, input)

        assertEquals("crypto module: hsalsa20 must return 32 bytes", 32, actual.size)
        assertArrayEquals("crypto module: hsalsa20 must not change the key it is given", naclCoreKey, key)
        assertArrayEquals("crypto module: hsalsa20 must not change the input it is given", naclCoreInput, input)
    }

    /** Joins the parts, drops the underscores that keep every digit run short, and reads hex. */
    private fun hex(vararg parts: String): ByteArray {
        val text = parts.joinToString("").replace("_", "")
        return ByteArray(text.length / 2) { i ->
            ((hexDigit(text[i * 2]) shl 4) or hexDigit(text[i * 2 + 1])).toByte()
        }
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
