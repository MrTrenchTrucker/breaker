package dev.breaker.dictation.updater

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads the update manifest over https. Every failure is [ManifestLoad.Unavailable]; no exception stands for one.
 *
 * The request is a GET of the manifest address under the configured base, with the named timeouts. A base that the origin rule refuses is
 * never requested. A redirect or any non-2xx status is a failure, and its body is never read. A declared length over the cap is a
 * failure before any body byte is read. The body is read up to one byte past the cap, so a longer body is refused. A body shorter
 * than its declared length is a failure, and a malformed UTF-8 body is a failure. The reply is closed on every path, and no request
 * header is sent.
 *
 * Only an IOException from the opener is turned into a failure; any other exception is a port failure and propagates.
 */
internal class ManifestHttpSource(
    private val serverBase: String,
    private val opener: UpdateHttpOpener,
) : ManifestSource {
    override fun load(): ManifestLoad {
        val url = HttpRules.manifestUrl(serverBase) ?: return ManifestLoad.Unavailable
        val reply = try {
            opener.open(url, HttpRules.CONNECT_TIMEOUT_MS, HttpRules.READ_TIMEOUT_MS)
        } catch (error: IOException) {
            return ManifestLoad.Unavailable
        }
        return reply.use { readBody(it) }
    }

    private fun readBody(reply: UpdateHttpReply): ManifestLoad {
        if (!HttpRules.isSuccessStatus(reply.status)) return ManifestLoad.Unavailable
        if (reply.contentLength > ManifestReader.MAX_BYTES) return ManifestLoad.Unavailable
        val bytes = ByteArrayOutputStream()
        val count = copyUpTo(reply.body, ManifestReader.MAX_BYTES.toLong() + 1L) { b, o, l -> bytes.write(b, o, l) } ?: return ManifestLoad.Unavailable
        if (count > ManifestReader.MAX_BYTES) return ManifestLoad.Unavailable
        if (reply.contentLength >= 0L && count < reply.contentLength) return ManifestLoad.Unavailable
        val text = decodeStrictUtf8(bytes.toByteArray()) ?: return ManifestLoad.Unavailable
        return ManifestLoad.Loaded(text, serverBase)
    }
}
