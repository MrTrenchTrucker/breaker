package dev.breaker.dictation.wiring

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The purpose text of the seal. The session label is pinned to today's text, so a session file made
 * before the purpose became an argument still opens, and the two files cannot be swapped.
 */
class AesSessionSealPurposeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key: SecretKey = SecretKeySpec(ByteArray(32) { 6 }, "AES")
    private val input: ByteArray = "the-record-text".toByteArray(StandardCharsets.UTF_8)
    private val sessionLabel = "dev.breaker.dictation.SessionVault"

    private fun sessionSealer(): AesSessionSeal = AesSessionSeal { key }

    private fun credentialSealer(): AesSessionSeal = AesSessionSeal(CREDENTIAL_VAULT_PURPOSE) { key }

    /** A valid blob (version 1, 12 byte IV) sealed by a raw cipher with [label] then the version as AAD. */
    private fun rawBlob(label: String): ByteArray {
        val aad: ByteArray = label.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val iv: ByteArray = cipher.iv
        val cipherText: ByteArray = cipher.doFinal(input)
        return byteArrayOf(1) + iv + cipherText
    }

    @Test
    fun `the default label text is pinned`() {
        assertEquals("wiring: the session label text", sessionLabel, SESSION_VAULT_PURPOSE)
        assertEquals(
            "wiring: the credentials label text",
            "dev.breaker.dictation.CredentialVault",
            CREDENTIAL_VAULT_PURPOSE,
        )
    }

    @Test
    fun `the two labels differ`() {
        assertNotEquals("wiring: the labels must differ", SESSION_VAULT_PURPOSE, CREDENTIAL_VAULT_PURPOSE)
    }

    @Test
    fun `a blob sealed with the old session label opens through the one argument constructor`() {
        assertArrayEquals(
            "wiring: the old session blob must open",
            input,
            sessionSealer().unseal(rawBlob(sessionLabel)),
        )
    }

    @Test
    fun `a blob sealed through the one argument constructor opens with the old session label`() {
        val blob: ByteArray = sessionSealer().seal(input)
        val explicit: AesSessionSeal = AesSessionSeal(sessionLabel) { key }
        assertArrayEquals("wiring: the blob must carry the old session label", input, explicit.unseal(blob))
    }

    @Test
    fun `a blob sealed with the credentials label opens with the credentials label`() {
        val blob: ByteArray = credentialSealer().seal(input)
        assertArrayEquals("wiring: the control blob must open", input, credentialSealer().unseal(blob))
    }

    @Test
    fun `a blob sealed with the credentials label does not open under the session label`() {
        val blob: ByteArray = credentialSealer().seal(input)
        assertThrows(GeneralSecurityException::class.java) { sessionSealer().unseal(blob) }
    }

    @Test
    fun `a blob sealed with the session label does not open under the credentials label`() {
        val blob: ByteArray = sessionSealer().seal(input)
        assertThrows(GeneralSecurityException::class.java) { credentialSealer().unseal(blob) }
    }

    @Test
    fun `a credentials file read by the session vault gives nothing and the reverse`() {
        val file = File(tmp.root, "swap.bin")
        FileSessionVault(file, credentialSealer()).save("the-record")
        assertNull(
            "wiring: the session vault must not open a credentials blob",
            FileSessionVault(file, sessionSealer()).load(),
        )
        assertEquals(
            "wiring: the control must open",
            "the-record",
            FileSessionVault(file, credentialSealer()).load(),
        )
        FileSessionVault(file, sessionSealer()).save("the-record")
        assertNull(
            "wiring: the credentials seal must not open a session blob",
            FileSessionVault(file, credentialSealer()).load(),
        )
    }
}
