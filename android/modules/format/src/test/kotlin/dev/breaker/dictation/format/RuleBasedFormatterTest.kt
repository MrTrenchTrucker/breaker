package dev.breaker.dictation.format

import dev.breaker.dictation.core.port.Formatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RuleBasedFormatter], the deterministic rule-based local
 * formatter. The golden examples come from the module card; the N9
 * (non-destructive) tests run the PRODUCTION formatter and compare content
 * tokens, with number words normalised to digits and punctuation/layout
 * ignored.
 */
class RuleBasedFormatterTest {

    private val formatter = RuleBasedFormatter()

    @Test
    fun `card golden example formats exactly`() {
        val input = "I have 3 things I want you to go over one is file a, two is file b, three is file c"
        val out = "I have 3 things I want you to go over:\n\n1. is file a.\n2. is file b.\n3. is file c."
        assertEquals("the card golden must pass exactly", out, formatter.format(input))
    }

    @Test
    fun `numbered list keeps the lead and turns each N is item into a numbered line`() {
        val input = "please review one is the spec, two is the design"
        assertEquals(
            "Please review:\n\n1. is the spec.\n2. is the design.",
            formatter.format(input)
        )
    }

    @Test
    fun `bullet list drops the ordinal words and keeps the item words`() {
        val input = "I need three things, first coffee, second tea, third milk"
        assertEquals(
            "I need three things:\n\n- coffee.\n- tea.\n- milk.",
            formatter.format(input)
        )
    }

    @Test
    fun `a single one is item is not turned into a list`() {
        val out = formatter.format("I want one is nothing special")
        assertTrue(
            "a single 'one is' must stay plain text (no list marker)",
            !out.contains("\n") && !out.contains("1.")
        )
    }

    @Test
    fun `a single ordinal item is not turned into a list`() {
        val out = formatter.format("I need first coffee")
        assertTrue(
            "a single ordinal item must stay plain text and keep its word",
            !out.contains("\n") && out.contains("first")
        )
    }

    @Test
    fun `ordinals that do not ascend from one are left plain`() {
        val out = formatter.format("he said two is big, three is bigger")
        assertTrue(
            "a run that does not start at one must stay plain",
            !out.contains("\n\n")
        )
    }

    @Test
    fun `non-ascending ordinals are left plain and their words survive`() {
        val out = formatter.format("he said second, first")
        assertTrue(
            "'second, first' is not an ascending run",
            !out.contains("\n\n") && out.contains("second") && out.contains("first")
        )
    }

    @Test
    fun `filler words are removed`() {
        assertEquals(
            "I have a cat.",
            formatter.format("um, I have a cat")
        )
    }

    @Test
    fun `sentence starts are capitalised`() {
        assertEquals(
            "Go left. Then stop.",
            formatter.format("go left. then stop")
        )
    }

    @Test
    fun `text without terminal punctuation gains a period`() {
        assertEquals(
            "The dog sat down.",
            formatter.format("the dog sat down")
        )
    }

    @Test
    fun `a sentence end inside the run kills the list and leaves it plain`() {
        // findRun KDoc: an item that itself carries a sentence end kills the
        // run. The items stay comma-joined (so the run passes the gap and
        // ascending checks) and the sentence end sits INSIDE the second item
        // — the sentence-end check is the only thing that can stop this run
        val out = formatter.format("one is the spec, two is the design.")
        assertEquals(
            "a run whose second item carries a sentence end must stay plain",
            "One is the spec, two is the design.",
            out
        )
        val alt = formatter.format("first coffee, second tea. third milk")
        assertEquals(
            "same rule for a bullet-shape run broken inside an item",
            "First coffee, second tea. Third milk.",
            alt
        )
    }

    @Test
    fun `an ordinal followed by is does not start a bullet list`() {
        // BULLET_ITEM KDoc: the ordinal must NOT be followed by "is" — the
        // "<ordinal> is <rest>" shape belongs to the numbered rule (or to
        // plain text when it does not ascend from one), never to bullets
        val out = formatter.format("I want first is coffee, second is tea")
        assertEquals(
            "'first is / second is' must not become a bullet list",
            "I want first is coffee, second is tea.",
            out
        )
    }

    // ------------------------------------------------------------------
    // N9: the formatted output must never contain content that was absent
    // from the raw text. Two-sided content-token check (number words
    // normalised to digits, punctuation and list layout ignored), run
    // against the PRODUCTION formatter. The only tokens allowed to
    // disappear: (1) the formatter's filler words, (2) the ordinal words
    // the list rule actually consumed, as reported by the production
    // trigger — never a fixed word list.
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------

    private data class N9Report(val invented: Set<String>, val droppedUnexplained: Set<String>)

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
        val countsA = a.groupingBy { it }.eachCount()
        val countsB = b.groupingBy { it }.eachCount()
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
}
