package dev.breaker.dictation.stt.ondevice

/**
 * Reads the token table of a keyword model.
 *
 * Each token line is the token, one space, and its number (digits only), for example `ER 123`. The token is
 * everything before the last space, so a token that itself holds a space would be kept whole. A line with no
 * space, a blank token, an empty number or a number that is not all digits is not a token line and is skipped.
 * Line breaks of either style are accepted. The format is taken from the shipped model's own table; no other
 * layout is read here.
 */
internal object KeywordTokenFile {

    /** The set of tokens named by the token lines of [text]. */
    fun tokensOf(text: String): Set<String> {
        val tokens = LinkedHashSet<String>()
        for (line in text.lines()) {
            val cut = line.lastIndexOf(' ')
            if (cut <= 0) continue
            val number = line.substring(cut + 1)
            if (number.isEmpty() || !number.all { it in '0'..'9' }) continue
            val piece = line.substring(0, cut)
            if (piece.isBlank()) continue
            tokens.add(piece)
        }
        return tokens
    }
}
