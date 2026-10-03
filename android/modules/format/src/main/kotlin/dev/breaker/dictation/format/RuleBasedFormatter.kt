package dev.breaker.dictation.format

import dev.breaker.dictation.core.port.Formatter

/**
 * The deterministic, rule-based local formatter: the zero-cost, always-
 * available offline fallback for [Formatter].
 *
 * It reshapes a raw transcript without changing its meaning (non-
 * destructive): a spoken enumeration becomes a list, filler words are
 * removed, and sentence casing and terminal punctuation are applied. The
 * output never contains content that was not in the raw text.
 *
 * Rules, in the fixed order they run:
 * 1. numbered list  — a tail run of two or more comma-joined items
 *    "one is ..., two is ..., three is ..." (ascending from "one") becomes
 *    a numbered list; the lead gains a colon and a blank line;
 * 2. bullet list    — a tail run of two or more comma-joined items
 *    "first ..., second ..., third ..." (ascending from "first", the
 *    ordinal not followed by "is") becomes a bullet list;
 * 3. filler removal — whole filler tokens (um, uh, er, erm) are dropped;
 * 4. sentence casing — the first letter of the text and the letter after a
 *    sentence end are capitalised (a list marker is not a sentence end);
 * 5. final punctuation — the text ends with a terminal mark.
 *
 * Anything that does not match a rule exactly is left as plain text: the
 * safe, non-destructive default.
 */
class RuleBasedFormatter : Formatter {

    override fun format(rawText: String): String {
        var text = numberedListRule(rawText)
        text = bulletListRule(text)
        text = removeFillers(text)
        text = sentenceCase(text)
        return finalPunctuation(text)
    }

    /**
     * The ordinal words a list rule actually consumed as item markers for
     * [rawText] (the same trigger the formatter runs, so a check that asks
     * "what may legitimately disappear" never uses a fixed word list).
     * Empty when no rule fired.
     */
    fun consumedOrdinalMarkers(rawText: String): List<String> {
        val run = findRun(rawText) ?: return emptyList()
        val items = run.items
        if (items.all { NUMBERED_ITEM.find(it) != null }) return run.markers
        if (items.all { BULLET_ITEM.find(it) != null }) return run.markers
        return emptyList()
    }

    private fun numberedListRule(text: String): String {
        val run = findRun(text) ?: return text
        if (run.items.any { NUMBERED_ITEM.find(it) == null }) return text
        val lines = run.items.map { item ->
            val m = NUMBERED_ITEM.find(item)!!
            val num = toDigit(m.groupValues[1])!!
            val rest = item.substring(m.range.last + 1).trimEnd()
            "${num}. is${rest}."
        }
        return leadColon(run.lead) + "\n\n" + lines.joinToString("\n")
    }

    private fun bulletListRule(text: String): String {
        val run = findRun(text) ?: return text
        if (run.items.any { BULLET_ITEM.find(it) == null }) return text
        val lines = run.items.map { item ->
            val m = BULLET_ITEM.find(item)!!
            val rest = item.substring(m.range.last + 1).trim()
            "- ${rest}."
        }
        return leadColon(run.lead) + "\n\n" + lines.joinToString("\n")
    }

    /**
     * A tail run of two or more comma-joined ordinal items ascending from
     * one/first, or null. Each item's span ends at the ', ' that starts the
     * next item; the last item runs to the end of the text. An item that
     * itself carries a sentence end (or a comma other than the item join)
     * kills the run — a list is the tail of the utterance, and anything
     * after it is left plain.
     */
    private fun findRun(text: String): Run? {
        val matches = ITEM.findAll(text).map { m ->
            val word = m.groupValues[1].ifEmpty { m.groupValues[2] }
            OrdinalMatch(m.range.first, word)
        }.toList()
        for (i in matches.indices) {
            val firstValue = ordinalValue(matches[i].word) ?: continue
            if (firstValue != 1) continue // a run must start at one/first
            var j = i
            while (j + 1 < matches.size) {
                val next = matches[j + 1]
                if (next.start < 2 || text.substring(next.start - 2, next.start) != ", ") break
                val v = ordinalValue(next.word) ?: break
                if (v != (ordinalValue(matches[j].word) ?: 0) + 1) break
                j++
            }
            if (j == i) continue // need at least two items
            val items = ArrayList<String>()
            for (k in i..j) {
                val end = if (k < j) matches[k + 1].start - 2 else text.length
                items.add(text.substring(matches[k].start, end))
            }
            if (items.any { it.any { c -> c == '.' || c == '!' || c == '?' } }) continue
            val lead = text.substring(0, matches[i].start).trimEnd()
            val markers = matches.subList(i, j + 1).map { it.word }
            return Run(lead, items, markers)
        }
        return null
    }

    private fun removeFillers(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in TOKEN.findAll(text)) {
            val bare = m.value.trim(' ', '.', ',', ';', ':', '!', '?', '(', ')')
            if (bare.isNotEmpty() && FILLERS.contains(bare.lowercase())) {
                var start = m.range.first
                var end = m.range.last + 1
                if (end < text.length && text[end] == ' ') {
                    end++ // swallow the following separator
                } else if (start > last && text[start - 1] == ' ') {
                    start-- // ... else the preceding one
                }
                out.append(text, last, start)
                last = end
            }
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private fun sentenceCase(text: String): String {
        if (text.isEmpty() || !text[0].isLetter()) return text
        var out = text[0].uppercase() + text.substring(1)
        out = SENTENCE_END.replace(out) { m ->
            m.value.substring(0, 2) + m.value[2].uppercase() + m.value.substring(3)
        }
        return out
    }

    private fun finalPunctuation(text: String): String {
        val s = text.trimEnd()
        if (s.isEmpty() || s.last() in ".?!") return text
        return s + "."
    }

    private fun leadColon(lead: String): String {
        // the join between the lead and the list is a colon; a comma the
        // speaker put before the list is replaced by it
        return lead.trimEnd().trimEnd(',').trimEnd() + ":"
    }

    private data class Run(val lead: String, val items: List<String>, val markers: List<String>)
    private data class OrdinalMatch(val start: Int, val word: String)

    companion object {
        val CARDINALS = listOf("one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten")
        val ORDINALS = listOf("first", "second", "third", "fourth", "fifth",
            "sixth", "seventh", "eighth", "ninth", "tenth")
        val TO_DIGIT: Map<String, String> = CARDINALS.mapIndexed { i, w -> w to (i + 1).toString() }.toMap() +
          ORDINALS.mapIndexed { i, w -> w to (i + 1).toString() }.toMap()

        val FILLERS = setOf("um", "uh", "er", "erm")

        val ITEM = Regex(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten) is\\b|" +
                "\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\b(?!\\s+is\\b)"
        )
        val NUMBERED_ITEM = Regex(
            "^(one|two|three|four|five|six|seven|eight|nine|ten) is"
        )
        val BULLET_ITEM = Regex(
            "^(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)(?!\\s+is\\b)"
        )
        val TOKEN = Regex("\\S+")
        val SENTENCE_END = Regex("(?<=[^\\d])[.!?] ([a-z])")

        fun ordinalValue(word: String): Int? {
            val c = CARDINALS.indexOf(word)
            if (c >= 0) return c + 1
            val o = ORDINALS.indexOf(word)
            return if (o >= 0) o + 1 else null
        }

        fun toDigit(word: String): String? = TO_DIGIT[word]
    }
}
