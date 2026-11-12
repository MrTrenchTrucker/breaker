package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxKeyPair
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.shared.api.LoginResponse
import java.util.Base64

/**
 * The account key step of a password sign-in. It runs inside the one key derivation of that
 * sign-in, with the key-encryption key that derivation made, and stores the account keys through
 * the credential vault. The caller stores the session only after [settle] returns.
 *
 * When the login reply holds no keys, the flow makes a new data key and box key pair, wraps them,
 * uploads them and stores them. When the server answers that the account already has keys, the
 * flow asks for one more login with the same verifier and opens the keys that reply holds; it
 * never asks twice. When the reply holds keys, the flow opens them, checks the public key and
 * stores them. The previous wrapped key, the reset time and the re-key time of a reply are not
 * read here: an unfinished re-key is a later step.
 *
 * Every key object the flow makes is wiped and every byte array it holds is zeroed on every path.
 * No failure text carries a key, a wrapped byte, a key version or a token.
 */
internal class AccountKeyFlow(private val ports: AccountKeyPorts) {

    /**
     * Sets up or opens the account keys for the login reply [first] and returns the reply the
     * session is made from: [first], or the reply of the one more login after a conflict.
     * [upload] sends the keys request with the bearer and returns the new key version, or null when
     * the server answered 409; it throws [AuthFailure] on every other outcome. [relogin] makes one
     * more login request with the same verifier. [dropToken] asks for a best-effort logout of the
     * given token.
     */
    fun settle(
        kek: KeyEncryptionKey,
        salt: ByteArray,
        first: LoginResponse,
        upload: (bearer: String, body: ByteArray) -> Int?,
        relogin: () -> LoginResponse,
        dropToken: (String) -> Unit,
    ): LoginResponse {
        if (hasKeys(first)) {
            unwrapAndStore(first, kek, salt)
            return first
        }
        val version = bootstrap(kek, salt, first.token, upload)
        if (version != null) return first
        val again = relogin()
        dropToken(first.token)
        if (!hasKeys(again)) throw AuthFailure(AuthFailureReason.KEY_UPLOAD_REFUSED)
        unwrapAndStore(again, kek, salt)
        return again
    }

    private fun hasKeys(reply: LoginResponse): Boolean =
        reply.wrappedDek != null && reply.wrappedBoxPrivkey != null && reply.boxPubkey != null

    private fun bootstrap(
        kek: KeyEncryptionKey,
        salt: ByteArray,
        bearer: String,
        upload: (bearer: String, body: ByteArray) -> Int?,
    ): Int? {
        val dek = refusingUpload { ports.accountKeys.generateDataKey() }
        try {
            val pair = refusingUpload { ports.accountKeys.generateBoxKeyPair() }
            try {
                return wrapUploadStore(dek, pair, kek, salt, bearer, upload)
            } finally {
                pair.wipe()
            }
        } finally {
            dek.wipe()
        }
    }

    private fun wrapUploadStore(
        dek: DataEncryptionKey,
        pair: BoxKeyPair,
        kek: KeyEncryptionKey,
        salt: ByteArray,
        bearer: String,
        upload: (bearer: String, body: ByteArray) -> Int?,
    ): Int? {
        val wrappedDek = refusingUpload { ports.accountKeys.wrapDek(dek, kek, salt) }
        try {
            val wrappedBox = refusingUpload { ports.accountKeys.wrapBoxPrivateKey(pair.privateKey, dek) }
            try {
                val version = send(wrappedDek, wrappedBox, pair, bearer, upload)
                if (version != null) store(AccountKeys(dek, pair.privateKey), version)
                return version
            } finally {
                wrappedBox.bytes.fill(0)
            }
        } finally {
            wrappedDek.bytes.fill(0)
        }
    }

    private fun send(
        wrappedDek: WrappedDek,
        wrappedBox: WrappedBoxPrivateKey,
        pair: BoxKeyPair,
        bearer: String,
        upload: (bearer: String, body: ByteArray) -> Int?,
    ): Int? {
        val publicBytes = pair.copyPublicKey()
        val body = try {
            refusingUpload { RequestEncoder.encodeKeys(wrappedDek.bytes, wrappedBox.bytes, publicBytes) }
        } finally {
            publicBytes.fill(0)
        }
        try {
            return upload(bearer, body)
        } finally {
            body.fill(0)
        }
    }

    private fun unwrapAndStore(reply: LoginResponse, kek: KeyEncryptionKey, salt: ByteArray) {
        val dekText = reply.wrappedDek
        val boxText = reply.wrappedBoxPrivkey
        val publicText = reply.boxPubkey
        if (dekText == null || boxText == null || publicText == null) {
            throw AuthFailure(AuthFailureReason.KEYS_UNREADABLE)
        }
        val dekBytes = decode(dekText)
        try {
            val boxBytes = decode(boxText)
            try {
                val publicBytes = decode(publicText)
                try {
                    openAndStore(dekBytes, boxBytes, publicBytes, reply.keyVersion, kek, salt)
                } finally {
                    publicBytes.fill(0)
                }
            } finally {
                boxBytes.fill(0)
            }
        } finally {
            dekBytes.fill(0)
        }
    }

    private fun openAndStore(
        dekBytes: ByteArray,
        boxBytes: ByteArray,
        publicBytes: ByteArray,
        keyVersion: Int,
        kek: KeyEncryptionKey,
        salt: ByteArray,
    ) {
        val dek = refusing(AuthFailureReason.KEYS_UNREADABLE) {
            ports.crypto.unwrapDek(WrappedDek(dekBytes, salt), kek)
        }
        try {
            val box = refusing(AuthFailureReason.KEYS_UNREADABLE) {
                ports.accountKeys.unwrapBoxPrivateKey(WrappedBoxPrivateKey(boxBytes), dek)
            }
            try {
                verify(box, publicBytes)
                store(AccountKeys(dek, box), keyVersion)
            } finally {
                box.wipe()
            }
        } finally {
            dek.wipe()
        }
    }

    private fun verify(box: BoxPrivateKey, publicBytes: ByteArray) {
        try {
            ports.accountKeys.verifyBoxPublicKey(box, publicBytes)
        } catch (mismatch: CryptoRefused) {
            throw AuthFailure(AuthFailureReason.PUBLIC_KEY_MISMATCH)
        }
    }

    private fun store(keys: AccountKeys, keyVersion: Int) {
        try {
            ports.credentials.saveKeys(keys, keyVersion)
        } catch (storeFailure: Exception) {
            throw AuthFailure(AuthFailureReason.KEY_STORE_FAILED)
        }
    }

    private fun decode(text: String): ByteArray =
        try {
            Base64.getDecoder().decode(text)
        } catch (notBase64: IllegalArgumentException) {
            throw AuthFailure(AuthFailureReason.KEYS_UNREADABLE)
        }

    private fun <T> refusingUpload(block: () -> T): T = refusing(AuthFailureReason.KEY_UPLOAD_REFUSED, block)

    /** Runs [block]; a refusal of the crypto port, or a model constructor, becomes [reason]. */
    private fun <T> refusing(reason: AuthFailureReason, block: () -> T): T =
        try {
            block()
        } catch (refused: CryptoRefused) {
            throw AuthFailure(reason)
        } catch (badInput: IllegalArgumentException) {
            throw AuthFailure(reason)
        }
}
