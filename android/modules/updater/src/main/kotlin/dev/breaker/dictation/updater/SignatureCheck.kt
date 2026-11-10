package dev.breaker.dictation.updater

import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** The fixed prefix of the signed update message. */
private const val MESSAGE_PREFIX = "breaker-update-v1"

/** An Ed25519 signature is exactly 64 bytes. */
private const val SIGNATURE_BYTES = 64

/** An Ed25519 public key is exactly 32 raw bytes. */
private const val KEY_BYTES = 32

/**
 * Verifies an Ed25519 signature over an update message.
 *
 * The signed message is the ASCII text "breaker-update-v1\\n" + version + "\\n" +
 * sha256-lowercase-hex + "\\n". Binding the digest binds the exact APK bytes (the digest
 * check on the downloaded file already exists); binding the version stops a replay of an
 * older signed APK as "new". Nothing else is signed: notes and URL may change freely.
 *
 * [pinnedKeys] is a list of raw 32-byte public keys. A key that is not 32 bytes never
 * matches. The list and each key are defensively copied.
 *
 * Never throws: every outcome is [SignatureResult.Verified] or [SignatureResult.Refused]
 * with a fixed sentence. The BC calls and the base64 decode are wrapped so no exception
 * escapes, and nothing here installs anything.
 */
internal class SignatureCheck(private val pinnedKeys: List<ByteArray>) {

    private val usableKeys: List<ByteArray> =
        pinnedKeys.map { it.copyOfRange(0, it.size) }.filter { it.size == KEY_BYTES }

    /**
     * Verifies [signature] over the message built from [version] and [sha256].
     *
     * Refuses (with a fixed sentence, no key/signature/version/digest text) when the
     * version is blank or holds a control character, when [sha256] is not exactly 64 hex
     * digits, when [signature] is not canonical base64 of exactly 64 bytes, when no key
     * is pinned, or when no pinned key verifies. Otherwise returns [SignatureResult.Verified].
     */
    fun verify(version: String, sha256: String, signature: String): SignatureResult {
        // 1. Validate the version: blank or any control character forges the message.
        if (version.isBlank() || version.any { Character.isISOControl(it) }) {
            return SignatureResult.Refused(SignatureCheckReasons.BAD_VERSION)
        }
        // 2. Validate the digest: exactly 64 hex digits, any case.
        if (!isValidSha256Hex(sha256)) {
            return SignatureResult.Refused(SignatureCheckReasons.BAD_DIGEST)
        }
        // 3. Decode the signature strictly: canonical base64 of exactly 64 bytes.
        val signatureBytes = try {
            decodeCanonicalBase64(signature)
        } catch (_: Exception) {
            return SignatureResult.Refused(SignatureCheckReasons.BAD_SIGNATURE)
        }
        // 4. No pinned keys: nothing can verify.
        if (usableKeys.isEmpty()) {
            return SignatureResult.Refused(SignatureCheckReasons.NO_PINNED_KEY)
        }
        // 5. Build the signed message.
        val message = buildMessage(version, sha256)
        // 6. Try every pinned key.
        for (key in usableKeys) {
            if (verifyOne(key, message, signatureBytes)) {
                return SignatureResult.Verified
            }
        }
        return SignatureResult.Refused(SignatureCheckReasons.SIGNATURE_NOT_ACCEPTED)
    }

    private fun isValidSha256Hex(text: String): Boolean =
        text.length == 64 && text.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }

    /** Canonical base64: decodes to exactly [SIGNATURE_BYTES] and re-encodes to the same text. */
    private fun decodeCanonicalBase64(text: String): ByteArray {
        val decoded = Base64.getDecoder().decode(text)
        if (decoded.size != SIGNATURE_BYTES) throw IllegalArgumentException("wrong signature length")
        if (Base64.getEncoder().encodeToString(decoded) != text) {
            throw IllegalArgumentException("signature is not canonical base64")
        }
        return decoded
    }

    private fun buildMessage(version: String, sha256: String): ByteArray {
        val shaLower = sha256.lowercase()
        return (MESSAGE_PREFIX + "\n" + version + "\n" + shaLower + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun verifyOne(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            val params = Ed25519PublicKeyParameters(key, 0)
            val signer = Ed25519Signer()
            signer.init(false, params)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
}
