package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import javax.net.ssl.SSLException

/**
 * The real network implementation of [ModelFetcher].
 *
 * It is plain blocking code: one request at a time, redirects followed by
 * hand, every address checked against [DownloadLimits], every body copied
 * with a size cap into a file under the staging directory. It never hashes
 * and never trusts what it fetched; the installer's digest check is the
 * judge of the bytes.
 *
 * Failure contract, matching the port: a network problem comes back as
 * [ModelFetcher.Result.Failed] with a short reason that never carries an
 * address, path or query. Only a problem writing to the staging directory is
 * thrown, as an [IOException], after the partial file has been deleted.
 *
 * [cancelled] is polled before every request and once per chunk of a body.
 * A read that is already blocked in the socket ends at the read timeout.
 */
class HttpModelFetcher internal constructor(
    private val limits: DownloadLimits,
    private val cancelled: () -> Boolean,
    private val opener: HttpOpener,
    private val elapsedMillis: () -> Long,
    private val openOutput: (File) -> OutputStream,
    private val usableSpace: (File) -> Long,
) : ModelFetcher {

    constructor(
        limits: DownloadLimits = DownloadLimits(),
        cancelled: () -> Boolean = { false },
    ) : this(
        limits,
        cancelled,
        JdkHttpOpener(),
        { System.nanoTime() / 1_000_000L },
        { FileOutputStream(it) },
        { it.usableSpace },
    )

    override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
        if (!LocalModelStore.isSafeName(entry.id)) return ModelFetcher.Result.Failed("model id not allowed")
        val target = File(stagingDir, entry.id + DOWNLOAD_SUFFIX)
        return fetchTo(entry.url, target, limits.modelCapBytes(entry), stagingDir)
    }

    override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
        val target = File(stagingDir, CHECKSUMS_NAME)
        return fetchTo(limits.checksumsUrl, target, limits.checksumsMaxBytes, stagingDir)
    }

    /**
     * Fetch [startUrl] into [target]. The partial file is deleted on every path
     * except a complete, checked download.
     */
    private fun fetchTo(startUrl: String, target: File, capBytes: Long, stagingDir: File): ModelFetcher.Result {
        val startedAt = elapsedMillis()
        target.delete()
        var completed = false
        try {
            val result = transfer(startUrl, target, capBytes, stagingDir, startedAt)
            if (result is ModelFetcher.Result.Fetched) completed = true
            return result
        } catch (e: StagingWriteException) {
            throw e.failure
        } catch (e: SSLException) {
            return ModelFetcher.Result.Failed("tls")
        } catch (e: SocketTimeoutException) {
            return ModelFetcher.Result.Failed("timed out")
        } catch (e: IOException) {
            return ModelFetcher.Result.Failed("network: " + e.javaClass.simpleName)
        } catch (e: Exception) {
            return ModelFetcher.Result.Failed("unexpected")
        } finally {
            if (!completed) target.delete()
        }
    }

    /** The redirect loop. One request per pass; the reply is closed on every pass. */
    private fun transfer(
        startUrl: String,
        target: File,
        capBytes: Long,
        stagingDir: File,
        startedAt: Long,
    ): ModelFetcher.Result {
        var current: URI = parseAddress(startUrl) ?: return ModelFetcher.Result.Failed("address refused: invalid")
        var hop = 0
        while (true) {
            if (cancelled()) return ModelFetcher.Result.Failed("cancelled")
            if (elapsedMillis() - startedAt > limits.totalTimeoutMillis) return ModelFetcher.Result.Failed("timed out")
            if (!limits.isAllowed(current, hop)) return ModelFetcher.Result.Failed(refusedReason(current, hop))
            val reply = opener.open(
                current.toString(),
                REQUEST_HEADERS,
                limits.connectTimeoutMillis,
                limits.readTimeoutMillis,
            )
            try {
                if (REDIRECT_STATUSES.contains(reply.status)) {
                    val location = reply.location
                    if (location == null) return ModelFetcher.Result.Failed("redirect without location")
                    hop += 1
                    if (hop > limits.maxRedirects) return ModelFetcher.Result.Failed("too many redirects")
                    val next = resolveAddress(current, location)
                    if (next == null) return ModelFetcher.Result.Failed("redirect refused: invalid")
                    current = next
                    continue
                }
                return readReply(reply, target, capBytes, stagingDir, startedAt)
            } finally {
                closeQuietly(reply)
            }
        }
    }

    /** Check one final reply and copy its body into [target]. */
    private fun readReply(
        reply: HttpReply,
        target: File,
        capBytes: Long,
        stagingDir: File,
        startedAt: Long,
    ): ModelFetcher.Result {
        if (reply.status != 200) return ModelFetcher.Result.Failed("http " + reply.status)
        val contentType = reply.contentType
        if (contentType?.trimStart()?.startsWith(JSON_TYPE, ignoreCase = true) == true) {
            return ModelFetcher.Result.Failed("unexpected content type")
        }
        val declared = reply.contentLength
        if (declared > capBytes) return ModelFetcher.Result.Failed("too large")
        val needBytes = if (declared >= 0L) declared else capBytes
        if (usableSpace(stagingDir) < needBytes) throw StagingWriteException(IOException("not enough space"))
        val output = openStaging(target)
        try {
            var total = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                if (cancelled()) return ModelFetcher.Result.Failed("cancelled")
                if (elapsedMillis() - startedAt > limits.totalTimeoutMillis) return ModelFetcher.Result.Failed("timed out")
                val count = reply.body.read(buffer)
                if (count < 0) break
                total += count
                if (total > capBytes) return ModelFetcher.Result.Failed("too large")
                writeChunk(output, buffer, count)
            }
            finishOutput(output)
            if (declared >= 0L) {
                if (total != declared) return ModelFetcher.Result.Failed("truncated")
            }
            if (total == 0L) return ModelFetcher.Result.Failed("empty")
            return ModelFetcher.Result.Fetched(target)
        } finally {
            closeQuietly(output)
        }
    }

    private fun openStaging(target: File): OutputStream {
        try {
            return openOutput(target)
        } catch (e: IOException) {
            throw StagingWriteException(e)
        }
    }

    private fun writeChunk(output: OutputStream, buffer: ByteArray, count: Int) {
        try {
            output.write(buffer, 0, count)
        } catch (e: IOException) {
            throw StagingWriteException(e)
        }
    }

    private fun finishOutput(output: OutputStream) {
        try {
            output.flush()
            output.close()
        } catch (e: IOException) {
            throw StagingWriteException(e)
        }
    }

    private fun parseAddress(address: String): URI? {
        try {
            return URI(address)
        } catch (e: URISyntaxException) {
            return null
        }
    }

    private fun resolveAddress(base: URI, location: String): URI? {
        try {
            return base.resolve(location)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    private fun refusedReason(address: URI, hop: Int): String {
        val host = address.host
        val label = if (host == null) "none" else host.lowercase()
        if (hop == 0) return "address refused: " + label
        return "redirect refused: " + label
    }

    private fun closeQuietly(reply: HttpReply) {
        try {
            reply.close()
        } catch (e: IOException) {
            // A failed close cannot change what was already read.
        }
    }

    private fun closeQuietly(output: OutputStream) {
        try {
            output.close()
        } catch (e: IOException) {
            // Closing again after a finished or failed write adds nothing.
        }
    }

    /** A problem on the staging side, carried past the network-side mapping. */
    private class StagingWriteException(val failure: IOException) : Exception()

    private companion object {
        const val DOWNLOAD_SUFFIX = ".download"
        const val CHECKSUMS_NAME = "checksums.txt"
        const val CHUNK_BYTES = 64 * 1024
        const val JSON_TYPE = "application/json"
        const val ACCEPT_HEADER = "Accept"
        const val ACCEPT_VALUE = "application/octet-stream"
        const val ENCODING_HEADER = "Accept-Encoding"
        const val ENCODING_VALUE = "identity"
        const val AGENT_HEADER = "User-Agent"
        const val AGENT_VALUE = "dictation-model-fetcher"
        val REDIRECT_STATUSES: Set<Int> = setOf(301, 302, 303, 307, 308)
        val REQUEST_HEADERS: Map<String, String> = mapOf(
            ACCEPT_HEADER to ACCEPT_VALUE,
            ENCODING_HEADER to ENCODING_VALUE,
            AGENT_HEADER to AGENT_VALUE,
        )
    }
}
