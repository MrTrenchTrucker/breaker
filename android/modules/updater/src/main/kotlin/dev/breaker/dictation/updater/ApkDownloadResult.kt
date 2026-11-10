package dev.breaker.dictation.updater

import java.io.File

/** What asking for the APK download came to. Never throws: every outcome is one of these two. */
internal sealed class ApkDownloadResult {
    /** The APK was downloaded and verified; [file] is the destination. */
    data class Downloaded(val file: File) : ApkDownloadResult()

    /** The download was refused; [reason] is a fixed sentence. */
    data class Refused(val reason: String) : ApkDownloadResult()
}
