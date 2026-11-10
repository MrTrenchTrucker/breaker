package dev.breaker.dictation.updater

/** The result of verifying an update signature. Never throws. */
internal sealed class SignatureResult {
    /** The signature verifies under one of the pinned keys. */
    object Verified : SignatureResult()

    /** The signature is refused; [reason] is a fixed sentence. */
    data class Refused(val reason: String) : SignatureResult()
}
