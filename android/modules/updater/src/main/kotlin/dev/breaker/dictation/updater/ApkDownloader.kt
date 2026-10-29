package dev.breaker.dictation.updater

import java.io.InputStream

/** Downloads an APK into a staged file that the install reads later. */
internal interface ApkDownloader {
    /** Downloads the file at [apkUrl]. An unreachable server is [ApkDownload.Unavailable]; no exception stands for it. */
    fun download(apkUrl: String): ApkDownload
}

/** The result of one download. */
internal sealed class ApkDownload {
    /** The file reached the device and is staged. */
    internal data class Staged(val apk: StagedApk) : ApkDownload()

    /** The file could not be downloaded. */
    internal object Unavailable : ApkDownload()
}

/**
 * A downloaded file that is held until the install ends.
 *
 * The bytes must not change between the digest check and the installer call. The installer adapter hashes the
 * bytes while it copies them into the install session, which is intended to make the installed bytes the bytes
 * that were hashed; a change before the copy gives a digest mismatch and nothing is installed. The path is reused
 * by the next download. [discard] is guaranteed by this port
 * contract not to throw; an adapter that cannot remove the file records that inside itself.
 */
internal interface StagedApk {
    /** Opens the staged file for reading, or returns null when it cannot be opened. */
    fun open(): InputStream?

    /** Removes the staged file. Guaranteed not to throw. */
    fun discard()
}
