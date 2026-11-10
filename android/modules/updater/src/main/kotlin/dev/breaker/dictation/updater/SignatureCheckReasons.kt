package dev.breaker.dictation.updater

/**
 * Fixed refusal sentences for the signature check. None holds key bytes, signature bytes,
 * a version, or a digest, so a caller can show or log one as it is.
 */
internal object SignatureCheckReasons {
    const val BAD_VERSION = "the update version is not usable"
    const val BAD_DIGEST = "the release digest is not usable"
    const val BAD_SIGNATURE = "the update signature is not usable"
    const val NO_PINNED_KEY = "no pinned update key is configured"
    const val SIGNATURE_NOT_ACCEPTED = "no pinned key accepts the update signature"
}
