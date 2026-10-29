package dev.breaker.dictation.updater

/** The lowercase hexadecimal digits, indexed by nibble value. */
private const val HEX = "0123456789abcdef"

/** True when [text] is exactly 64 characters, each one a digit from 0 to 9 or a letter from a to f. */
internal fun isLowerSha256Hex(text: String): Boolean {
    return text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Encodes [bytes] as lowercase hexadecimal, two characters per byte. */
internal fun toLowerHex(bytes: ByteArray): String = buildString {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        append(HEX[v ushr 4])
        append(HEX[v and 0x0F])
    }
}
