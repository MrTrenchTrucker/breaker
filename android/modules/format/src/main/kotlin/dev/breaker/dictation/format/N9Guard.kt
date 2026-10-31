package dev.breaker.dictation.format

import java.util.Locale

internal object N9Guard {
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
