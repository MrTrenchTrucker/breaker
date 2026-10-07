package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import java.net.URI

/**
 * Every bound the real fetcher works within.
 *
 * All values are plain parameters so a test can tighten any of them. The
 * defaults suit a phone on mobile data fetching from the release host.
 *
 * @property connectTimeoutMillis longest wait for a connection to open.
 * @property readTimeoutMillis longest wait for the next bytes of a reply.
 * @property totalTimeoutMillis longest time for one whole fetch, so a body
 *   that trickles in cannot outlast the per-read timeout forever.
 * @property maxRedirects most redirects followed for one fetch.
 * @property checksumsMaxBytes largest checksum list accepted.
 * @property firstHopHosts hosts the first request may go to.
 * @property redirectHosts hosts a redirect may go to.
 * @property checksumsUrl address of the upstream checksum list.
 */
data class DownloadLimits(
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 30_000,
    val totalTimeoutMillis: Long = 3_600_000L,
    val maxRedirects: Int = 5,
    val checksumsMaxBytes: Long = 1_048_576L,
    val firstHopHosts: Set<String> = setOf("api.github.com"),
    val redirectHosts: Set<String> = setOf("release-assets.githubusercontent.com"),
    val checksumsUrl: String = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/424735889",
) {
    /**
     * The largest body accepted for [entry].
     *
     * The registry size is given to the nearest whole MiB, so the true size
     * is always below that figure plus one MiB.
     */
    fun modelCapBytes(entry: ModelEntry): Long = (entry.sizeMb.toLong() + 1L) * 1_048_576L

    /**
     * The whole address policy for one request.
     *
     * An address is allowed when it is https, its host is in the set for this
     * hop (the first request or a later redirect), it carries no user-info,
     * and its port is the default one or 443.
     */
    internal fun isAllowed(url: URI, hopIndex: Int): Boolean {
        val scheme = url.scheme
        if (scheme == null) return false
        if (!scheme.equals("https", ignoreCase = true)) return false
        val host = url.host
        if (host == null) return false
        val lowerHost = host.lowercase()
        val hosts = if (hopIndex == 0) firstHopHosts else redirectHosts
        if (!hosts.contains(lowerHost)) return false
        if (url.userInfo != null) return false
        val port = url.port
        if (port == -1) return true
        return port == 443
    }
}
