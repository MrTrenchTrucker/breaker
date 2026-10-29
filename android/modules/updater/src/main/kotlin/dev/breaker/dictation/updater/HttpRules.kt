package dev.breaker.dictation.updater

/**
 * The fixed rules of the update HTTP reads: the timeouts, the manifest path, the success statuses and the request header list.
 *
 * The timeouts are per read call. The connect timeout is ten seconds, because a phone on a poor link needs time to connect and a
 * longer wait would block the check. The manifest read timeout is fifteen seconds, because a manifest of at most 64 KiB arrives in
 * a few reads. The APK read timeout is thirty seconds, because a large file on a slow link takes longer per read call.
 *
 * TLS uses the platform default trust store. There is no custom trust manager and no certificate pin: the manifest signature is
 * the authority for the content.
 */
internal object HttpRules {
    internal const val CONNECT_TIMEOUT_MS: Int = 10_000
    internal const val READ_TIMEOUT_MS: Int = 15_000
    internal const val APK_READ_TIMEOUT_MS: Int = 30_000
    internal const val MANIFEST_PATH: String = "/v1/updates/latest.json"

    /** The request headers the opener sends. The list is empty: no update request carries a header of any kind. */
    fun requestHeaders(): List<Pair<String, String>> = emptyList()

    /** True for a 2xx status only. Redirects are not followed, so a redirect status is never a success. */
    fun isSuccessStatus(status: Int): Boolean = status in 200..299

    /** The manifest address under [serverBase], with one trailing slash removed; null when the origin rule refuses the base. */
    fun manifestUrl(serverBase: String): String? {
        if (ApkOrigin.refusalForBase(serverBase) != null) return null
        return serverBase.removeSuffix("/") + MANIFEST_PATH
    }
}
