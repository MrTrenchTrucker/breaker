package dev.breaker.dictation.core.model

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A cipher text is a ciphertext, a nonce and a tag. Sealing an empty message
 * with AES-GCM yields zero ciphertext bytes plus the 16-byte tag, so a
 * cipher text with an empty ciphertext part is a real, decryptable value.
 *
 * The three vectors below are published AES-256-GCM known answers (NIST CAVP,
 * gcmEncryptExtIV256, key length 256, IV length 96, tag length 128, empty
 * plaintext, with 0, 128 and 160 bits of additional data). They are external
 * expected values. The first test seals each empty message with the JDK and checks
 * it reproduces the published tag, so a mistyped digit cannot make these tests pass.
 */
class CipherTextTest {
    private class Vector(
        val key: String,
        val iv: String,
        val aad: String,
        val tag: String,
    )

    private val emptyPlaintextVectors = listOf(
        Vector(
            key = "b52c505a37d78eda5dd34f20c22540ea1b58963cf8e5bf8ffa85f9f2492505b4",
            iv = "516c33929df5a3284ff463d7",
            aad = "",
            tag = "bdc1ac884d332457a1d2664f168c76f0",
        ),
        Vector(
            key = "78dc4e0aaf52d935c3c01eea57428f00ca1fd475f5da86a49c8dd73d68c8e223",
            iv = "d79cf22d504cc793c3fb6c8a",
            aad = "b96baa8c1c75a671bfb2d08d06be5f36",
            tag = "3e5d486aa2e30b22e040b85723a06e76",
        ),
        Vector(
            key = "886cff5f3e6b8d0e1ad0a38fcdb26de97e8acbe79f6bed66959a598fa5047d65",
            iv = "3a8efa1cd74bbab5448f9945",
            aad = "519fee519d25c7a304d6c6aa1897ee1eb8c59655",
            tag = "f6d47505ec96c98a42dc3ae719877b87",
        ),
    )

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Seal an empty message the way an AES-256-GCM implementation does. */
    private fun sealEmpty(vector: Vector): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(hex(vector.key), "AES"),
            GCMParameterSpec(128, hex(vector.iv)),
        )
        if (vector.aad.isNotEmpty()) cipher.updateAAD(hex(vector.aad))
        return cipher.doFinal(ByteArray(0))
    }

    @Test
    fun `an empty message sealed with AES-GCM is a valid cipher text`() {
        emptyPlaintextVectors.forEachIndexed { index, vector ->
            val sealed = sealEmpty(vector)
            assertEquals("vector $index: only the tag is produced", 16, sealed.size)
            assertArrayEquals("vector $index: the JDK reproduces the published tag", hex(vector.tag), sealed)

            val cipherText = CipherText(
                ciphertext = sealed.copyOfRange(0, sealed.size - 16),
                nonce = hex(vector.iv),
                tag = sealed.copyOfRange(sealed.size - 16, sealed.size),
            )

            assertEquals("vector $index: no ciphertext bytes", 0, cipherText.ciphertext.size)
            assertArrayEquals(hex(vector.iv), cipherText.nonce)
            assertArrayEquals(hex(vector.tag), cipherText.tag)

            // Open it again from the parts the cipher text holds: the tag
            // authenticates and the message comes back empty.
            val opener = Cipher.getInstance("AES/GCM/NoPadding")
            opener.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hex(vector.key), "AES"),
                GCMParameterSpec(128, cipherText.nonce),
            )
            if (vector.aad.isNotEmpty()) opener.updateAAD(hex(vector.aad))
            val opened = opener.doFinal(cipherText.ciphertext + cipherText.tag)
            assertEquals("vector $index: opens back to the empty message", 0, opened.size)
        }
    }

    @Test
    fun `an empty ciphertext still compares by content and never prints its bytes`() {
        val vector = emptyPlaintextVectors.first()
        fun build() = CipherText(ByteArray(0), hex(vector.iv), hex(vector.tag))

        assertEquals(build(), build())
        assertEquals(build().hashCode(), build().hashCode())
        assertEquals("CipherText(0 bytes, nonce=12, tag=16)", build().toString())

        // ...and it still tells different values apart.
        fun flipped(bytes: ByteArray) = bytes.also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(build(), CipherText(ByteArray(0), flipped(hex(vector.iv)), hex(vector.tag)))
        assertNotEquals(build(), CipherText(ByteArray(0), hex(vector.iv), flipped(hex(vector.tag))))
        assertNotEquals(build(), CipherText(byteArrayOf(1), hex(vector.iv), hex(vector.tag)))
    }

    @Test
    fun `a missing nonce is refused even when the ciphertext is empty`() {
        val vector = emptyPlaintextVectors.first()
        try {
            CipherText(ciphertext = ByteArray(0), nonce = ByteArray(0), tag = hex(vector.tag))
            fail("expected a missing nonce to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("nonce"))
        }
    }

    @Test
    fun `a missing tag is refused even when the ciphertext is empty`() {
        val vector = emptyPlaintextVectors.first()
        try {
            CipherText(ciphertext = ByteArray(0), nonce = hex(vector.iv), tag = ByteArray(0))
            fail("expected a missing tag to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("tag"))
        }
    }

    @Test
    fun `a missing nonce or tag is refused when there is ciphertext too`() {
        try {
            CipherText(ciphertext = byteArrayOf(1), nonce = ByteArray(0), tag = byteArrayOf(3))
            fail("expected a missing nonce to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("nonce"))
        }
        try {
            CipherText(ciphertext = byteArrayOf(1), nonce = byteArrayOf(2), tag = ByteArray(0))
            fail("expected a missing tag to be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("tag"))
        }
    }
}
