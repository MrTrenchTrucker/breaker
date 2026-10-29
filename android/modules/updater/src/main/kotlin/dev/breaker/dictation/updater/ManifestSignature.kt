package dev.breaker.dictation.updater

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec

/**
 * Checks the signature of a manifest against the pinned keys.
 *
 * The signed bytes come from [manifestSignedBytes], built from [versionCode] and [sha256]. The signature must be
 * the canonical base64 of an ECDSA signature in DER form, made with SHA256withECDSA. When [keyId] is null, every
 * pinned key may verify the signature. When it is not null, only the pinned key whose id equals it exactly is tried.
 *
 * Only a selected key that verifies gives [SignatureVerdict.Verified]. Every other outcome is a
 * [SignatureVerdict.NotVerified] with a reason, and no exception from the provider leaves this function.
 * On the phone the provider is Conscrypt, which may report a malformed signature as a plain false instead of an
 * exception. Both outcomes refuse; the reason is informational only.
 *
 * @param pinnedKeys the pinned keys; an empty or unusable list verifies nothing.
 * @param keyId the key id the manifest selects, or null when it selects none.
 * @param versionCode the canonical version code text of the manifest.
 * @param sha256 the manifest digest, 64 lowercase hexadecimal characters.
 * @param signatureBase64 the canonical base64 of the DER signature.
 * @param decodeKey turns the SPKI bytes of a pinned key into a public key. The default is the platform EC key
 * factory; a test may replace it to make the key loading fail in a chosen way.
 * @param verify checks the signed bytes against the signature with a public key. The default is the platform
 * SHA256withECDSA check; a test may replace it to make the check fail in a chosen way.
 */
internal fun verifyManifestSignature(
    pinnedKeys: List<PinnedKey>,
    keyId: String?,
    versionCode: String,
    sha256: String,
    signatureBase64: String,
    decodeKey: (ByteArray) -> PublicKey = { spki -> decodeEcPublicKey(spki) },
    verify: (PublicKey, ByteArray, ByteArray) -> Boolean = { publicKey, data, sig -> checkEcdsaSha256(publicKey, data, sig) },
): SignatureVerdict {
    if (pinnedKeys.isEmpty()) return SignatureVerdict.NotVerified(SignatureRefusal.EMPTY_KEY_LIST)
    if (!pinnedKeysAreUsable(pinnedKeys)) return SignatureVerdict.NotVerified(SignatureRefusal.INVALID_KEY_LIST)
    if (keyId != null && !isWellFormedKeyId(keyId)) return SignatureVerdict.NotVerified(SignatureRefusal.MALFORMED_KEY_ID)
    val candidates = if (keyId == null) pinnedKeys else pinnedKeys.filter { it.id == keyId }
    if (candidates.isEmpty()) return SignatureVerdict.NotVerified(SignatureRefusal.UNKNOWN_KEY_ID)
    val signed = manifestSignedBytes(versionCode, sha256) ?: return SignatureVerdict.NotVerified(SignatureRefusal.MALFORMED_INPUT)
    val signature = decodeCanonicalBase64(signatureBase64) ?: return SignatureVerdict.NotVerified(SignatureRefusal.MALFORMED_INPUT)
    var sawRejected = false
    var sawMalformed = false
    for (key in candidates) {
        when (checkWithKey(key, signed, signature, decodeKey, verify)) {
            CandidateOutcome.VERIFIED -> return SignatureVerdict.Verified
            CandidateOutcome.REJECTED -> sawRejected = true
            CandidateOutcome.MALFORMED_SIGNATURE -> sawMalformed = true
            CandidateOutcome.UNUSABLE_KEY -> Unit
        }
    }
    return SignatureVerdict.NotVerified(
        when {
            sawRejected -> SignatureRefusal.BAD_SIGNATURE
            sawMalformed -> SignatureRefusal.MALFORMED_INPUT
            else -> SignatureRefusal.NO_USABLE_KEY
        },
    )
}

/** The outcome of checking one signature with one pinned key. */
internal enum class CandidateOutcome { VERIFIED, REJECTED, MALFORMED_SIGNATURE, UNUSABLE_KEY }

/**
 * Checks [signed] and [signature] with one pinned key and reports the outcome.
 *
 * Every exception from the provider becomes a non-verified outcome; none escapes, and none verifies. A signature
 * the provider rejects with a SignatureException is MALFORMED_SIGNATURE. Every other provider failure is
 * UNUSABLE_KEY, including a key that cannot be loaded.
 *
 * [decodeKey] and [verify] default to the platform implementations. They are parameters so that a test can make
 * the provider fail in each way and check that the outcome stays non-verified.
 */
internal fun checkWithKey(
    key: PinnedKey,
    signed: ByteArray,
    signature: ByteArray,
    decodeKey: (ByteArray) -> PublicKey = { bytes -> decodeEcPublicKey(bytes) },
    verify: (PublicKey, ByteArray, ByteArray) -> Boolean = { candidate, data, sig -> checkEcdsaSha256(candidate, data, sig) },
): CandidateOutcome {
    val spki = decodeCanonicalBase64(key.spkiBase64) ?: return CandidateOutcome.UNUSABLE_KEY
    val publicKey = try { decodeKey(spki) } catch (e: GeneralSecurityException) { return CandidateOutcome.UNUSABLE_KEY } catch (e: RuntimeException) { return CandidateOutcome.UNUSABLE_KEY }
    return try {
        if (verify(publicKey, signed, signature)) CandidateOutcome.VERIFIED else CandidateOutcome.REJECTED
    } catch (e: SignatureException) { CandidateOutcome.MALFORMED_SIGNATURE } catch (e: InvalidKeyException) { CandidateOutcome.UNUSABLE_KEY } catch (e: GeneralSecurityException) { CandidateOutcome.UNUSABLE_KEY } catch (e: RuntimeException) { CandidateOutcome.UNUSABLE_KEY }
}

/** Turns the SPKI bytes of a pinned key into a public key with the platform EC key factory. */
internal fun decodeEcPublicKey(spki: ByteArray): PublicKey {
    return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
}

/** Checks [signature] over [signed] with SHA256withECDSA and the given public key. */
internal fun checkEcdsaSha256(key: PublicKey, signed: ByteArray, signature: ByteArray): Boolean {
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(key)
    verifier.update(signed)
    return verifier.verify(signature)
}
