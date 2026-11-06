package dev.breaker.dictation.wiring

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The purpose text of the session file: it is bound into every blob the session vault seals. */
internal const val SESSION_VAULT_PURPOSE: String = "dev.breaker.dictation.SessionVault"

/** The purpose text of the credentials file: it differs from the session text, so the files do not swap. */
internal const val CREDENTIAL_VAULT_PURPOSE: String = "dev.breaker.dictation.CredentialVault"

/**
 * A [SessionSeal] over AES-GCM. The cipher picks a fresh 12-byte IV on each seal
 * (Android refuses a caller-supplied IV under randomized encryption), and the AAD
 * binds the blob to this vault's purpose so a blob the wrong key or the wrong
 * purpose reads cannot open. The key arrives as a lazy supplier, so a Keystore
 * failure on load becomes null and on save becomes an exception.
 */
internal class AesSessionSeal(private val purpose: String, private val key: () -> SecretKey) : SessionSeal {

    /** The seal of the session file: the purpose text is [SESSION_VAULT_PURPOSE]. */
    constructor(key: () -> SecretKey) : this(SESSION_VAULT_PURPOSE, key)

    /** AAD = the vault purpose name as UTF-8, then the version byte, the same on seal and on unseal. */
    private val aad: ByteArray = run {
        val purposeBytes: ByteArray = purpose.toByteArray(StandardCharsets.UTF_8)
        val next: ByteArray = ByteArray(purposeBytes.size + 1)
        System.arraycopy(purposeBytes, 0, next, 0, purposeBytes.size)
        next[purposeBytes.size] = VERSION.toByte()
        next
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val cipherText: ByteArray = cipher.doFinal(plain)
        val iv: ByteArray = cipher.iv
        val out: ByteArray = ByteArray(1 + iv.size + cipherText.size)
        out[0] = VERSION.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        return out
    }

    override fun unseal(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_END || sealed[0].toInt() != VERSION) {
            throw IllegalArgumentException("bad version")
        }
        val iv: ByteArray = sealed.copyOfRange(1, IV_END)
        val cipherText: ByteArray = sealed.copyOfRange(IV_END, sealed.size)
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherText)
    }

    companion object {
        /** version byte 0x01, in the blob and appended to the AAD. */
        private const val VERSION: Int = 0x01
        /** the GCM IV length in bytes (exactly 12). */
        private const val IV_BYTES: Int = 12
        /** first index after the version byte + the IV. */
        private const val IV_END: Int = 1 + IV_BYTES
        /** the GCM authentication tag length in bits. */
        private const val TAG_BITS: Int = 128
    }
}
