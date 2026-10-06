package dev.breaker.dictation.format

import dev.breaker.dictation.core.port.Formatter
import java.util.Locale

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
 *    a numbered list;
 * 2. bullet list    — a tail run of two or more comma-joined items
 *    "first ..., second ..., third ..." (ascending from "first", the
 *    ordinal not followed by "is") becomes a bullet list;
 * 3. filler removal — whole filler tokens (um, uh, er, erm) are dropped;
 * 4. sentence casing — the first letter of the text and the letter after a
 *    sentence end are capitalised (a list marker is not a sentence end);
 * 5. final punctuation — trailing spaces are dropped and the text ends
 *    with a terminal mark.
 *
 * Ordinal markers match without regard to letter case (Locale.ROOT); item
 * text keeps its own casing.
 *
 * The lead (the text before the run) gains a colon and a blank line before
 * the list — unless the list starts the utterance, the lead is only filler
 * words, or the lead carries no letter or digit at all, in which case the
 * list stands alone with no lead line. A lead that ends in a letter or a
 * digit gains the colon; a comma the speaker put before the list is
 * replaced by the colon; a lead that ends in any other character (a
 * sentence end, ';', '-' or whatever the speaker put there) keeps that
 * character and gains nothing — never two marks in a row.
 *
 * Last-item rule: the last item of a run has its trailing whitespace
 * dropped first (whitespace is not content), then a single trailing ','
 * and/or a single trailing '.' — the end of the utterance — are dropped
 * from the item, which then gets its own period like the others. A final
 * '?' or '!' is NOT the utterance end: it stays in the item and leaves the
 * text plain, because a question or an exclamation must not become a list
 * (this formatter changes structure, never meaning). A comma, a sentence
 * end, or a line break or a tab anywhere else in an item kills the run —
 * an item must be one line of text (every line break counts: newline,
 * carriage return and the unicode line separators — and the tab, which is
 * horizontal whitespace, not a line break).
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
     * Empty when no rule fired — and empty when a run was found but killed
     * by an item check, because then the formatter left the text plain and
     * consumed nothing.
     */
    internal fun consumedOrdinalMarkers(rawText: String): List<String> {
        val run = findRun(rawText) ?: return emptyList()
        val items = run.items
        // A run only counts as consumed when a list rule can actually fire
        // on it: every item in one shape (all numbered, or all bullet). A
        // mixed run satisfies neither rule, so the text stays plain and
        // nothing is consumed.
        if (!items.all { NUMBERED_ITEM.find(it) != null } &&
            !items.all { BULLET_ITEM.find(it) != null }
        ) return emptyList()
        for (item in items.dropLast(1)) {
            if (itemKilled(item)) return emptyList()
        }
        if (processLastItem(items.last()) == null) return emptyList()
        return run.markers
    }

    private fun numberedListRule(text: String): String {
        val run = findRun(text) ?: return text
        if (run.items.any { NUMBERED_ITEM.find(it) == null }) return text
        for (item in run.items.dropLast(1)) {
            if (itemKilled(item)) return text
        }
        val last = processLastItem(run.items.last()) ?: return text
        val lines = (run.items.dropLast(1) + last).map { item ->
            val m = NUMBERED_ITEM.find(item)!!
            val num = toDigit(m.groupValues[1])!!
            val rest = item.substring(m.range.last + 1).trimEnd()
            "${num}. is${rest}."
        }
        return joinWithLead(run.lead, lines)
    }

    private fun bulletListRule(text: String): String {
        val run = findRun(text) ?: return text
        if (run.items.any { BULLET_ITEM.find(it) == null }) return text
        for (item in run.items.dropLast(1)) {
            if (itemKilled(item)) return text
        }
        val last = processLastItem(run.items.last()) ?: return text
        val lines = (run.items.dropLast(1) + last).map { item ->
            val m = BULLET_ITEM.find(item)!!
            val rest = item.substring(m.range.last + 1).trim()
            "- ${rest}."
        }
        return joinWithLead(run.lead, lines)
    }

    /**
     * A tail run of two or more comma-joined ordinal items ascending from
     * one/first, or null. Each item's span ends at the ', ' that starts the
     * next item; the last item runs to the end of the text. Whether an item
     * kills the run is decided by the list rules that use the run (they
     * know which item is the last): an item that carries a sentence end, or
     * a comma that is not the join to the next item, kills the run — a
     * list is the tail of the utterance, and anything after it is left
     * plain.
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
            val lead = text.substring(0, matches[i].start).trimEnd()
            val markers = matches.subList(i, j + 1).map { it.word }
            return Run(lead, items, markers)
        }
        return null
    }

    /**
     * The shared kill check for one item: any character of KILL_CHARS in it
     * kills the run — an item must be one line of text. The list rules use
     * it for every non-last item, the last-item cleanup uses it after
     * dropping its trailing marks, and consumedOrdinalMarkers uses it so a
     * killed run reports no markers.
     */
    private fun itemKilled(item: String): Boolean = item.any { it in KILL_CHARS }

    /**
     * The last item of a run, cleaned, or null when the run is killed:
     * trailing whitespace is not content and is dropped first; then one
     * trailing '.' (the end of the utterance) and/or a trailing ',' are
     * dropped; any remaining kill character (the shared check) kills the
     * run — including a line break, a tab, or any other character of
     * KILL_CHARS that the cleanup
     * leaves behind.
     */
    private fun processLastItem(item: String): String? {
        // The last item's trailing whitespace is dropped, and a trailing
        // U+0085 (next line) is trimmed too — Kotlin's default trimEnd uses
        // Char.isWhitespace, which does not include U+0085, so without this
        // a trailing NEL would leak into the output and kill the list while
        // the other line breaks at the same position are trimmed. This is a
        // TRIM only; U+0085 stays in KILL_CHARS, so a NEL INSIDE the item
        // still kills the run.
        var s = item.trimEnd { it.isWhitespace() || it == '\u0085' }
        if (s.endsWith(".")) s = s.dropLast(1)
        // The re-trim after the comma drop uses the same predicate as the
        // trim above, so a next line right before the final comma goes.
        if (s.endsWith(",")) s = s.dropLast(1).trimEnd { it.isWhitespace() || it == '\u0085' }
        if (itemKilled(s)) return null
        return s
    }

    /** The lead line + blank line + items, or the bare list when leadless. */
    private fun joinWithLead(lead: String, lines: List<String>): String {
        val head = leadLine(lead)
        return if (head.isEmpty()) lines.joinToString("\n")
        else head + "\n\n" + lines.joinToString("\n")
    }

    /**
     * The lead with its join mark; empty string when there is no lead. A
     * lead that is only filler words is no lead, and a lead with no letter
     * or digit in it (punctuation only) is no lead either: the list stands
     * alone. A lead that ends in a letter or a digit gains ':'; a trailing
     * ',' (already stripped) is replaced by that ':'; a lead that ends in
     * any other mark keeps it and gains nothing. The speaker's own marks
     * stay exactly as dictated; only the trailing run of separators and
     * marks left behind by a filler removal is collapsed to its last
     * non-comma mark, and the whitespace the speaker put before that first
     * mark stays as dictated.
     */
    private fun leadLine(lead: String): String {
        var l = lead.trimEnd().trimEnd(',').trimEnd()
        val beforeFillers = l
        l = removeFillers(l).trim()
        // If the fillers removed something, the lead may end in a run of
        // separators and marks they left behind. Collapse that run to its
        // last non-comma mark (or none, then the colon rule below decides),
        // and keep the whitespace the speaker put before the run's first
        // mark: the speaker's own marks and their spacing are untouched.
        if (l != beforeFillers) {
            var i = l.length - 1
            while (i >= 0 && !l[i].isLetterOrDigit()) i--
            if (i >= 0) {
                val run = l.substring(i + 1)
                val firstMark = run.indexOfFirst { !it.isWhitespace() }
                val space = if (firstMark >= 0) run.substring(0, firstMark) else ""
                val kept = run.lastOrNull { !it.isWhitespace() && it != ',' }
                l = if (kept != null) l.substring(0, i + 1) + space + kept else l.substring(0, i + 1)
            }
        }
        if (!l.any { it.isLetterOrDigit() }) return ""
        return if (l.last().isLetterOrDigit()) l + ":" else l
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
        if (s.isEmpty() || s.last() in ".?!") return s
        return s + "."
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

        // Every character that kills a run when it sits inside an item: the
        // comma and the sentence ends, plus every line break — \n, \r, the
        // unicode line separators (U+2028, U+2029, U+0085) and VT/FF — and
        // the tab (horizontal whitespace, not a line break). An item is
        // one line of text; a line break inside it means the "list" would
        // span lines with a markerless line in the middle.
        val KILL_CHARS = setOf(
            ',', '.', '!', '?',
            '\n', '\r', '\t',
            '\u2028', '\u2029', '\u0085', '\u000B', '\u000C'
        )

        // Marker words as explicit [lowerUpper] character classes: the match
        // is case-insensitive by construction and never consults the
        // default locale's case mapping.
        private fun ci(w: String): String =
            w.map { c -> "[${c.lowercaseChar()}${c.uppercaseChar()}]" }.joinToString("")
        private val CARD_CI = CARDINALS.joinToString("|") { ci(it) }
        private val ORDL_CI = ORDINALS.joinToString("|") { ci(it) }

        val ITEM = Regex(
            "\\b($CARD_CI) [Ii][Ss]\\b|" +
                "\\b($ORDL_CI)\\b(?!\\s+[Ii][Ss]\\b)"
        )
        val NUMBERED_ITEM = Regex("^($CARD_CI) [Ii][Ss]")
        val BULLET_ITEM = Regex("^($ORDL_CI)\\b(?!\\s+[Ii][Ss]\\b)")
        val TOKEN = Regex("\\S+")
        val SENTENCE_END = Regex("(?<=[^\\d])[.!?] ([a-z])")

        fun rootLower(word: String): String = word.lowercase(Locale.ROOT)

        fun ordinalValue(word: String): Int? {
            val lc = rootLower(word)
            val c = CARDINALS.indexOf(lc)
            if (c >= 0) return c + 1
            val o = ORDINALS.indexOf(lc)
            return if (o >= 0) o + 1 else null
        }

        fun toDigit(word: String): String? = TO_DIGIT[rootLower(word)]
    }
}
