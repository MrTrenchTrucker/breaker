package dev.breaker.server.syncapi.sync

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Appends [value] as one JSON string: the quotation mark, the backslash and the control characters
 * are escaped, and every other character is written as it is.
 */
internal fun appendJsonString(out: StringBuilder, value: String) {
    out.append('"')
    for (c in value) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 -> {
                out.append("\\u00")
                out.append(HEX_DIGITS[c.code shr 4])
                out.append(HEX_DIGITS[c.code and 0x0F])
            }
            else -> out.append(c)
        }
    }
    out.append('"')
}
