package dev.breaker.dictation.stt.ondevice

import java.math.BigDecimal

/**
 * Writes and checks the text of a keywords file for the keyword spotter.
 *
 * The file format (from the engine's published documentation, not run against the
 * engine here): one keyword per line, the tokens separated by single spaces, then
 * optionally a boost that starts with a colon and a threshold that starts with a
 * hash sign, with no space between the marker and the number.
 *
 * Numbers are written as plain decimal text that does not depend on the phone's
 * language. The boost bound of 0 to 100 is a bound of ours: the engine's own range
 * is not verified. The threshold range of 0 to 1 is the documented one.
 *
 * Every refusal carries one fixed sentence and never repeats a token, a number or
 * a line of the input. This object reads and writes no file; [KeywordsFileWriter]
 * is only a seam and nothing here implements it.
 */
object KeywordsFileText {
    /** The most lines a keywords file may hold. */
    const val MAX_LINES = 16

    /** The most bytes (UTF-8) a keywords file may hold. */
    const val MAX_BYTES = 4096

    /** [render] was given no lines. */
    const val NO_LINES = "The keywords list is empty."

    /** [render] was given a line with no tokens. */
    const val NO_TOKENS = "A keyword line has no tokens."

    /** [render] was given a token that is empty, holds white space or starts with a marker. */
    const val BAD_TOKEN = "A keyword token is empty, holds white space or starts with a marker."

    /** A boost or threshold is missing, not a number or out of range. */
    const val BAD_NUMBER = "A boost or threshold is missing, not a number or out of range."

    /** The text has an empty line, or a line with no tokens. */
    const val EMPTY_LINE = "The keywords file has an empty line."

    /** A boost or threshold marker is followed by a space. */
    const val SPACE_AFTER_MARKER = "The keywords file has a space after a boost or threshold marker."

    /** A token is not in the model's tokens. */
    const val UNKNOWN_TOKEN = "The keywords file has a token that the model does not know."

    /** The text has more than [MAX_LINES] lines. */
    const val TOO_MANY_LINES = "The keywords file has too many lines."

    /** The text is longer than [MAX_BYTES] bytes. */
    const val TOO_LARGE = "The keywords file is too large."

    /** A line is not tokens, then at most one boost, then at most one threshold. */
    const val MALFORMED_LINE = "A keyword line is not in the expected shape."

    private val BOOST_MAX = BigDecimal(100)
    private val THRESHOLD_MAX = BigDecimal.ONE
    private val PLAIN_NUMBER = Regex("[0-9]+(\\.[0-9]+)?")
    private const val BOOST_MARK = ':'
    private const val THRESHOLD_MARK = '#'

    /**
     * Renders [lines] as the text of a keywords file: one line per keyword, every
     * line ending in a newline.
     *
     * @throws IllegalArgumentException with a fixed sentence when [lines] is empty,
     *   a line has no tokens, a token is empty, holds white space or starts with a
     *   colon or a hash sign, or a boost or threshold is NaN, infinite or out of range.
     */
    fun render(lines: List<KeywordLine>): String {
        require(lines.isNotEmpty()) { NO_LINES }
        val out = StringBuilder()
        for (line in lines) {
            require(line.tokens.isNotEmpty()) { NO_TOKENS }
            for (token in line.tokens) require(isPlainToken(token)) { BAD_TOKEN }
            out.append(line.tokens.joinToString(" "))
            val boost = line.boost
            if (boost != null) out.append(' ').append(BOOST_MARK).append(numberText(boost, BOOST_MAX))
            val threshold = line.threshold
            if (threshold != null) out.append(' ').append(THRESHOLD_MARK).append(numberText(threshold, THRESHOLD_MAX))
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * Checks [text] against the file rules and the model's [tokens].
     *
     * Returns null when the text is valid, otherwise one of the fixed sentences of
     * this object. The checks run in this order: size in bytes, number of lines,
     * then each line (empty line, space after a marker, shape, tokens, numbers).
     */
    fun refusal(text: String, tokens: Set<String>): String? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return TOO_LARGE
        val lines = text.removeSuffix("\n").split('\n')
        if (lines.size > MAX_LINES) return TOO_MANY_LINES
        for (line in lines) {
            val problem = lineRefusal(line, tokens)
            if (problem != null) return problem
        }
        return null
    }

    private fun isPlainToken(token: String): Boolean =
        token.isNotEmpty() &&
            token.none { it.isWhitespace() } &&
            token[0] != BOOST_MARK &&
            token[0] != THRESHOLD_MARK

    private fun isMarkerItem(item: String): Boolean =
        item.isNotEmpty() && (item[0] == BOOST_MARK || item[0] == THRESHOLD_MARK)

    /** The plain decimal text of [value], or a refusal when it is not finite or not in 0..[max]. */
    private fun numberText(value: Float, max: BigDecimal): String {
        require(!value.isNaN() && !value.isInfinite()) { BAD_NUMBER }
        val exact = BigDecimal(value.toString())
        require(exact.signum() >= 0 && exact <= max) { BAD_NUMBER }
        return exact.toPlainString()
    }

    private fun inRange(valueText: String, max: BigDecimal): Boolean =
        PLAIN_NUMBER.matches(valueText) && BigDecimal(valueText) <= max

    private fun lineRefusal(line: String, tokens: Set<String>): String? {
        if (line.isEmpty()) return EMPTY_LINE
        val items = line.split(' ')
        if (items.any { it == BOOST_MARK.toString() || it == THRESHOLD_MARK.toString() }) return SPACE_AFTER_MARKER
        if (items.any { it.isEmpty() }) return MALFORMED_LINE
        val firstMarker = items.indexOfFirst { isMarkerItem(it) }
        val tokenItems = if (firstMarker < 0) items else items.subList(0, firstMarker)
        val markerItems = if (firstMarker < 0) emptyList() else items.subList(firstMarker, items.size)
        if (tokenItems.isEmpty()) return EMPTY_LINE
        if (tokenItems.any { it !in tokens }) return UNKNOWN_TOKEN
        return markersRefusal(markerItems)
    }

    /** The marker items must be at most one boost, then at most one threshold. */
    private fun markersRefusal(items: List<String>): String? {
        var seenBoost = false
        var seenThreshold = false
        for (item in items) {
            val valueText = item.substring(1)
            when (item[0]) {
                BOOST_MARK -> {
                    if (seenBoost || seenThreshold) return MALFORMED_LINE
                    if (!inRange(valueText, BOOST_MAX)) return BAD_NUMBER
                    seenBoost = true
                }
                THRESHOLD_MARK -> {
                    if (seenThreshold) return MALFORMED_LINE
                    if (!inRange(valueText, THRESHOLD_MAX)) return BAD_NUMBER
                    seenThreshold = true
                }
                else -> return MALFORMED_LINE
            }
        }
        return null
    }
}
