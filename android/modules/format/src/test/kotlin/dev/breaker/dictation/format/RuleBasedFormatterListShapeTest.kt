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
    fun `a comma inside a non-last item keeps the text plain in both list shapes`() {
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

    @Test
    fun `a lead ending in a semicolon keeps its mark and gains no colon`() {
        // never two marks in a row: a lead that ends in a character that is
        // not a letter, a digit or a comma keeps it and gains nothing
        assertEquals(
            "He said;\n\n1. is a.\n2. is b.",
            formatter.format("He said; one is a, two is b")
        )
    }

    @Test
    fun `a lead ending in a hyphen keeps its mark and gains no colon`() {
        assertEquals(
            "He said -\n\n1. is a.\n2. is b.",
            formatter.format("He said - one is a, two is b")
        )
    }

    @Test
    fun `a lead that is only punctuation is no lead at all`() {
        // no letter or digit in the lead -> the list stands alone, exactly
        // like an empty or filler-only lead: never a bare ":" line
        assertEquals(
            "1. is a.\n2. is b.",
            formatter.format(": one is a, two is b")
        )
    }

    @Test
    fun `a semicolon lead keeps its mark in a bullet list too`() {
        assertEquals(
            "He said;\n\n- a.\n- b.",
            formatter.format("He said; first a, second b")
        )
    }

    @Test
    fun `a newline inside an item kills the run and leaves it plain`() {
        // an item is one line of text: a newline inside it (here the first
        // item) kills the run — the text stays plain
        assertEquals(
            "One is a\nb, two is c.",
            formatter.format("one is a\nb, two is c")
        )
    }

    @Test
    fun `a tab inside an item kills the run and leaves it plain`() {
        assertEquals(
            "First a\tb, second c.",
            formatter.format("first a\tb, second c")
        )
    }

    @Test
    fun `a bare carriage return inside an item kills the run and leaves it plain`() {
        assertEquals(
            "One is a\rb, two is c.",
            formatter.format("one is a\rb, two is c")
        )
    }

    @Test
    fun `a newline inside the last item kills the run too`() {
        // the last item goes through its own cleanup path (trailing
        // whitespace dropped, trailing comma/period dropped) — a newline it
        // leaves behind still kills the run
        assertEquals(
            "One is a, two is b\nc.",
            formatter.format("one is a, two is b\nc")
        )
    }

    @Test
    fun `a unicode line separator inside an item kills the run and leaves it plain`() {
        // U+2028 is a line break like any other: an item must be one line,
        // and a list line containing a line separator would span lines with
        // a markerless line in the middle
        assertEquals(
            "One is a\u2028b, two is c.",
            formatter.format("one is a\u2028b, two is c")
        )
    }

    @Test
    fun `every line break character in the kill set keeps a non-last item plain one at a time`() {
        // every line break the production kill set names, plus the tab, is
        // written as a LITERAL here: a test that read the production set
        // could never go red when a character was dropped from it. Each
        // character inside a non-last item must kill the run and leave the
        // text plain; the failure message names the character.
        val lineBreaks = listOf(
            "newline" to "\n",
            "carriage return" to "\r",
            "tab" to "\t",
            "U+2028 line separator" to "\u2028",
            "U+2029 paragraph separator" to "\u2029",
            "U+0085 next line" to "\u0085",
            "U+000B vertical tab" to "\u000B",
            "U+000C form feed" to "\u000C"
        )
        for ((name, ch) in lineBreaks) {
            val out = formatter.format("one is a${ch}b, two is c")
            assertEquals(
                "a '$name' inside a non-last item must kill the run and leave the text plain",
                "One is a${ch}b, two is c.",
                out
            )
        }
    }
}
