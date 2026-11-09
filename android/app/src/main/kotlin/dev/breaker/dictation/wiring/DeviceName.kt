package dev.breaker.dictation.wiring

/**
 * The device name the app sends with the sign-in hand-off, or null to send none.
 *
 * A non-null answer always satisfies the auth-client validator (1 to 64 code points,
 * no ISO control character and no surrogate code point), so the finish never has to
 * refuse a name this function gives it.
 *
 * The work, in order: each usable part is trimmed; the parts are joined (the model
 * alone when it already starts with the manufacturer, ignoring case); the join is walked
 * by code point and every ISO control character and every lone surrogate code point is
 * dropped; a double space left by a drop is kept as it is, not collapsed; the ends are
 * trimmed; the result is clamped to 64 code points and trimmed again, so a cut that lands
 * on a space leaves no blank; a blank result is null.
 */
fun deviceNameOf(manufacturer: String?, model: String?): String? {
    val m = usablePart(manufacturer)
    val mo = usablePart(model)
    val raw = when {
        m == null && mo == null -> null
        m == null -> mo
        mo == null -> m
        mo.startsWith(m, ignoreCase = true) -> mo
        else -> "$m $mo"
    }
    if (raw == null) return null
    val kept = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val point = raw.codePointAt(i)
        if (!Character.isISOControl(point) && !point.isLoneSurrogate()) {
            kept.appendCodePoint(point)
        }
        i += Character.charCount(point)
    }
    val trimmed = kept.toString().trim()
    val clamped = clampToCodePoints(trimmed, 64)
    val named = clamped.trim()
    return if (named.isEmpty()) null else named
}

/** A part is usable when it is not null and not blank; the part is trimmed to its ends. */
private fun usablePart(raw: String?): String? {
    if (raw == null) return null
    val kept = raw.trim()
    return if (kept.isEmpty()) null else kept
}

/** True for a lone surrogate code point: a half of a pair without its other half. */
private fun Int.isLoneSurrogate(): Boolean = this in 0xD800..0xDFFF

/**
 * The first [limit] code points of [value]. A surrogate pair is one code point, so a cut
 * keeps the pair whole or drops it whole.
 */
private fun clampToCodePoints(value: String, limit: Int): String {
    var count = 0
    var i = 0
    while (i < value.length && count < limit) {
        count += 1
        i += Character.charCount(value.codePointAt(i))
    }
    return value.substring(0, i)
}
