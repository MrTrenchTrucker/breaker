package dev.breaker.dictation.wiring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AesSessionSealTest {

    companion object {
        val KEY: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun seal(): AesSessionSeal = AesSessionSeal({ KEY })
    private fun otherKey(): SecretKey {
        val g = KeyGenerator.getInstance("AES"); g.init(256); return g.generateKey()
    }

    private val input: ByteArray = "the-session-record-\u00e9".toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `two seals of the same input differ`() {
        val s = seal()
        val a: ByteArray = s.seal(input)
        val b: ByteArray = s.seal(input)
        assertFalse("app: a fresh IV must make every seal unique", a.contentEquals(b))
    }

    @Test
    fun `the IV is exactly twelve bytes and differs per seal`() {
        val s = seal()
        val a: ByteArray = s.seal(input)
        val b: ByteArray = s.seal(input)
        assertEquals(1 + 12 + input.size + 16, a.size)
        assertEquals(1, a[0].toInt())
        assertFalse(a.copyOfRange(1, 13).contentEquals(b.copyOfRange(1, 13)))
    }

    @Test
    fun `a sealed blob unseals to the same bytes with the same key`() {
        val s = seal()
        assertArrayEquals(input, s.unseal(s.seal(input)))
    }

    @Test
    fun `a flipped version byte is refused`() {
        val s = seal()
        val blob: ByteArray = s.seal(input).copyOf()
        blob[0] = (blob[0].toInt() + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { s.unseal(blob) }
    }

    @Test
    fun `tampered ciphertext throws on unseal`() {
        val s = seal()
        val blob: ByteArray = s.seal(input).copyOf()
        blob[20] = (blob[20].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { s.unseal(blob) }
    }

    @Test
    fun `wrong key throws on unseal`() {
        val blob: ByteArray = seal().seal(input)
        assertThrows(AEADBadTagException::class.java) { AesSessionSeal({ otherKey() }).unseal(blob) }
    }

    @Test
    fun `a short blob throws on unseal`() {
        val s = seal()
        val blob: ByteArray = s.seal(input)
        assertThrows(IllegalArgumentException::class.java) { s.unseal(blob.copyOfRange(0, 8)) }
    }

    @Test
    fun `a blob sealed without the purpose binding is refused`() {
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KEY)
        val iv: ByteArray = cipher.iv
        val cipherText: ByteArray = cipher.doFinal(input)
        val blob: ByteArray = ByteArray(1 + iv.size + cipherText.size)
        blob[0] = 0x01.toByte()
        System.arraycopy(iv, 0, blob, 1, iv.size)
        System.arraycopy(cipherText, 0, blob, 1 + iv.size, cipherText.size)
        assertThrows(AEADBadTagException::class.java) { seal().unseal(blob) }
    }

    @Test
    fun `a sealed blob does not open without the purpose binding`() {
        val blob: ByteArray = seal().seal(input)
        val iv: ByteArray = blob.copyOfRange(1, 13)
        val cipherText: ByteArray = blob.copyOfRange(13, blob.size)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, KEY, GCMParameterSpec(128, iv))
        assertThrows(AEADBadTagException::class.java) { cipher.doFinal(cipherText) }
    }

    // The same purpose text as the seal binds into its authenticated data, written out on its own.
    private val purpose: ByteArray = "dev.breaker.dictation.SessionVault".toByteArray(StandardCharsets.UTF_8)

    private fun purposeThen(last: Int): ByteArray = purpose + byteArrayOf(last.toByte())

    // A valid blob (version byte 1, 12-byte IV, 128-bit tag, the shared key) sealed by a raw cipher,
    // so the authenticated data is the only thing that differs from a blob the seal wrote.
    private fun blobWithAad(aad: ByteArray): ByteArray {
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KEY)
        cipher.updateAAD(aad)
        val iv: ByteArray = cipher.iv
        val cipherText: ByteArray = cipher.doFinal(input)
        val blob: ByteArray = ByteArray(1 + iv.size + cipherText.size)
        blob[0] = 0x01.toByte()
        System.arraycopy(iv, 0, blob, 1, iv.size)
        System.arraycopy(cipherText, 0, blob, 1 + iv.size, cipherText.size)
        return blob
    }

    @Test
    fun `a blob sealed by a raw cipher with the exact binding opens`() {
        assertArrayEquals(input, seal().unseal(blobWithAad(purposeThen(1))))
    }

    @Test
    fun `a blob bound to the version byte only is refused`() {
        val blob: ByteArray = blobWithAad(byteArrayOf(0x01.toByte()))
        assertThrows(AEADBadTagException::class.java) { seal().unseal(blob) }
    }

    @Test
    fun `a blob bound to the purpose bytes only is refused`() {
        val blob: ByteArray = blobWithAad(purpose)
        assertThrows(AEADBadTagException::class.java) { seal().unseal(blob) }
    }

    @Test
    fun `a blob bound to the purpose bytes then a zero byte is refused`() {
        val blob: ByteArray = blobWithAad(purposeThen(0))
        assertThrows(AEADBadTagException::class.java) { seal().unseal(blob) }
    }
}
