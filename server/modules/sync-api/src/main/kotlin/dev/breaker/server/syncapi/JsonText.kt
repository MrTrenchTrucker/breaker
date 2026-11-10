package dev.breaker.server.syncapi

/** Escapes [value] as a quoted JSON string.
 *
 * Escapes: quote ("), backslash (\), newline (\n), CR (\r), tab (\t),
 * backspace (\b), formfeed (\f), and control characters U+0000..U+001F
 * as \u00XX (lowercase hex, exactly four digits). Wraps the result in
 * double quotes so the caller does not add its own.
 *
 * Left as-is: U+2028 and U+2029 (valid unescaped in JSON strings), lone
 * surrogates (invalid input, passed through), and surrogate pairs
 * (valid JSON, passed through as two escaped-or-raw code units).
 *
 * No dependency on kotlinx.serialization, Gson, Jackson, or whisper's
 * escapeJsonString (another module). Same escaping set as whisper's,
 * re-implemented here so sync-api stays self-contained.
 */
internal fun escapeJsonString(value: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (ch in value) {
        when (ch.code) {
            0x22 -> sb.append("\\\"")   // quote
            0x5C -> sb.append("\\\\")   // backslash
            0x08 -> sb.append("\\b")    // backspace
            0x0C -> sb.append("\\f")    // formfeed
            0x0A -> sb.append("\\n")    // newline
            0x0D -> sb.append("\\r")    // carriage return
            0x09 -> sb.append("\\t")    // tab
            in 0x00..0x1F -> {
                sb.append("\\u00")
                sb.append(hexDigit((ch.code ushr 4) and 0xF))
                sb.append(hexDigit(ch.code and 0xF))
            }
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun hexDigit(nibble: Int): Char = "0123456789abcdef"[nibble]
