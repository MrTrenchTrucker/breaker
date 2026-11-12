package dev.breaker.dictation.crypto

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the near miss refusal vector: its associated data is the real box text with the last
 * character changed, so it refuses only because of that one character and not because it is some
 * other, unrelated text.
 */
class WrapRefusalAadPinTest : CryptoServiceTestFixture() {
    private val boxName = "box-private-key-under-dek"

    private fun nearMissRefusals(): List<WrapRefusal> =
        CryptoVectorData.wrapRefusals.filter {
            it.name.contains("other-purpose") || it.name.contains("near-miss")
        }

    /** A failure here means the near miss aad is not the box aad with exactly its last byte changed. */
    @Test
    fun `the other purpose aad differs from the box aad in the last byte only`() {
        val real = aadBytes(BOX_PRIVATE_KEY_AAD_TEXT)
        val near = nearMissRefusals()
        assertEquals("android_crypto: the number of near miss refusals", 1, near.size)
        for (r in near) {
            val aad = hex(r.aadHex)
            assertEquals("android_crypto vector ${r.name}: aad length", real.size, aad.size)
            val differing = real.indices.filter { real[it] != aad[it] }
            assertEquals("android_crypto vector ${r.name}: differing bytes", listOf(real.size - 1), differing)
            assertEquals("android_crypto vector ${r.name}: real last byte", 0x31, real[real.size - 1].toInt())
            assertEquals("android_crypto vector ${r.name}: last byte", 0x32, aad[aad.size - 1].toInt())
            val text = String(aad, Charsets.US_ASCII)
            assertEquals("android_crypto vector ${r.name}: aad text", "breaker-box-privkey-v2", text)
        }
    }

    /** A failure here means the v1 blob does not open under the real aad, or opens under the near miss. */
    @Test
    fun `the box blob opens under the real aad and fails the tag under the near miss aad`() {
        val box = CryptoVectorData.wrap.single { it.name == boxName }
        val r = nearMissRefusals().single()
        val key = hex(r.wrapperKeyHex)
        val blob = hex(r.wrappedHex)
        assertArrayEquals("android_crypto: control, the blob is the box vector", hex(box.wrappedHex), blob)

        val opened = openKey(blob, key, aadBytes(BOX_PRIVATE_KEY_AAD_TEXT))
        assertArrayEquals("android_crypto: control, the real aad opens it", hex(box.plainHex), opened)
        assertThrows("android_crypto: the near miss aad opened the blob", AEADBadTagException::class.java) {
            openKey(blob, key, hex(r.aadHex))
        }
    }
}
