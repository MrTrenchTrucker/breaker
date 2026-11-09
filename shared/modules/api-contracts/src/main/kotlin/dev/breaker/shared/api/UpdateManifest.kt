package dev.breaker.shared.api

/**
 * The latest signed update manifest.
 *
 * Mirrors the `UpdateManifest` schema in the OpenAPI spec. The
 * `versionCode` field is at least 1, matching the spec minimum.
 */
data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val apkUrl: String,
    val sha256: String,
    val signature: String,
    val notes: String,
) {
    init {
        require(versionCode >= 1L) {
            "versionCode must be at least 1"
        }
    }
}
