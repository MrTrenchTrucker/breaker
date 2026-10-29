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

/**
 * What an install attempt came to. A refused or failed install is a value here,
 * never an exception.
 */
sealed class UpdateInstallResult {
    /** The installer accepted the verified file. */
    data object Installed : UpdateInstallResult()

    /**
     * The release was refused on purpose: a digest or signature mismatch, a wrong
     * origin, a version that is not newer, or an install that is not allowed.
     * [reason] is one short plain sentence for the user. It never holds a URL, a
     * digest or key material.
     */
    data class Refused(val reason: String) : UpdateInstallResult() {
        init {
            require(reason.isNotBlank()) { "A refusal needs a reason" }
        }
    }

    /**
     * Something broke while installing: the network, the disk or the installer.
     * [cause] is one short plain sentence, with the same rules as [Refused.reason].
     */
    data class Failed(val cause: String) : UpdateInstallResult() {
        init {
            require(cause.isNotBlank()) { "A failure needs a cause" }
        }
    }
}
