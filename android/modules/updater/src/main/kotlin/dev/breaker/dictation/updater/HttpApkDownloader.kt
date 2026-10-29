package dev.breaker.dictation.updater

import java.io.IOException
import java.io.OutputStream

/**
 * The place a download writes to. A slot holds one staged file, and a new download replaces it.
 *
 * [openForWrite] gives the stream the bytes go to, or null when the file cannot be opened. [removeQuietly] removes the
 * staged file after a failed download and never throws.
 */
internal interface StagingSlot {
    fun openForWrite(): OutputStream?
    fun stagedApk(): StagedApk
    fun removeQuietly()
}

/**
 * Downloads the update file over https into [slot] and returns it as staged.
 *
 * The request uses the platform default trust store, with no custom trust manager and no certificate pin. It sends
 * no request header and follows no redirect. At most [capBytes] plus one byte are written, so the checker can refuse
 * a file that is over the cap. A non-2xx status, a short body, a write failure or a failure to close the output
 * removes the partial file and gives [ApkDownload.Unavailable]. The reply is closed on every path.
 */
internal class HttpApkDownloader(
    private val opener: UpdateHttpOpener,
    private val slot: StagingSlot,
    private val capBytes: Long = MAX_APK_BYTES,
) : ApkDownloader {
    override fun download(apkUrl: String): ApkDownload {
        val reply = try {
            opener.open(apkUrl, HttpRules.CONNECT_TIMEOUT_MS, HttpRules.APK_READ_TIMEOUT_MS)
        } catch (error: IOException) {
            return ApkDownload.Unavailable
        }
        return reply.use { writeBody(it) }
    }

    private fun writeBody(reply: UpdateHttpReply): ApkDownload {
        if (!HttpRules.isSuccessStatus(reply.status)) return ApkDownload.Unavailable
        val out = slot.openForWrite() ?: return ApkDownload.Unavailable
        val copied: Long? = try {
            out.use { copyUpTo(reply.body, capBytes + 1L) { b, o, l -> out.write(b, o, l) } }
        } catch (failure: IOException) {
            null
        }
        if (copied == null) return removedUnavailable()
        if (reply.contentLength >= 0L && copied < reply.contentLength) return removedUnavailable()
        return ApkDownload.Staged(slot.stagedApk())
    }

    private fun removedUnavailable(): ApkDownload {
        slot.removeQuietly()
        return ApkDownload.Unavailable
    }
}
