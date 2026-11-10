package dev.breaker.dictation.updater

import java.io.File

/** What verifying one update came to. Never thrown: every outcome is one of these two. */
internal sealed class UpdateVerificationResult {
    /** The download, its digest and the signature all passed; [file] is the verified APK. */
    data class Ready(val file: File) : UpdateVerificationResult()

    /** The update is refused; [reason] is a fixed sentence. */
    data class Refused(val reason: String) : UpdateVerificationResult()
}

/**
 * The one sentence this step adds. A refusal of the download or of the signature carries that step's own
 * fixed sentence; nothing here holds a key, a signature, a URL, a path or exception text.
 */
internal object UpdateVerificationReasons {
    const val UNEXPECTED = "the update verification could not finish"
}

/**
 * Joins the download and the signature check into one verification, in the order the update ruling sets:
 * download (its digest check is inside it), then signature, and only then is the file ready.
 *
 * The download runs first. If it is refused, its own reason is returned and the signature check is never
 * called. Then the signature is checked with the manifest's version, digest and signature exactly as given.
 * If it is refused, the downloaded file is deleted (best effort) and the signature reason is returned. A
 * file is [UpdateVerificationResult.Ready] only when both steps passed.
 *
 * Never throws: any exception from either step, an interrupt included, becomes
 * [UpdateVerificationReasons.UNEXPECTED], and after a signature step that threw the file is deleted too.
 * No install, no Android classes, no app wiring.
 */
internal class UpdateVerification(
    private val download: (apkUrl: String, sha256: String) -> ApkDownloadResult,
    private val checkSignature: (version: String, sha256: String, signature: String) -> SignatureResult,
) {

    fun verify(version: String, apkUrl: String, sha256: String, signature: String): UpdateVerificationResult {
        val downloaded = try {
            download(apkUrl, sha256)
        } catch (_: Exception) {
            return UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED)
        }
        val file = when (downloaded) {
            is ApkDownloadResult.Refused -> return UpdateVerificationResult.Refused(downloaded.reason)
            is ApkDownloadResult.Downloaded -> downloaded.file
        }

        val checked = try {
            checkSignature(version, sha256, signature)
        } catch (_: Exception) {
            discard(file)
            return UpdateVerificationResult.Refused(UpdateVerificationReasons.UNEXPECTED)
        }
        return when (checked) {
            is SignatureResult.Verified -> UpdateVerificationResult.Ready(file)
            is SignatureResult.Refused -> {
                discard(file)
                UpdateVerificationResult.Refused(checked.reason)
            }
        }
    }

    /** Deletes the downloaded file; a file that cannot be deleted changes no answer. */
    private fun discard(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
            // best effort: the refusal that is being answered does not depend on it
        }
    }
}
