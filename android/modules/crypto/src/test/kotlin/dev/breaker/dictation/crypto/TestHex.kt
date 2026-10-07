package dev.breaker.dictation.crypto

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Decodes hex text into bytes. Spaces and line breaks are ignored and both
 * letter cases are accepted. An odd digit count or a character that is not a
 * hex digit throws [IllegalArgumentException].
 */
internal fun hex(s: String): ByteArray {
    val digits = s.filter { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
    require(digits.length % 2 == 0) { "odd number of hex digits: ${digits.length}" }
    return ByteArray(digits.length / 2) { i ->
        ((hexDigitValue(digits[2 * i]) shl 4) or hexDigitValue(digits[2 * i + 1])).toByte()
    }
}

/** Encodes the bytes as lowercase hex with no separators. */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[2 * i] = HEX_DIGITS[v shr 4]
        out[2 * i + 1] = HEX_DIGITS[v and 0x0f]
    }
    return out.concatToString()
}

private fun hexDigitValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$c'")
}
