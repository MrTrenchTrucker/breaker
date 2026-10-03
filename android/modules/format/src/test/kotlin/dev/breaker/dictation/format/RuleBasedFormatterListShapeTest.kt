package dev.breaker.dictation.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * List-structure behaviour of [RuleBasedFormatter]: lead handling (leadless
 * lists, a lead's own terminal mark, a comma inside an item), the final
 * period as the end of the utterance, and trailing whitespace around the
 * last item.
 */
class RuleBasedFormatterListShapeTest {

    private val formatter = RuleBasedFormatter()

    @Test
    fun `a numbered list at the start of the utterance has no lead line`() {
        assertEquals(
            "1. is milk.\n2. is eggs.",
            formatter.format("one is milk, two is eggs")
        )
    }

    @Test
    fun `a bullet list at the start of the utterance has no lead line`() {
        assertEquals(
            "- coffee.\n- eggs.",
            formatter.format("first coffee, second eggs")
        )
    }

    @Test
    fun `a lead ending in a colon keeps it and gains no extra colon`() {
        assertEquals(
            "He said:\n\n1. is a.\n2. is b.",
            formatter.format("He said: one is a, two is b")
        )
    }

    @Test
    fun `a lead ending in a period keeps it and gains no colon`() {
        assertEquals(
            "He said.\n\n1. is a.\n2. is b.",
            formatter.format("He said. one is a, two is b")
        )
    }

    @Test
    fun `a comma inside an earlier item keeps the text plain`() {
        assertEquals(
            "One is apples, bananas, and oranges, two is grapes, cherries.",
            formatter.format("one is apples, bananas, and oranges, two is grapes, cherries")
        )
    }

    @Test
    fun `a comma inside the last item plus a trailing comma keeps the text plain`() {
        assertEquals(
            "One is a, two is b, c,.",
            formatter.format("one is a, two is b, c,")
        )
    }

    @Test
    fun `a comma inside a non-last item keeps the numbered text plain`() {
        assertEquals(
            "One is a, b, two is c.",
            formatter.format("one is a, b, two is c")
        )
        assertEquals(
            "First a, b, second c.",
            formatter.format("first a, b, second c")
        )
    }

    @Test
    fun `a comma inside the last item keeps the text plain`() {
        assertEquals(
            "He is tall, one is big, two is c, d.",
            formatter.format("He is tall, one is big, two is c, d")
        )
    }

    @Test
    fun `a final period is the end of the utterance and the list still forms`() {
        assertEquals(
            "I need:\n\n1. is milk.\n2. is eggs.",
            formatter.format("I need one is milk, two is eggs.")
        )
    }

    @Test
    fun `a final question mark keeps the text plain`() {
        assertEquals(
            "I need one is milk, two is eggs?",
            formatter.format("I need one is milk, two is eggs?")
        )
    }

    @Test
    fun `a final exclamation mark keeps the text plain`() {
        assertEquals(
            "I need one is milk, two is eggs!",
            formatter.format("I need one is milk, two is eggs!")
        )
    }

    @Test
    fun `two final periods keep the text plain`() {
        assertEquals(
            "I need one is milk, two is eggs..",
            formatter.format("I need one is milk, two is eggs..")
        )
    }

    @Test
    fun `a trailing comma right before the final period is still dropped`() {
        // "b,." — the last item ends in a comma that sits right before the
        // end-of-utterance period. The period must be read as the utterance
        // end (dropped) and the comma then dropped as a trailing comma;
        // reversing that order would leave a residual comma and kill the run.
        assertEquals(
            "1. is a.\n2. is b.",
            formatter.format("one is a, two is b,.")
        )
    }

    @Test
    fun `a trailing space after the final period is ignored`() {
        assertEquals(
            "I need:\n\n1. is milk.\n2. is eggs.",
            formatter.format("I need one is milk, two is eggs. ")
        )
        assertEquals(
            "1. is a.\n2. is b.",
            formatter.format("one is a, two is b.\n")
        )
    }

    @Test
    fun `a trailing comma with a following space is dropped`() {
        assertEquals(
            "1. is a.\n2. is b.",
            formatter.format("one is a, two is b, ")
        )
    }

    @Test
    fun `items that are not comma-joined stay plain`() {
        assertEquals(
            "One is a two is b.",
            formatter.format("one is a two is b")
        )
    }
}
