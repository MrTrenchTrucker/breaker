package dev.breaker.server.syncapi.transcriptions

// The bound of the older-rows read, as the client sends it in older_than: a stored time in
// milliseconds, or that time and a change number joined by a dash. Digits only, no sign.
internal data class OlderBound(val storedAtMs: Long, val change: Long?) {
    /** The text of this bound: the time alone, or the time, a dash and the change number. */
    fun render(): String = if (change == null) "$storedAtMs" else "$storedAtMs-$change"
}

// One digit run for the time (at most 15 digits), then an optional dash and a change (at most 19 digits).
private val OLDER_BOUND_PATTERN = Regex("^[0-9]{1,15}(-[0-9]{1,19})?$")

/**
 * Parses [text] into an OlderBound, or returns null when it must be refused: a null text, a text that
 * does not match the pattern, or a part that does not fit a Long. The pattern is checked before any
 * number is read, so a plus sign or a minus sign never reaches toLongOrNull. Leading zeros are digits
 * and are accepted: the value is what counts.
 */
internal fun parseOlderBound(text: String?): OlderBound? {
    if (text == null) return null
    if (!OLDER_BOUND_PATTERN.matches(text)) return null
    val parts = text.split("-")
    val storedAtMs = parts[0].toLongOrNull() ?: return null
    if (parts.size == 1) return OlderBound(storedAtMs, null)
    val change = parts[1].toLongOrNull() ?: return null
    return OlderBound(storedAtMs, change)
}
