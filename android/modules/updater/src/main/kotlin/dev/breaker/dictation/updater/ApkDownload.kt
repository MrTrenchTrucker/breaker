package dev.breaker.dictation.updater

import dev.breaker.dictation.http.CallMethod
import dev.breaker.dictation.http.CallRequest
import dev.breaker.dictation.http.CallLimits
import dev.breaker.dictation.http.HttpRefusal
import dev.breaker.dictation.http.HttpRefusalReason
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.http.ServerAddressRule
import dev.breaker.dictation.http.StreamReply
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** The connect/read timeouts and the reply cap for the APK download, from ADR-030 L13. */
private const val APK_CONNECT_TIMEOUT_MS = 10_000
private const val APK_READ_TIMEOUT_MS = 30_000
private const val APK_CAP_BYTES: Long = 268_435_456L

private const val APK_DIGEST_HEX_LEN = 64
private const val TEMP_SUFFIX = ".part"

/**
 * Downloads the APK offered by an update manifest and verifies its SHA-256 before it lands.
 *
 * [apkUrl] is untrusted text from the manifest. The download may only fetch from the configured
 * server address: same scheme, same host, same effective port (80 for http, 443 for https when
 * absent). Any other host, a different scheme, userinfo, a blank or relative URL: refused before
 * any connection. The transport's own [ServerAddressRule] is the second gate.
 *
 * The APK is never held in memory: the stream is read chunk by chunk, the digest is computed as
 * it goes, and the bytes land in a `.part` temp file beside the destination. Only when the digest
 * matches is the temp file moved over the destination (atomic move + replace). On every other
 * path the temp file is deleted and an old destination is never touched.
 *
 * Never throws: every outcome is [ApkDownloadResult.Downloaded] or [ApkDownloadResult.Refused]
 * with a fixed sentence. The call blocks; the caller runs it off the UI thread.
 */
