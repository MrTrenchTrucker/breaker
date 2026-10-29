package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.SignatureException

/** Pins the manifest signature verdicts against the golden vectors. */
internal class ManifestSignatureTest {
    private val current = PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.CURRENT_SPKI)
    private val next = PinnedKey(ManifestVectors.NEXT_KEY_ID, ManifestVectors.NEXT_SPKI)

    private fun verify(
        keys: List<PinnedKey>,
        keyId: String?,
        versionCode: String,
        digest: String,
        signature: String,
    ): SignatureVerdict = verifyManifestSignature(keys, keyId, versionCode, digest, signature)

    private fun refused(reason: SignatureRefusal): SignatureVerdict = SignatureVerdict.NotVerified(reason)

    private fun derBytes(): ByteArray = decodeCanonicalBase64(ManifestVectors.SIG_CURRENT)!!

    private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun signedBytes(): ByteArray = manifestSignedBytes(ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST)!!

    private fun decodeThrowing(failure: Throwable): (ByteArray) -> PublicKey = { _ -> throw failure }

    private fun verifyThrowing(failure: Throwable): (PublicKey, ByteArray, ByteArray) -> Boolean = { _, _, _ -> throw failure }

    @Test
    fun currentKeyVerifiesItsSignature() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(SignatureVerdict.Verified, result)
        // The single-key check with the platform defaults accepts the signature, and refuses it once a bit is flipped.
        assertEquals(CandidateOutcome.VERIFIED, checkWithKey(current, signedBytes(), derBytes()))
        val flipped = derBytes()
        flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 0x01).toByte()
        assertEquals(CandidateOutcome.REJECTED, checkWithKey(current, signedBytes(), flipped))
    }

    @Test
    fun keyIdSelectsSecondKey() {
        val result = verify(listOf(current, next), "next", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT)
        assertEquals(SignatureVerdict.Verified, result)
    }

    @Test
    fun noKeyIdAllowsAnyPinnedKey() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT)
        assertEquals(SignatureVerdict.Verified, result)
    }

    @Test
    fun keyIdThatSelectsAnotherKeyRefusesSignature() {
        val result = verify(listOf(current, next), "next", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun keyIdIsCaseSensitive() {
        val result = verify(listOf(current, next), "NEXT", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT)
        assertEquals(refused(SignatureRefusal.UNKNOWN_KEY_ID), result)
    }

    @Test
    fun unknownKeyIdIsRefused() {
        val result = verify(listOf(current, next), "unpinned", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_UNPINNED)
        assertEquals(refused(SignatureRefusal.UNKNOWN_KEY_ID), result)
    }

    @Test
    fun unpinnedKeyNeverVerifies() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_UNPINNED)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun emptyPinnedListVerifiesNothing() {
        val result = verify(emptyList(), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.EMPTY_KEY_LIST), result)
    }

    @Test
    fun duplicateIdPinnedListVerifiesNothing() {
        val twins = listOf(current, PinnedKey(ManifestVectors.CURRENT_KEY_ID, ManifestVectors.NEXT_SPKI))
        val result = verify(twins, null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.INVALID_KEY_LIST), result)
    }

    @Test
    fun malformedKeyIdIsRefused() {
        val result = verify(listOf(current, next), "", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.MALFORMED_KEY_ID), result)
    }

    @Test
    fun versionBitFlipIsRefused() {
        // One bit of the last character of the version code is flipped: "12" becomes "13".
        assertEquals('2', ManifestVectors.VERSION_CODE[1])
        val result = verify(listOf(current, next), null, "13", ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun oldNameSignatureDoesNotVerify() {
        // The signature covers the retired name-based string "breaker-update-v1\n1.2.0\n" + digest.
        val byCode = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_OLD_NAME)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), byCode)
        val byName = verify(listOf(current, next), null, "1.2.0", ManifestVectors.DIGEST, ManifestVectors.SIG_OLD_NAME)
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), byName)
    }

    @Test
    fun digestBitFlipIsRefused() {
        // The first digest character is "9"; one bit flipped gives "8", which is still lowercase hex.
        assertEquals('9', ManifestVectors.DIGEST[0])
        val flipped = "8" + ManifestVectors.DIGEST.substring(1)
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, flipped, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun signatureBitFlipIsRefused() {
        // The last byte of the DER signature has its lowest bit flipped; the DER stays well formed.
        val der = derBytes()
        der[der.size - 1] = (der[der.size - 1].toInt() xor 0x01).toByte()
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, encode(der))
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun truncatedDerIsMalformed() {
        val der = derBytes()
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, encode(der.copyOf(der.size - 1)))
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), result)
    }

    @Test
    fun shortAndZeroSignaturesAreMalformed() {
        val der = derBytes()
        val eightBytes = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, encode(der.copyOf(8)))
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), eightBytes)
        val zeros = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, encode(ByteArray(72)))
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), zeros)
    }

    @Test
    fun emptySignatureIsMalformed() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, "")
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), result)
    }

    @Test
    fun unpaddedSignatureIsMalformed() {
        // SIG_NEXT is a 70-byte DER signature. 70 is not a multiple of 3, so its base64 text ends in "==".
        // The text as it is verifies with the next key; dropping one or both padding characters must be refused.
        assertEquals(0, ManifestVectors.SIG_NEXT.length % 4)
        assertTrue(ManifestVectors.SIG_NEXT.endsWith("=="))
        val control = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT)
        assertEquals(SignatureVerdict.Verified, control)
        val oneDropped = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT.dropLast(1))
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), oneDropped)
        val bothDropped = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_NEXT.dropLast(2))
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), bothDropped)
    }

    @Test
    fun trailingLfSignatureIsRefused() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT_OVER_LF)
        assertEquals(refused(SignatureRefusal.BAD_SIGNATURE), result)
    }

    @Test
    fun upperCaseDigestIsMalformedInput() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST.uppercase(), ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), result)
    }

    @Test
    fun malformedPinnedKeyDoesNotHideGoodKey() {
        val keys = listOf(PinnedKey("bad", "AAAA"), current)
        val result = verify(keys, null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(SignatureVerdict.Verified, result)
    }

    @Test
    fun unparsableSpkiVerifiesNothing() {
        val keys = listOf(PinnedKey("bad", "AAAA"))
        val result = verify(keys, "bad", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }

    @Test
    fun nonCanonicalPinnedKeyTextVerifiesNothing() {
        val keys = listOf(PinnedKey("bad", "%%%"))
        val result = verify(keys, "bad", ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }

    @Test
    fun nonBase64SignatureIsMalformed() {
        val result = verify(listOf(current, next), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, "%%%")
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), result)
    }

    @Test
    fun keyFactoryRuntimeFailureMakesKeyUnusable() {
        val decode = decodeThrowing(RuntimeException("test failure"))
        assertEquals(CandidateOutcome.UNUSABLE_KEY, checkWithKey(current, signedBytes(), derBytes(), decodeKey = decode))
        val result = verifyManifestSignature(listOf(current), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT, decodeKey = decode)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }

    @Test
    fun verifierSignatureExceptionIsMalformedSignature() {
        val throwing = verifyThrowing(SignatureException("test failure"))
        assertEquals(CandidateOutcome.MALFORMED_SIGNATURE, checkWithKey(current, signedBytes(), derBytes(), verify = throwing))
        val result = verifyManifestSignature(listOf(current), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT, verify = throwing)
        assertEquals(refused(SignatureRefusal.MALFORMED_INPUT), result)
    }

    @Test
    fun verifierInvalidKeyExceptionMakesKeyUnusable() {
        val throwing = verifyThrowing(InvalidKeyException("test failure"))
        assertEquals(CandidateOutcome.UNUSABLE_KEY, checkWithKey(current, signedBytes(), derBytes(), verify = throwing))
        val result = verifyManifestSignature(listOf(current), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT, verify = throwing)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }

    @Test
    fun verifierSecurityExceptionMakesKeyUnusable() {
        val throwing = verifyThrowing(NoSuchAlgorithmException("test failure"))
        assertEquals(CandidateOutcome.UNUSABLE_KEY, checkWithKey(current, signedBytes(), derBytes(), verify = throwing))
        val result = verifyManifestSignature(listOf(current), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT, verify = throwing)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }

    @Test
    fun verifierRuntimeExceptionMakesKeyUnusable() {
        val throwing = verifyThrowing(RuntimeException("test failure"))
        assertEquals(CandidateOutcome.UNUSABLE_KEY, checkWithKey(current, signedBytes(), derBytes(), verify = throwing))
        val result = verifyManifestSignature(listOf(current), null, ManifestVectors.VERSION_CODE, ManifestVectors.DIGEST, ManifestVectors.SIG_CURRENT, verify = throwing)
        assertEquals(refused(SignatureRefusal.NO_USABLE_KEY), result)
    }
}
