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

    @Test
    fun `a comma before a filler before a numbered list is replaced by the colon`() {
        // The filler after the comma is removed, and the comma that was
        // always there is exposed at the end of the lead — no mark is left
        // in the trailing run, so the letter-or-digit rule gives the lead
        // its ':'
        assertEquals(
            "a comma before a filler must be replaced by the ':' (numbered)",
            "He needs this:\n\n1. is a.\n2. is b.",
            formatter.format("He needs this, um one is a, two is b")
        )
    }

    @Test
    fun `a comma before a filler before a bullet list is replaced by the colon`() {
        // Bullet shape: same rule, same fix
        assertEquals(
            "a comma before a filler must be replaced by the ':' (bullet)",
            "He needs this:\n\n- a.\n- b.",
            formatter.format("He needs this, uh first a, second b")
        )
    }

    @Test
    fun `a line break or a tab in the trailing position is trimmed and the list still forms`() {
        // The last item's cleanup trims trailing whitespace, and a
        // trailing U+0085 (next line) is trimmed too — Kotlin's default
        // trimEnd uses Char.isWhitespace, which excludes U+0085, so without
        // the added check a trailing NEL leaks into the output and kills
        // the list while the other seven line breaks at the same position
        // are trimmed. Written as literals: each at the very end of the
        // utterance must be dropped and the list must still form (RED on
        // 1596ca6d for the U+0085 case only, per JDK 21 isWhitespace).
        val trailing = listOf(
            "newline" to "\n",
            "carriage return" to "\r",
            "tab" to "\t",
            "U+2028 line separator" to "\u2028",
            "U+2029 paragraph separator" to "\u2029",
            "U+0085 next line" to "\u0085",
            "U+000B vertical tab" to "\u000B",
            "U+000C form feed" to "\u000C"
        )
        for ((name, ch) in trailing) {
            assertEquals(
                "a trailing '$name' must be trimmed and the list must still form",
                "1. is a.\n2. is b.",
                formatter.format("one is a, two is b" + ch)
            )
        }
    }

    @Test
    fun `a lead ending in a digit gains the colon`() {
        // A lead ending in a letter OR a digit gains ':' — a lead
        // ending in '12' must gain the ':' (isLetterOrDigit, not isLetter)
        assertEquals(
            "a digit-ending lead must gain the ':'",
            "Room 12:\n\n1. is a.\n2. is b.",
            formatter.format("Room 12 one is a, two is b")
        )
    }
    @Test
    fun `a space before a comma in a filler lead is still replaced by the colon`() {
        // The comma sits one space off from the filler: the filler removal
        // exposes it and the space after it, and both must go before the
        // colon rule runs - a trailing space left here would skip the colon.
        assertEquals(
            "a space before the comma must not survive the lead cleanup",
            "He needs this:\n\n1. is a.\n2. is b.",
            formatter.format("He needs this , um , one is a, two is b")
        )
    }

    @Test
    fun `a next line before a final comma is trimmed like a newline`() {
        // The re-trim after dropping the final comma must use the same
        // predicate as the first trim: a next line right before the comma
        // is dropped, just like a newline, and the list forms. The period
        // branch is a different path and stays plain for both line breaks.
        assertEquals(
            "a next line before the final comma must not kill the list",
            "1. is a.\n2. is b.",
            formatter.format("one is a, two is b\u0085,")
        )
        assertEquals(
            "a newline before the final comma behaves the same",
            "1. is a.\n2. is b.",
            formatter.format("one is a, two is b\n,")
        )
        assertEquals(
            "a next line before the final period stays plain (period branch)",
            "One is a, two is b\u0085.",
            formatter.format("one is a, two is b\u0085.")
        )
        assertEquals(
            "a newline before the final period stays plain (period branch)",
            "One is a, two is b\n.",
            formatter.format("one is a, two is b\n.")
        )
    }

    @Test
    fun `the speaker's space before a mark left by filler removal stays`() {
        // The collapse keeps the whitespace the speaker put before the run's
        // first mark — for a dash a glued hyphen would read as a hyphenated
        // word, so the space is part of what was dictated
        assertEquals(
            "the space before the speaker's dash must survive the cleanup",
            "He needs this -\n\n1. is a.\n2. is b.",
            formatter.format("He needs this - um - one is a, two is b")
        )
    }

    @Test
    fun `the speaker's space before a semicolon left by filler removal stays`() {
        // Same rule as the dash case, for a semicolon: the space before the
        // kept mark is the speaker's, not the filler's
        assertEquals(
            "the space before the speaker's semicolon must survive the cleanup",
            "He needs this ;\n\n1. is a.\n2. is b.",
            formatter.format("He needs this ; um , one is a, two is b")
        )
    }

    @Test
    fun `two different marks left by filler removal keep the last one`() {
        // The run keeps its last non-comma mark, not its first: the dash
        // comes first in the dictation, the semicolon is the one kept
        assertEquals(
            "the last of two different marks must be the one kept",
            "He needs this ;\n\n1. is a.\n2. is b.",
            formatter.format("He needs this - um ; one is a, two is b")
        )
    }

    @Test
    fun `the speaker's own two marks with no filler stay untouched`() {
        // No filler removed anything, so nothing is collapsed: the comma
        // and the semicolon both stand exactly as dictated
        assertEquals(
            "a lead whose fillers removed nothing keeps both its marks",
            "He said, ;\n\n1. is a.\n2. is b.",
            formatter.format("He said, ; one is a, two is b")
        )
    }

    @Test
    fun `a trailing mark run left by filler removal keeps only its last mark`() {
        // After the fillers go, the lead can end in a run of separators
        // and marks: drop the commas, keep only the last mark (a lead
        // never ends in two marks), and if no mark is left the letter-or-
        // digit colon rule applies. A comma in the middle of the lead,
        // outside that trailing run, survives.
        assertEquals(
            "a semicolon left behind by the filler replaces the whole trailing run",
            "He needs this;\n\n1. is a.\n2. is b.",
            formatter.format("He needs this, um ; one is a, two is b")
        )
        assertEquals(
            "commas only after the filler leave a clean colon",
            "He needs this:\n\n1. is a.\n2. is b.",
            formatter.format("He needs this,, ,, um one is a, two is b")
        )
        assertEquals(
            "a comma in the middle of the lead survives the cleanup",
            "He needs this, then:\n\n1. is a.\n2. is b.",
            formatter.format("He needs this, then one is a, two is b")
        )
    }
}
