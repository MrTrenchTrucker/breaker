package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxKeyPair
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedAgentResult
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.model.WrappedPreviousDek
import dev.breaker.dictation.core.port.AccountKeyService
import dev.breaker.shared.api.sync.SyncBodyCodec
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** The length of the random bytes behind a data key and behind a box private key, in bytes. */
internal const val ACCOUNT_KEY_BYTES = 32

/** The most plaintext bytes an unsealed agent result may hold, in bytes (512 KiB). */
internal const val UNSEAL_PLAINTEXT_MAX_BYTES = 524288

/** The most bytes a sealed box may hold: the plaintext cap plus 32 (ephemeral key) and 16 (tag). */
internal const val UNSEAL_BOX_MAX_BYTES = UNSEAL_PLAINTEXT_MAX_BYTES + 48

/** The length of the AES-GCM tag at the end of a sealed result, in bytes. */
internal const val UNSEAL_TAG_BYTES = 16

/** The one secure random source of the account key service. */
private val accountKeyRandom = SecureRandom()

/** A fresh array of [size] bytes from the shared secure random source. */
private fun secureKeyEntropy(size: Int): ByteArray {
    val bytes = ByteArray(size)
    accountKeyRandom.nextBytes(bytes)
    return bytes
}

/**
 * The [AccountKeyService] for the phone: makes the account's data key and box key pair, and
 * wraps and unwraps them.
 *
 * Every failure of any operation is the one plain [CryptoRefused]. It carries no reason, no
 * cause and no byte, and nothing partial is ever returned.
 *
 * The three wrap forms, each exactly 60 raw bytes (nonce 12, ciphertext 32, tag 16) under
 * AES-256-GCM:
 * - The data key under the key-encryption key, with empty associated data. This is the form
 *   [CryptoServiceImpl] opens, and [CryptoServiceImpl] is unchanged.
 * - The box private key under the data key, with the associated data
 *   [BOX_PRIVATE_KEY_AAD_TEXT].
 * - The previous data key under the new data key, with the associated data
 *   [PREVIOUS_DEK_AAD_TEXT]. The two purpose strings make a blob of one slot fail the tag in
 *   the other slot.
 *
 * Nonces come only from the injected [NonceSource], one for each wrap. Key bytes come from the
 * entropy function, which is asked for exactly 32 bytes; production uses one [SecureRandom].
 * Every array copied out of a key object, and every temporary key array, is zeroed before the
 * call returns.
 *
 * The unseal form opens a sealed agent result with the box private key, checks that the plaintext
 * is an R1 body with source server of at most [UNSEAL_PLAINTEXT_MAX_BYTES] bytes, and only then
 * asks the nonce source once and encrypts those same bytes under the data key with the caller's
 * associated data. The size of the box is checked before it is copied, every plaintext copy is
 * zeroed on every path, and the nonce source is never asked when anything is refused.
 */
