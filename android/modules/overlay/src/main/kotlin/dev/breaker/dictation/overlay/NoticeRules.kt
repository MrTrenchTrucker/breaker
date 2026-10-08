package dev.breaker.dictation.overlay

/** Cleans up the sentence the app gives the tile to show, so the tile can draw it in at most two short lines. */
internal object NoticeRules {
    /** The most characters a notice keeps. */
    const val MAX_CHARS: Int = 80

    /** The line-break and tab characters, by code: line feed, tab, vertical tab, form feed, carriage return, and the Unicode line breaks. */
    private val breakCodes = setOf(0x0A, 0x09, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)

    /**
     * The notice to show for [text], or null when there is nothing to show.
     *
     * A null or blank text gives null. Each line break and each tab becomes one space. The text is
     * trimmed. A text longer than [MAX_CHARS] is cut to [MAX_CHARS] characters, one fewer when the cut
     * would split a surrogate pair, and trimmed again. A result is never blank and never longer than
     * [MAX_CHARS].
     */
    fun normalise(text: String?): String? {
        if (text == null) return null
        val flat = text.map { if (it.code in breakCodes) ' ' else it }.joinToString("").trim()
        if (flat.isEmpty()) return null
        if (flat.length <= MAX_CHARS) return flat
        val splitsPair = flat[MAX_CHARS - 1].isHighSurrogate() && flat[MAX_CHARS].isLowSurrogate()
        return flat.substring(0, if (splitsPair) MAX_CHARS - 1 else MAX_CHARS).trimEnd()
    }
}
