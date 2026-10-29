package dev.breaker.dictation.updater

/**
 * Decodes [bytes] as UTF-8 and refuses every malformed sequence: overlong forms, encoded surrogates, values above U+10FFFF,
 * truncated sequences, and stray continuation or 0xFF bytes. A malformed input gives null; no replacement character is ever
 * produced. A byte order mark is kept as U+FEFF.
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String? =
    if (isWellFormedUtf8(bytes)) String(bytes, Charsets.UTF_8) else null

/** The sequence length and the allowed range of the second byte for a lead byte, or null when the byte cannot start a sequence. */
private fun leadRule(lead: Int): List<Int>? = when (lead) {
    in 0xC2..0xDF -> listOf(2, 0x80, 0xBF)
    0xE0 -> listOf(3, 0xA0, 0xBF)
    in 0xE1..0xEC, 0xEE, 0xEF -> listOf(3, 0x80, 0xBF)
    0xED -> listOf(3, 0x80, 0x9F)
    0xF0 -> listOf(4, 0x90, 0xBF)
    in 0xF1..0xF3 -> listOf(4, 0x80, 0xBF)
    0xF4 -> listOf(4, 0x80, 0x8F)
    else -> null
}

private fun isWellFormedUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val lead = bytes[i].toInt() and 0xFF
        if (lead < 0x80) {
            i += 1
        } else {
            val rule = leadRule(lead) ?: return false
            val length = rule[0]
            if (i + length > bytes.size) return false
            for (k in 1 until length) {
                val b = bytes[i + k].toInt() and 0xFF
                val allowed = if (k == 1) rule[1]..rule[2] else 0x80..0xBF
                if (b !in allowed) return false
            }
            i += length
        }
    }
    return true
}
