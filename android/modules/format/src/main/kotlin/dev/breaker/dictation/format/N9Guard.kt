package dev.breaker.dictation.format

import java.util.Locale

internal object N9Guard {
    /**
     * Protected tokens: negation words plus every n't contraction. One constant, in one place.
     * Every protected token must survive a rewrite, or the guard refuses.
     */
    private val PROTECTED_TOKENS: Set<String> = setOf(
        "not", "no", "never", "cannot", "without", "none", "nothing", "nobody", "neither", "nor",
        "don't", "doesn't", "didn't", "won't", "can't", "isn't", "aren't", "wasn't", "weren't",
        "haven't", "hasn't", "hadn't", "wouldn't", "shouldn't", "couldn't", "mustn't", "needn't"
    )

    /**
     * Contraction stems for the n't forms above. The n't forms are counted straight from the
     * lower-cased RAW text with this regex, after every apostrophe look-alike was read as the
     * plain apostrophe (see [APOSTROPHES]). tokenize() splits at the apostrophe ("don't" ->
     * "don","t") so it cannot be used here. One regex match = one protected contraction token.
     */
    private val CONTRACTION_REGEX = Regex(
        "\\b(doesn|didn|haven|hasn|hadn|wouldn|shouldn|couldn|mustn|needn|weren|wasn|aren|isn|won|can|don)'t\\b"
    )

    /**
     * Characters that stand for an apostrophe in typed or dictated text, besides the plain one:
     * the curly ones (U+2018, U+2019, U+201B), the prime (U+2032), the modifier letter apostrophe
     * (U+02BC), the acute accent (U+00B4), the grave accent (U+0060) and the fullwidth apostrophe
     * (U+FF07). Each is read as the plain apostrophe before anything is counted, so a negation
     * written with any of them is protected like "don't".
     */
    private const val APOSTROPHES: String = "‘’‛′ʼ´`＇"

    fun accepts(rawText: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.length > 4 * rawText.length + 64) return false
        if (hasForbiddenCharacter(candidate)) return false
        val raw = readApostrophes(rawText)
        val cand = readApostrophes(candidate)
        val rawTokens = tokenize(raw)
        val candTokens = tokenize(cand)
        val rawSet = rawTokens.toSet()
        var nonDigitCandidateCount = 0
        for (t in candTokens) {
            val allDigits = t.all { it.isDigit() }
            if (allDigits && t.length <= 3) continue
            if (t !in rawSet) return false
            if (!allDigits) nonDigitCandidateCount++
        }
        val rawWords = rawTokens.count { t -> !t.all { it.isDigit() } }
        return nonDigitCandidateCount >= requiredWords(rawWords)
            && protectedTokensSatisfied(raw, cand)
            && digitTokensSatisfied(raw, cand)
    }

    /**
     * How many non-number words a candidate must keep: at least half of the raw words, and at
     * least one whenever the raw has any, so a reply of punctuation alone never passes for a
     * short transcript (half of one word is zero by integer division).
     */
    private fun requiredWords(rawWords: Int): Int = if (rawWords == 0) 0 else maxOf(1, rawWords / 2)

    /**
     * True when the candidate holds a character that is no text: a control character other than
     * tab, line feed and carriage return, or a surrogate half with no partner. The word count
     * skips both, so without this check a reply could carry them past the guard.
     */
    private fun hasForbiddenCharacter(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0xD800..0xDFFF) return true
            if (Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code && cp != '\r'.code) return true
        }
        return false
    }

    /** [text] with every apostrophe look-alike of [APOSTROPHES] replaced by the plain apostrophe. */
    private fun readApostrophes(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(if (APOSTROPHES.indexOf(c) >= 0) '\'' else c)
        return out.toString()
    }

    /**
     * Protected-token rule: every protected token must appear in the candidate at a count >= its
     * count in the raw text, or the guard refuses (returns false, the caller then falls back to
     * the raw text exactly, as today). Only the count is checked, not the place: a word that
     * moves inside the reply is not caught.
     */
    private fun protectedTokensSatisfied(rawText: String, candidate: String): Boolean {
        val rawCounts = protectedCounts(rawText)
        if (rawCounts.isEmpty()) return true
        val candCounts = protectedCounts(candidate)
        for ((token, required) in rawCounts) {
            if ((candCounts[token] ?: 0) < required) return false
        }
        return true
    }

    /**
     * Whole-word protected-token counts of lower-cased text. Base negation words come from
     * tokenize(), whose non-letter/digit boundaries mean "not" inside "another" does not count.
     * n't contractions come from CONTRACTION_REGEX over the raw text.
     */
    private fun protectedCounts(text: String): Map<String, Int> {
        val low = text.lowercase(Locale.ROOT)
        val counts = HashMap<String, Int>()
        for (t in tokenize(low)) {
            if (t in PROTECTED_TOKENS) counts[t] = (counts[t] ?: 0) + 1
        }
        for (m in CONTRACTION_REGEX.findAll(low)) {
            val token = "${m.groupValues[1]}'t"
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    /**
     * Digit rule: every raw token that contains at least one digit (any length, including the
     * <=3 digit tokens the candidate side exempts) must appear in the candidate at a count >=
     * its raw count. It looks only at raw tokens, so nothing is double counted.
     */
    private fun digitTokensSatisfied(rawText: String, candidate: String): Boolean {
        val rawDigitTokens = tokenize(rawText).filter { t -> t.any { it.isDigit() } }
        if (rawDigitTokens.isEmpty()) return true
        val candTokens = tokenize(candidate)
        val rawCounts = rawDigitTokens.groupingBy { it }.eachCount()
        for ((token, required) in rawCounts) {
            if (candTokens.count { it == token } < required) return false
        }
        return true
    }

    // Words are runs of letters and digits, read by code point so a letter outside the Basic
    // Multilingual Plane (a surrogate pair) counts as a letter, not as two boundaries.
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                tokens.add(sb.toString().lowercase(Locale.ROOT))
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString().lowercase(Locale.ROOT))
        return tokens
    }
}
