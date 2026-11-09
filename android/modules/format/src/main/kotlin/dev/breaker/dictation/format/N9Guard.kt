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
     * lower-cased RAW text with this regex (straight ' and curly ’ apostrophes both match) —
     * tokenize() splits at the apostrophe ("don't" -> "don","t") so it cannot be used here.
     * One regex match = one protected contraction token.
     */
    private val CONTRACTION_REGEX = Regex(
        "\\b(doesn|didn|haven|hasn|hadn|wouldn|shouldn|couldn|mustn|needn|weren|wasn|aren|isn|won|can|don)['’]t\\b"
    )

    fun accepts(rawText: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.length > 4 * rawText.length + 64) return false
        val rawTokens = tokenize(rawText)
        val candTokens = tokenize(candidate)
        val rawSet = rawTokens.toSet()
        var nonDigitCandidateCount = 0
        for (t in candTokens) {
            val allDigits = t.all { it.isDigit() }
            if (allDigits && t.length <= 3) continue
            if (t !in rawSet) return false
            if (!allDigits) nonDigitCandidateCount++
        }
        val rawWords = rawTokens.count { t -> !t.all { it.isDigit() } }
        return nonDigitCandidateCount >= rawWords / 2
            && protectedTokensSatisfied(rawText, candidate)
            && digitTokensSatisfied(rawText, candidate)
    }

    /**
     * Protected-token rule: every protected token must appear in the candidate at a count >= its
     * count in the raw text, or the guard refuses (returns false, the caller then falls back to
     * the raw text exactly, as today).
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

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c)
            } else if (sb.isNotEmpty()) {
                tokens.add(sb.toString().lowercase(Locale.ROOT))
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString().lowercase(Locale.ROOT))
        return tokens
    }
}