internal class ApkDownload(
    private val transport: HttpTransport,
    private val serverUrl: () -> String,
    private val destination: () -> File,
) {

    fun download(apkUrl: String, sha256: String): ApkDownloadResult {
        return try {
            if (!isValidSha256Hex(sha256)) {
                ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DIGEST)
            } else {
                doDownload(apkUrl, sha256)
            }
        } catch (_: Exception) {
            ApkDownloadResult.Refused(ApkDownloadReasons.UNFINISHED)
        }
    }

    private fun doDownload(apkUrl: String, sha256: String): ApkDownloadResult {
        val configured = try {
            serverUrl()
        } catch (_: Exception) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DESTINATION)
        }

        // 1. Same origin: same scheme, host, effective port.
        if (!sameOrigin(apkUrl, configured)) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.NOT_SAME_ORIGIN)
        }

        // 2. The transport's own address rule as the second gate.
        if (!ServerAddressRule.permits(apkUrl, configured)) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.NOT_ALLOWED)
        }

        // 3. Open the stream.
        val reply = try {
            transport.open(
                CallRequest(
                    method = CallMethod.GET,
                    url = apkUrl,
                    headers = listOf("Accept" to "application/vnd.android.package-archive"),
                    body = null,
                    limits = CallLimits(
                        connectTimeoutMs = APK_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = APK_READ_TIMEOUT_MS,
                        maxReplyBytes = APK_CAP_BYTES,
                    ),
                )
            )
        } catch (_: HttpRefusal) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.NOT_ALLOWED)
        } catch (_: IOException) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.UNREACHABLE)
        } catch (_: Exception) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.UNFINISHED)
        }

        // Close the reply on every path.
        return reply.use { r ->
            // 4. Status must be 200.
            if (r.status != 200) {
                ApkDownloadResult.Refused(ApkDownloadReasons.BAD_STATUS)
            } else {
                doStream(r, sha256)
            }
        }
    }

    private fun doStream(reply: StreamReply, sha256: String): ApkDownloadResult {
        // 5. Known content length above the cap: refuse before downloading.
        val announced = reply.contentLength
        if (announced >= 0L && announced > APK_CAP_BYTES) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.REPLY_TOO_LARGE)
        }

        // 6. Destination: provider must not throw, and the parent directory must exist.
        val dest = try {
            destination()
        } catch (_: Exception) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DESTINATION)
        }
        val parent = dest.parentFile
        if (parent == null || !parent.exists()) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DESTINATION)
        }

        // 7. Stream to a .part temp file beside the destination; truncate if it exists.
        val temp = File(parent, dest.name + TEMP_SUFFIX)
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (_: NoSuchAlgorithmException) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.UNFINISHED)
        }

        val body: InputStream = reply.body
        val buffer = ByteArray(8192)
        var served = 0L
        var capped = false

        // Closed on every path: the reply drops its connection, and a finally block below
        // deletes the temp file unless a successful move happened.
        var moved = false
        val out = try {
            temp.outputStream()
        } catch (_: Exception) {
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DESTINATION)
        }
        try {
            out.use {
                while (true) {
                    val count = body.read(buffer)
                    if (count < 0) break
                    served += count
                    if (served > APK_CAP_BYTES) {
                        capped = true
                        break
                    }
                    it.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        } catch (refusal: HttpRefusal) {
            temp.delete()
            return ApkDownloadResult.Refused(
                when (refusal.reason) {
                    HttpRefusalReason.REPLY_TOO_LARGE -> ApkDownloadReasons.REPLY_TOO_LARGE
                    HttpRefusalReason.LENGTH_MISMATCH -> ApkDownloadReasons.LENGTH_MISMATCH
                    else -> ApkDownloadReasons.NOT_ALLOWED
                },
            )
        } catch (_: IOException) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.UNREACHABLE)
        } catch (_: Exception) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.UNFINISHED)
        }

        if (capped) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.REPLY_TOO_LARGE)
        }

        // Short read: announced length known and we received less.
        if (announced >= 0L && served < announced) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.LENGTH_MISMATCH)
        }

        // 8. Compare the computed digest with the offered one.
        val computed = digest.digest()
        if (!ApkDigest.matchesDigest(computed, sha256)) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DIGEST)
        }

        // 9. Move the temp file over the destination, atomically.
        try {
            Files.move(
                temp.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            moved = true
        } catch (_: Exception) {
            temp.delete()
            return ApkDownloadResult.Refused(ApkDownloadReasons.BAD_DESTINATION)
        } finally {
            if (!moved) {
                temp.delete()
            }
        }

        return ApkDownloadResult.Downloaded(dest)
    }

    private fun isValidSha256Hex(text: String): Boolean {
        if (text.length != APK_DIGEST_HEX_LEN) return false
        return text.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }

    private fun sameOrigin(apkUrl: String, configuredServer: String): Boolean {
        val apk = parseOrigin(apkUrl) ?: return false
        val own = parseOrigin(configuredServer) ?: return false
        return apk.scheme == own.scheme && apk.host == own.host && apk.port == own.port
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun parseOrigin(text: String): Origin? {
        if (text.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val sep = text.indexOf("://")
        if (sep < 0) return null
        val schemeRaw = text.substring(0, sep)
        if (!schemeRaw.all { it.code < 128 }) return null
        val scheme = schemeRaw.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val defaultPort = if (scheme == "https") 443 else 80
        val afterScheme = text.substring(sep + 3)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return null
        return parseAuthority(authority, scheme, defaultPort)
    }

    private fun parseAuthority(authority: String, scheme: String, defaultPort: Int): Origin? {
        val hostEnd = hostEndOf(authority)
        if (hostEnd < 0) return null
        val host = authority.substring(0, hostEnd)
        val usable = if (host.startsWith('[')) isIpv6Literal(host) else isHostName(host)
        if (!usable) return null
        val portText = authority.substring(hostEnd)
        val port = if (portText.isEmpty()) defaultPort else parsePort(portText)
        if (port == null) return null
        return Origin(scheme, host.lowercase(), port)
    }

    private fun parsePort(text: String): Int? {
        if (!text.startsWith(':')) return null
        val digits = text.substring(1)
        if (digits.isEmpty() || digits.length > 5 || !digits.all { it in '0'..'9' }) return null
        val value = digits.toInt()
        return if (value in 1..65535) value else null
    }

    private fun isHostName(host: String): Boolean =
        host.isNotEmpty() && host.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' }

    private fun isIpv6Literal(bracketed: String): Boolean {
        if (bracketed.length < 3 || !bracketed.endsWith(']')) return false
        val inner = bracketed.substring(1, bracketed.length - 1)
        if (inner.count { it == ':' } < 2) return false
        return inner.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == ':' || c == '.' }
    }

    private fun hostEndOf(authority: String): Int {
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            return if (close < 0) -1 else close + 1
        }
        val colon = authority.indexOf(':')
        return if (colon < 0) authority.length else colon
    }
}
