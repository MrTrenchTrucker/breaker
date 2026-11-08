package dev.breaker.server.syncapi.auth

/** True when [text] is exactly four characters, each 0-9, a-f or A-F: no sign, no space, no prefix. */
internal fun isFourHexDigits(text: String): Boolean {
    if (text.length != 4) return false
    for (ch in text) {
        val isHex = (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')
        if (!isHex) return false
    }
    return true
}