class AccountKeyServiceImpl internal constructor(
    private val nonces: NonceSource,
    private val entropy: (Int) -> ByteArray,
    private val opener: SealedBoxOpener,
) : AccountKeyService {
    /**
     * The form the wrap and key tests use: the entropy function is the LAST parameter, so a trailing lambda
     * still binds to it, and the box opener is the real one.
     */
    internal constructor(nonces: NonceSource, entropy: (Int) -> ByteArray) : this(nonces, entropy, SealedBoxOpener())

    /** The service wired for production: random nonces and the secure random key source. */
    constructor() : this(SecureRandomNonceSource(), ::secureKeyEntropy)

    override fun generateDataKey(): DataEncryptionKey =
        refusingAnyFailure {
            val bytes = takeKeyBytes()
            try {
                DataEncryptionKey(bytes.copyOf())
            } finally {
                bytes.fill(0)
            }
        }

    override fun generateBoxKeyPair(): BoxKeyPair =
        refusingAnyFailure {
            val bytes = takeKeyBytes()
            try {
                val publicKey = X25519PrivateKeyParameters(bytes, 0).generatePublicKey().encoded
                BoxKeyPair(BoxPrivateKey(bytes), publicKey)
            } finally {
                bytes.fill(0)
            }
        }

    override fun wrapDek(dek: DataEncryptionKey, kek: KeyEncryptionKey, salt: ByteArray): WrappedDek =
        refusingAnyFailure {
            val kekBytes = kek.copyBytes()
            try {
                WrappedDek(sealKey(dek.bytes, kekBytes, ByteArray(0), nonces.next()), salt.copyOf())
            } finally {
                kekBytes.fill(0)
            }
        }

    override fun wrapBoxPrivateKey(
        boxPrivateKey: BoxPrivateKey,
        dek: DataEncryptionKey,
    ): WrappedBoxPrivateKey =
        refusingAnyFailure {
            val keyBytes = boxPrivateKey.copyBytes()
            try {
                val aad = aadBytes(BOX_PRIVATE_KEY_AAD_TEXT)
                WrappedBoxPrivateKey(sealKey(keyBytes, dek.bytes, aad, nonces.next()))
            } finally {
                keyBytes.fill(0)
            }
        }

    override fun unwrapBoxPrivateKey(wrapped: WrappedBoxPrivateKey, dek: DataEncryptionKey): BoxPrivateKey =
        refusingAnyFailure {
            val opened = openKey(wrapped.bytes, dek.bytes, aadBytes(BOX_PRIVATE_KEY_AAD_TEXT))
            try {
                BoxPrivateKey(opened)
            } finally {
                opened.fill(0)
            }
        }

    override fun wrapPreviousDek(
        previousDek: DataEncryptionKey,
        newDek: DataEncryptionKey,
    ): WrappedPreviousDek =
        refusingAnyFailure {
            val aad = aadBytes(PREVIOUS_DEK_AAD_TEXT)
            WrappedPreviousDek(sealKey(previousDek.bytes, newDek.bytes, aad, nonces.next()))
        }

    override fun unwrapPreviousDek(
        wrapped: WrappedPreviousDek,
        newDek: DataEncryptionKey,
    ): DataEncryptionKey =
        refusingAnyFailure {
            val opened = openKey(wrapped.bytes, newDek.bytes, aadBytes(PREVIOUS_DEK_AAD_TEXT))
            try {
                DataEncryptionKey(opened.copyOf())
            } finally {
                opened.fill(0)
            }
        }

    override fun verifyBoxPublicKey(boxPrivateKey: BoxPrivateKey, publicKey: ByteArray) {
        refusingAnyFailure {
            if (publicKey.size != BoxKeyPair.PUBLIC_KEY_SIZE_BYTES) throw CryptoRefused()
            val keyBytes = boxPrivateKey.copyBytes()
            try {
                val computed = X25519PrivateKeyParameters(keyBytes, 0).generatePublicKey().encoded
                if (!MessageDigest.isEqual(computed, publicKey)) throw CryptoRefused()
                Unit
            } finally {
                keyBytes.fill(0)
            }
        }
    }

    override fun unsealAndReencrypt(
        sealed: SealedAgentResult,
        boxPrivateKey: BoxPrivateKey,
        dek: DataEncryptionKey,
        aad: ByteArray,
    ): CipherText =
        refusingAnyFailure {
            if (sealed.size > UNSEAL_BOX_MAX_BYTES) throw CryptoRefused()
            if (dek.bytes.size != ACCOUNT_KEY_BYTES) throw CryptoRefused()
            var box = ByteArray(0)
            var keyBytes = ByteArray(0)
            var plain = ByteArray(0)
            try {
                box = sealed.copyBytes()
                keyBytes = boxPrivateKey.copyBytes()
                plain = opener.open(box, keyBytes)
                if (plain.size > UNSEAL_PLAINTEXT_MAX_BYTES) throw CryptoRefused()
                SyncBodyCodec.decodeStrictServerBody(plain)
                val nonce = nonces.next()
                if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
                val sealedBytes = sealBytes(plain, dek.bytes, aad, nonce)
                val tagStart = sealedBytes.size - UNSEAL_TAG_BYTES
                CipherText(
                    sealedBytes.copyOfRange(0, tagStart),
                    nonce.copyOf(),
                    sealedBytes.copyOfRange(tagStart, sealedBytes.size),
                )
            } finally {
                box.fill(0)
                keyBytes.fill(0)
                plain.fill(0)
            }
        }

    /**
     * Open [cipherText] under [oldDek] with [oldAad] and encrypt the same bytes again under [newDek]
     * with [newAad]. The order: the ciphertext cap, the two key sizes, the nonce and tag sizes, the
     * open, then one nonce and the encryption. The nonce source is asked only after the open
     * succeeded, nothing is turned into a String, and the opened plaintext is zeroed on every path.
     */
    override fun reencrypt(
        cipherText: CipherText,
        oldDek: DataEncryptionKey,
        oldAad: ByteArray,
        newDek: DataEncryptionKey,
        newAad: ByteArray,
    ): CipherText =
        refusingAnyFailure {
            if (cipherText.ciphertext.size > UNSEAL_PLAINTEXT_MAX_BYTES) throw CryptoRefused()
            if (oldDek.bytes.size != ACCOUNT_KEY_BYTES) throw CryptoRefused()
            if (newDek.bytes.size != ACCOUNT_KEY_BYTES) throw CryptoRefused()
            if (cipherText.nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
            if (cipherText.tag.size != UNSEAL_TAG_BYTES) throw CryptoRefused()
            var plain = ByteArray(0)
            try {
                val sealedInput = cipherText.ciphertext + cipherText.tag
                plain = openBytes(sealedInput, oldDek.bytes, oldAad, cipherText.nonce)
                val nonce = nonces.next()
                if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
                val sealedBytes = sealBytes(plain, newDek.bytes, newAad, nonce)
                val tagStart = sealedBytes.size - UNSEAL_TAG_BYTES
                CipherText(
                    sealedBytes.copyOfRange(0, tagStart),
                    nonce.copyOf(),
                    sealedBytes.copyOfRange(tagStart, sealedBytes.size),
                )
            } finally {
                plain.fill(0)
            }
        }

    /** Seal opaque [plain] bytes under [dek] with [aad]; the steps are in [encryptRecordBytes]. */
    override fun encryptBytes(plain: ByteArray, dek: DataEncryptionKey, aad: ByteArray): CipherText =
        refusingAnyFailure { encryptRecordBytes(plain, dek, aad, nonces) }

    /** Open [cipherText] under [dek] with [aad]; the steps are in [decryptRecordBytes]. */
    override fun decryptBytes(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): ByteArray =
        refusingAnyFailure { decryptRecordBytes(cipherText, dek, aad) }

    /** One array of key bytes from the entropy source. A wrong-length array is zeroed and refused. */
    private fun takeKeyBytes(): ByteArray {
        val bytes = entropy(ACCOUNT_KEY_BYTES)
        if (bytes.size != ACCOUNT_KEY_BYTES) {
            bytes.fill(0)
            throw CryptoRefused()
        }
        return bytes
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
