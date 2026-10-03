package dev.breaker.dictation.format

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The N9 (non-destructive) suite for [RuleBasedFormatter]: the formatted
 * output must not contain content absent from the raw text — number words
 * normalised to digits, punctuation and list layout ignored. The check runs
 * the PRODUCTION formatter; the only tokens allowed to disappear are the
 * filler words and the ordinal words the list rule actually consumed, as
 * reported by the production trigger — never a fixed word list.
 */
class RuleBasedFormatterN9Test {

    private val formatter = RuleBasedFormatter()

    private data class N9Report(val invented: Set<String>, val droppedUnexplained: Set<String>)

    private fun List<String>.tokenCounts(): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (w in this) m[w] = (m[w] ?: 0) + 1
        return m
    }

    private fun assertN9Clean(raw: String, out: String) {
        val report = n9Report(raw, out)
        assertTrue(
            "N9 violated: invented=${report.invented} unexplained-drops=${report.droppedUnexplained}",
            report.invented.isEmpty() && report.droppedUnexplained.isEmpty()
        )
    }

    private fun n9Report(raw: String, out: String): N9Report {
        val a = contentTokens(raw)
        val b = contentTokens(out)
        val countsA = a.tokenCounts()
        val countsB = b.tokenCounts()
        // counts: Map<String, Int> — getValue on a present key is non-null;
        // the other map's get can be absent, hence the null-default
        val invented = countsB.keys.filter { countsB.getValue(it) > (countsA[it] ?: 0) }
        val dropped = countsA.keys.filter { countsA.getValue(it) > (countsB[it] ?: 0) }
        // the only allowed drops: the formatter's filler words, and the
        // ordinal words the list rule actually consumed (from the production
        // trigger, never a fixed word list)
        val allowed = HashSet(RuleBasedFormatter.FILLERS)
        val consumed = formatter.consumedOrdinalMarkers(raw)
        allowed.addAll(consumed)
        consumed.forEach { allowed.add(RuleBasedFormatter.TO_DIGIT[it] ?: it) }
        return N9Report(invented.toSet(), dropped.filter { it !in allowed }.toSet())
    }

    private fun contentTokens(text: String): List<String> {
        val toks = mutableListOf<String>()
        for (w in text.split(Regex("\\s+"))) {
            var t = w.trim(' ', '.', ',', ';', ':', '!', '?', '(', ')')
            if (t.isEmpty()) continue
            if (t.all { it.isDigit() }) continue // list-number markers: layout
            t = t.lowercase()
            if (!t.any { it.isLetterOrDigit() }) continue
            t = RuleBasedFormatter.TO_DIGIT[t] ?: t
            toks.add(t)
        }
        return toks
    }

    @Test
    fun `N9 numbered golden invents nothing and drops nothing unexplained`() {
        val input = "I have 3 things I want you to go over one is file a, two is file b, three is file c"
        assertN9Clean(input, formatter.format(input))
    }

    @Test
    fun `N9 bullet golden invents nothing and drops nothing unexplained`() {
        val input = "I need three things, first coffee, second tea, third milk"
        assertN9Clean(input, formatter.format(input))
    }

    @Test
    fun `N9 filler removal drops only filler words`() {
        val input = "um, I have a cat"
        assertN9Clean(input, formatter.format(input))
    }

    @Test
    fun `N9 a plain sentence invents nothing and drops nothing`() {
        val input = "the dog sat down"
        assertN9Clean(input, formatter.format(input))
    }

    @Test
    fun `N9 a single one is item keeps its ordinal word`() {
        val input = "I want one is nothing special"
        assertN9Clean(input, formatter.format(input))
    }

    @Test
    fun `N9 an added word is caught`() {
        // a formatter that adds a word invents content: side (a) must fail
        val input = "the dog sat down"
        val out = RuleBasedFormatter().format(input) + " extra_word"
        val report = n9Report(input, out)
        assertTrue(
            "an added word must be flagged as invented content: $report",
            report.invented.isNotEmpty()
        )
    }

    @Test
    fun `N9 a dropped item word is caught on the drop side`() {
        // a bullet rule that drops an item word along with its ordinal must
        // be flagged on side (b)
        val input = "I need three things, first coffee, second tea, third milk"
        val out = "I need three things:\n\n- tea.\n- milk."
        val report = n9Report(input, out)
        assertTrue(
            "a dropped item word must be flagged as unexplained: $report",
            report.droppedUnexplained.isNotEmpty() &&
                report.droppedUnexplained.contains("coffee")
        )
    }

    @Test
    fun `N9 stays clean when only consumed ordinal markers disappear`() {
        // a bullet rule that drops the ordinal words but emits no bullet:
        // N9 stays green (only the consumed ordinals disappeared), so the
        // golden equality test -- not N9 -- is what catches that change
        val input = "I need three things, first coffee, second tea, third milk"
        val out = "I need three things:\n\ncoffee.\n\ntea.\n\nmilk."
        assertN9Clean(input, out)
        assertTrue(
            "the bullet golden equality must be the check that goes RED here",
            out != formatter.format(input)
        )
    }
}
