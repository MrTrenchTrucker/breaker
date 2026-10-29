package dev.breaker.dictation.updater

/**
 * Why a manifest signature was not verified. The values are for tests and logs only: none of them carries
 * key material, a digest or a URL.
 */
internal enum class SignatureRefusal {
    /** The pinned key list is empty. */
    EMPTY_KEY_LIST,

    /** A pinned id is malformed, or two pinned ids are equal. */
    INVALID_KEY_LIST,

    /** The manifest's key id is present but malformed. */
    MALFORMED_KEY_ID,

    /** The manifest's key id is well formed, but no pinned key has it. */
    UNKNOWN_KEY_ID,

    /** The version code, the digest or the signature text is not in the form the check needs. */
    MALFORMED_INPUT,

    /** No selected key could be loaded or used by the provider. */
    NO_USABLE_KEY,

    /** A selected key was tried and did not verify the signed bytes. */
    BAD_SIGNATURE,
}

/** The outcome of a manifest signature check. */
internal sealed class SignatureVerdict {
    /** The selected pinned key verified the signed bytes. */
    internal object Verified : SignatureVerdict()

    /** The signature was not verified; [reason] says why. */
    internal data class NotVerified(val reason: SignatureRefusal) : SignatureVerdict()
}
