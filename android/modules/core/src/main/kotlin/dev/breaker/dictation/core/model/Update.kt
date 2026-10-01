package dev.breaker.dictation.core.model

/**
 * A release the server is offering.
 *
 * [sha256] and [signature] are both checked before anything is installed: the
 * digest says the file arrived intact, the signature says it came from us. A
 * mismatch refuses the update rather than warning about it.
 */
data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    val sha256: String,
    val signature: String,
    val notes: String = "",
) {
    init {
        require(version.isNotBlank()) { "A release needs a version" }
        require(apkUrl.isNotBlank()) { "A release needs a download URL" }
        require(sha256.isNotBlank()) { "A release needs a digest to verify against" }
        require(signature.isNotBlank()) { "A release needs a signature to verify against" }
    }
}

/** What an update check found. */
sealed class UpdateCheckResult {
    /** Nothing newer than what is running. */
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()

    /** A newer release, verified. */
    data class Available(val release: ReleaseInfo) : UpdateCheckResult()

    /** The check could not finish. Silent retry later; the app still runs. */
    data class Failed(val reason: String) : UpdateCheckResult()
}
