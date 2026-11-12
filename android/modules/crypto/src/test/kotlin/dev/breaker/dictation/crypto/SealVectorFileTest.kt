package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the shared sealed box vectors (the same values as the seal and seal_refusals sections of
 * examples/crypto_vectors.json, copied in [CryptoVectorData]) against [SealedBoxOpener] and
 * [SignInPackage]. Every box was made by libsodium and opened by libsodium before it was written.
 * The website proves the same file, so the two sides agree.
 */
class SealVectorFileTest : CryptoServiceTestFixture() {
    private fun sealNamed(name: String): SealVector = CryptoVectorData.seal.single { it.name == name }

    private fun openBox(box: String, recipientPrivate: String): ByteArray =
        SealedBoxOpener().open(hex(box), hex(recipientPrivate))

    /** The number of bits in which two arrays of the same size differ. */
    private fun differingBits(a: ByteArray, b: ByteArray): Int {
        require(a.size == b.size) { "arrays must have the same size" }
        var count = 0
        for (i in a.indices) count += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xff)
        return count
    }

    /** A failure means a sealed box does not open to its plain bytes, or is not shaped as sealed. */
    @Test
    fun `every seal vector opens to its plain bytes and is shaped as a sealed box`() {
        for (v in CryptoVectorData.seal) {
            val opened = openBox(v.boxHex, v.recipientPrivateHex)
            assertArrayEquals("seal vector ${v.name}: plain bytes", hex(v.plainHex), opened)

            val box = hex(v.boxHex)
            val front = box.copyOfRange(0, 32)
            assertArrayEquals(
                "seal vector ${v.name}: box starts with the ephemeral key",
                hex(v.ephemeralPublicHex),
                front,
            )
            assertEquals("seal vector ${v.name}: box size", hex(v.plainHex).size + 48, box.size)
        }
    }

    /** A failure means the sign-in package box no longer opens to the two keys it holds. */
    @Test
    fun `the sign-in package vector opens to the data key and the box private key`() {
        val v = sealNamed("signin-package-65")
        val plain = hex(v.plainHex)
        assertEquals("seal vector ${v.name}: plain size", 65, plain.size)

        val keys = SignInPackage.parse(openBox(v.boxHex, v.recipientPrivateHex))

        assertArrayEquals("seal vector ${v.name}: data key", plain.copyOfRange(1, 33), keys.dek.bytes)
        val boxKey = keys.boxPrivateKey.copyBytes()
        assertArrayEquals("seal vector ${v.name}: box key", plain.copyOfRange(33, 65), boxKey)
    }

    /**
     * A failure means a valid box whose body says source local stopped opening. The box is valid, so
     * it opens here; the strict R1 reader is the one that refuses such a body, elsewhere.
     */
    @Test
    fun `the source local body box is valid and opens to a body that says local`() {
        val v = sealNamed("r1-source-local-body")

        val text = String(openBox(v.boxHex, v.recipientPrivateHex), Charsets.US_ASCII)

        assertTrue("seal vector ${v.name}: body text was $text", text.contains("\"source\":\"local\""))
    }

    /** A failure means a refusal box opened, failed another way, or carried a cause. */
    @Test
    fun `every seal refusal is the one plain refusal with no cause`() {
        for (r in CryptoVectorData.sealRefusals) {
            val e = assertThrows("seal refusal ${r.name}: must be refused", CryptoRefused::class.java) {
                openBox(r.boxHex, r.recipientPrivateHex)
            }
            assertEquals("seal refusal ${r.name}: message", CryptoRefused().message, e.message)
            assertNull("seal refusal ${r.name}: cause", e.cause)
        }
    }

    /** A failure means a row was dropped from the seal table or from the refusal table. */
    @Test
    fun `the seal tables keep their row counts`() {
        assertEquals("seal vector rows", 3, CryptoVectorData.seal.size)
        assertEquals("seal refusal rows", 4, CryptoVectorData.sealRefusals.size)
    }

    /** A failure means a refusal is not what its name says: another recipient, or one flipped bit. */
    @Test
    fun `each seal refusal is a wrong recipient or exactly one flipped bit of a seal box`() {
        for (r in CryptoVectorData.sealRefusals) {
            val box = hex(r.boxHex)
            if (r.name.endsWith("-wrong-recipient")) {
                val owners = CryptoVectorData.seal.filter { it.boxHex == r.boxHex }
                assertEquals("seal refusal ${r.name}: seal entries with the same box", 1, owners.size)
                assertNotEquals(
                    "seal refusal ${r.name}: recipient key equals the entry's own",
                    owners[0].recipientPrivateHex,
                    r.recipientPrivateHex,
                )
            } else if (r.name.endsWith("-flipped-bit")) {
                val owners = CryptoVectorData.seal.filter {
                    it.boxHex.length == r.boxHex.length && differingBits(hex(it.boxHex), box) == 1
                }
                assertEquals("seal refusal ${r.name}: seal entries one bit away", 1, owners.size)
                assertEquals(
                    "seal refusal ${r.name}: recipient key must be the entry's own",
                    owners[0].recipientPrivateHex,
                    r.recipientPrivateHex,
                )
            } else {
                throw AssertionError("seal refusal ${r.name}: its name must end in a known kind")
            }
        }
    }
}
