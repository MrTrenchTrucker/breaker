package dev.breaker.server.whisper.subtitle

import kotlin.math.floor

// Text and time shaping shared by the SRT and VTT writers. Every function is pure: no clock,
// no I/O, no shared state, so the writers are safe to call from any thread.

/** Trims [text], then collapses every run of blanks (space, tab, CR, LF) to one space. */
internal fun collapseToOneLine(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val out = StringBuilder()
    var pendingBlank = false
    for (c in trimmed) {
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            pendingBlank = true
        } else {
            if (pendingBlank) out.append(' ')
            pendingBlank = false
            out.append(c)
        }
    }
    return out.toString()
}

/** A negative start becomes zero; an end before the start becomes the start. */
internal fun clampTimes(start: Double, end: Double): Pair<Double, Double> {
    val clampedStart = if (start < 0.0) 0.0 else start
    val clampedEnd = if (end < clampedStart) clampedStart else end
    return Pair(clampedStart, clampedEnd)
}

/** Rounds seconds to the nearest millisecond, a half rounding up. */
internal fun millisOf(seconds: Double): Long {
    val exact = seconds * 1000.0
    val lower = floor(exact)
    return if (exact - lower >= 0.5) (lower + 1.0).toLong() else lower.toLong()
}
