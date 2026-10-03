package dev.breaker.dictation.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RuleBasedFormatter], the deterministic rule-based local
 * formatter: the module card's golden examples, list shape, boundary
 * inputs, filler removal, sentence casing and final punctuation. The
 * other focused classes cover the N9 suite, list-structure edges and
 * word-level behaviour (fillers, terminal marks, ordinal casing,
 * default-locale independence).
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
    fun `a sentence end inside a NON-LAST item kills the list and leaves it plain`() {
        // the kill check runs on every non-last item: a sentence end inside
        // a non-last item (with the comma join intact) kills the run. A period
        // on the LAST item is the end of the utterance instead — see the
        // final-period test below.
        val out = formatter.format("one is a. b, two is c")
        assertEquals(
            "a non-last item carrying a sentence end must stay plain",
            "One is a. B, two is c.",
            out
        )
        val alt = formatter.format("first coffee, second tea. third milk")
        assertEquals(
            "same kill on the LAST item: its sentence end is not the utterance end",
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
}
