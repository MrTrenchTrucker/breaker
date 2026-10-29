package dev.breaker.server.whisper.json

/**
 * Shared by the forwarder and the HTTP layer so the two can never disagree.
 */
internal fun escapeJsonString(value: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
