package dev.breaker.dictation.seal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SealedBox.seal], the sender's half of the sealed box.
 *
 * The three seal rows of [SealVectorData] are real libsodium `crypto_box_seal` output made to a
 * fixed recipient key pair with a fixed ephemeral key, so sealing the row's plain bytes with the
 * row's ephemeral private key must reproduce the row's box byte for byte. That is the
 * [EphemeralSource] seam: the only randomness, replaced here by a fixed key.
 */
class SealedBoxSealerTest {
    /** The recipient of the vector rows: the RFC 7748 6.1 Alice key pair. */
    private val recipientPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val recipientPublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

    /** A cap the vector rows and the probe plaintexts fit under. */
    private val cap = 4096

    /** The ephemeral private key of the first two vector rows. */
    private val ephemeralPrivate = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")

    /** An ephemeral source that returns the same private key every call, as a fresh copy. */
    private class FixedEphemeral(private val key: ByteArray) : EphemeralSource {
        override fun next(): ByteArray = key.copyOf()
    }

    /** An ephemeral source that remembers the array it handed out, so a test can inspect it afterwards. */
    private class ZeroingProbe(private val key: ByteArray) : EphemeralSource {
        var last: ByteArray? = null

        override fun next(): ByteArray {
            last = key.copyOf()
            return last!!
        }
    }

    /** The sealer under test: the real body cipher and a fixed ephemeral source. */
    private fun sealerWith(ephemeralPrivate: ByteArray): SealedBox =
        SealedBox(DefaultBodyCipher, FixedEphemeral(ephemeralPrivate))

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex text must have an even length" }
        return ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    /** A failure means the sealer does not reproduce the libsodium box of a vector row. */
    @Test
    fun `every seal vector row reproduces its box byte for byte`() {
        for (v in SealVectorData.seal) {
            val plain = hex(v.plainHex)
            val box = sealerWith(hex(v.ephemeralPrivateHex)).seal(hex(v.recipientPublicHex), plain, plain.size)

            assertArrayEquals("shared_seal: seal vector ${v.name}: box bytes", hex(v.boxHex), box)
            assertArrayEquals(
                "shared_seal: seal vector ${v.name}: the box starts with the ephemeral public key",
                hex(v.ephemeralPublicHex),
                box.copyOfRange(0, 32),
            )
            assertEquals("shared_seal: seal vector ${v.name}: box size", plain.size + 48, box.size)
        }
    }

    /** A failure means a plaintext over the caller's cap was sealed, or one at the cap was refused. */
    @Test
    fun `a plaintext at the cap seals and one byte over the cap refuses`() {
        val sealed = sealerWith(ephemeralPrivate).seal(recipientPublic, ByteArray(cap), cap)
        assertEquals("shared_seal: a plaintext at the cap must seal", cap + 48, sealed.size)

        assertThrows(
            "shared_seal: a plaintext one byte over the cap must be refused",
            SealRefused::class.java,
        ) {
            sealerWith(ephemeralPrivate).seal(recipientPublic, ByteArray(cap + 1), cap)
        }
    }

    /** H6: the sealer refuses an empty plaintext, so it can never make the 48-byte box the intake route refuses. */
    @Test
    fun `an empty plaintext is refused`() {
        assertThrows("shared_seal: an empty plaintext must be refused", SealRefused::class.java) {
            sealerWith(ephemeralPrivate).seal(recipientPublic, ByteArray(0), cap)
        }
    }

    /** A failure means a low-order recipient key (an all-zero shared secret) was sealed. */
    @Test
    fun `an all zero shared secret is refused`() {
        assertThrows("shared_seal: an all-zero recipient public key must be refused", SealRefused::class.java) {
            sealerWith(ephemeralPrivate).seal(ByteArray(32), ByteArray(8), cap)
        }
    }

    /** A failure means a recipient public key of the wrong length was sealed. */
    @Test
    fun `a recipient public key that is not 32 bytes is refused`() {
        for (size in intArrayOf(0, 31, 33)) {
            assertThrows("shared_seal: a recipient public key of $size bytes must be refused", SealRefused::class.java) {
                sealerWith(ephemeralPrivate).seal(ByteArray(size), ByteArray(8), cap)
            }
        }
    }

    /** A failure means the sealed box does not open back to the plaintext, or the layout is wrong. */
    @Test
    fun `a sealed box opens back to its plaintext`() {
        val plain = hex("7b2276223a312c2274657874223a2268656c6c6f227d")
        val box = sealerWith(ephemeralPrivate).seal(recipientPublic, plain, cap)

        assertEquals("shared_seal: the box must be the plaintext plus 48 bytes", plain.size + 48, box.size)
        assertArrayEquals("shared_seal: the box must open to the plaintext", plain, SealedBox().open(box, recipientPrivate))
    }

    /** A failure means the ephemeral private key was left in memory after sealing. */
    @Test
    fun `the ephemeral private key is zeroed after use`() {
        val probe = ZeroingProbe(ephemeralPrivate)

        SealedBox(DefaultBodyCipher, probe).seal(recipientPublic, ByteArray(8), cap)

        assertTrue(
            "shared_seal: the ephemeral private key must be zeroed after use",
            probe.last != null && probe.last!!.all { it == 0.toByte() },
        )
    }

    /** A failure means a refusal row opened, so the module's copy of the table no longer matches the JSON. */
    @Test
    fun `every seal refusal row is refused by open`() {
        for (r in SealVectorData.sealRefusals) {
            assertThrows("shared_seal: seal refusal ${r.name}: must be refused", SealRefused::class.java) {
                SealedBox().open(hex(r.boxHex), hex(r.recipientPrivateHex))
            }
        }
    }
}
