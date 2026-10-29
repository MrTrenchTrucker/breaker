package dev.breaker.dictation.updater

/** The fixed first line of every signed manifest string. */
internal const val SIGNED_PREFIX: String = "breaker-update-v1"

/** The largest version code a manifest may carry. */
private const val MAX_VERSION_CODE: Long = 2100000000L

/** The longest canonical version code text: ten decimal digits. */
private const val MAX_VERSION_CODE_LENGTH: Int = 10

/**
 * Builds the bytes that a manifest signature covers: the prefix, a line feed, the version code text, a line feed
 * and the digest, as UTF-8 with no trailing line feed.
 *
 * Returns null when [versionCode] is not canonical decimal (1 to 2100000000, with no sign, no leading zero, no
 * space and no other character), or when [sha256] is not 64 lowercase hexadecimal characters.
 */
internal fun manifestSignedBytes(versionCode: String, sha256: String): ByteArray? {
    if (!isCanonicalVersionCode(versionCode) || !isLowerSha256Hex(sha256)) return null
    return "$SIGNED_PREFIX\n$versionCode\n$sha256".toByteArray(Charsets.UTF_8)
}

/** True when [text] is canonical decimal for a version code: 1 to 2100000000, no sign, no leading zero. */
private fun isCanonicalVersionCode(text: String): Boolean {
    if (text.isEmpty() || text.length > MAX_VERSION_CODE_LENGTH) return false
    if (text[0] !in '1'..'9') return false
    if (!text.all { it in '0'..'9' }) return false
    return text.toLong() <= MAX_VERSION_CODE
}
