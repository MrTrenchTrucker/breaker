package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.port.CryptoService
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The length of the AES-GCM authentication tag, in bytes. */
private const val GCM_TAG_BYTES = 16

/** The length of the AES-GCM authentication tag, in bits, as the cipher parameters take it. */
private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8

/** The length of a data-encryption key (an AES-256 key), in bytes. */
private const val DEK_SIZE_BYTES = 32

/** A wrapped data-encryption key is exactly nonce, then the 32 key bytes sealed, then the tag. */
private const val WRAPPED_DEK_BYTES = GCM_NONCE_BYTES + DEK_SIZE_BYTES + GCM_TAG_BYTES

/** The cipher the platform provides for AES-256-GCM. */
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

/** The key algorithm name the cipher expects. */
private const val AES_ALGORITHM = "AES"

/**
 * The [CryptoService] for the phone: AES-256-GCM records, the wrapped data key, and the
 * sign-in package.
 *
 * Every failure of a cryptographic operation is the one plain [CryptoRefused]. It carries no
 * reason, no cause and no byte, so a caller cannot learn from the error why an input was
 * refused, and nothing partial is ever returned.
 *
 * What the pieces are:
 * - A record is `CipherText(ciphertext, nonce of 12 bytes, tag of 16 bytes)`, AES-256-GCM under
 *   the data key. The caller passes the associated data as opaque bytes; the form the sync
 *   code uses (the client id, a newline, the key version) is that code's business and is
 *   pinned by test vectors here. Nonces come only from the injected [NonceSource].
 * - A wrapped data key is exactly 60 raw bytes: nonce (12), the encrypted key (32), tag (16),
 *   AES-256-GCM under the key-encryption key with empty associated data. The salt of the
 *   [WrappedDek] is not used here.
 * - A sign-in package is a sealed box opened with the one-time private key and then read as
 *   [SignInPackage] says.
 *
 * [toEncryptedText] gives the three base64 parts. Joining them into the single string the
 * sync contract carries (nonce, ciphertext, tag in one base64 string) is the sync code's
 * job and is not built here.
 */
class CryptoServiceImpl internal constructor(
    private val nonces: NonceSource,
    private val opener: SealedBoxOpener,
) : CryptoService {
    /** The service wired for production: random nonces and the real sealed-box opener. */
    constructor() : this(SecureRandomNonceSource(), SealedBoxOpener())

    override fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey =
        refusingAnyFailure {
            val wrapped = wrappedDek.bytes
            if (wrapped.size != WRAPPED_DEK_BYTES) throw CryptoRefused()
            val kekBytes = kek.copyBytes()
            try {
                val dekBytes = runGcm(
                    mode = Cipher.DECRYPT_MODE,
                    key = kekBytes,
                    nonce = wrapped.copyOfRange(0, GCM_NONCE_BYTES),
                    aad = ByteArray(0),
                    input = wrapped.copyOfRange(GCM_NONCE_BYTES, wrapped.size),
                )
                // Exactly 60 bytes were checked above, so the opened key is exactly 32 bytes.
                DataEncryptionKey(dekBytes)
            } finally {
                kekBytes.fill(0)
            }
        }

    override fun openSignInPackage(
        sealedPackage: SealedSignInPackage,
        signInPrivateKey: SignInPrivateKey,
    ): AccountKeys =
        refusingAnyFailure {
            val box = sealedPackage.copyBytes()
            val privateKey = signInPrivateKey.copyBytes()
            try {
                SignInPackage.parse(opener.open(box, privateKey))
            } finally {
                box.fill(0)
                privateKey.fill(0)
            }
        }

    override fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText =
        refusingAnyFailure {
            requireDekSize(dek)
            val nonce = nonces.next()
            if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
            val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
            try {
                val sealed = runGcm(Cipher.ENCRYPT_MODE, dek.bytes, nonce, aad, plainBytes)
                val tagStart = sealed.size - GCM_TAG_BYTES
                CipherText(
                    ciphertext = sealed.copyOfRange(0, tagStart),
                    nonce = nonce.copyOf(),
                    tag = sealed.copyOfRange(tagStart, sealed.size),
                )
            } finally {
                plainBytes.fill(0)
            }
        }

    override fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String =
        refusingAnyFailure {
            requireDekSize(dek)
            if (cipherText.nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
            if (cipherText.tag.size != GCM_TAG_BYTES) throw CryptoRefused()
            val plainBytes = runGcm(
                mode = Cipher.DECRYPT_MODE,
                key = dek.bytes,
                nonce = cipherText.nonce,
                aad = aad,
                input = cipherText.ciphertext + cipherText.tag,
            )
            try {
                String(plainBytes, Charsets.UTF_8)
            } finally {
                plainBytes.fill(0)
            }
        }

    override fun toEncryptedText(cipherText: CipherText): EncryptedText {
        val encoder = Base64.getEncoder()
        return EncryptedText(
            ciphertext = encoder.encodeToString(cipherText.ciphertext),
            nonce = encoder.encodeToString(cipherText.nonce),
            tag = encoder.encodeToString(cipherText.tag),
        )
    }

    /** Refuse a key that is not an AES-256 key. */
    private fun requireDekSize(dek: DataEncryptionKey) {
        if (dek.bytes.size != DEK_SIZE_BYTES) throw CryptoRefused()
    }

    /**
     * One AES-GCM operation. The associated data is added before the data, as the cipher
     * requires. On decryption [input] is the ciphertext with the tag at its end, and a wrong
     * tag throws.
     */
    private fun runGcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    /**
     * Run [block], and turn every failure inside it into the one plain [CryptoRefused], with no
     * cause attached. A [CryptoRefused] thrown by [block] passes through as it is.
     */
    private inline fun <T> refusingAnyFailure(block: () -> T): T =
        try {
            block()
        } catch (refused: CryptoRefused) {
            throw refused
        } catch (failure: Exception) {
            throw CryptoRefused()
        }
}
