package dev.breaker.dictation.format

import org.junit.Assert.assertEquals
import java.util.Locale
import org.junit.Test

/**
 * Word-level behaviour of [RuleBasedFormatter]: filler words, terminal
 * marks, a filler-only lead, case-insensitive ordinal markers (Locale.ROOT)
 * and independence from the default locale.
 */
class RuleBasedFormatterWordAndLocaleTest {

    private val formatter = RuleBasedFormatter()

    @Test
    fun `filler word uh is removed`() {
        assertEquals(
            "I have a cat.",
            formatter.format("uh, I have a cat")
        )
    }

    @Test
    fun `filler word er is removed`() {
        assertEquals(
            "I have a cat.",
            formatter.format("er, I have a cat")
        )
    }

    @Test
    fun `filler word erm is removed`() {
        assertEquals(
            "I have a cat.",
            formatter.format("erm, I have a cat")
        )
    }

    @Test
    fun `a question at the end keeps its question mark`() {
        assertEquals(
            "Is it ok?",
            formatter.format("is it ok?")
        )
    }

    @Test
    fun `an exclamation at the end keeps its exclamation mark`() {
        assertEquals(
            "Stop it!",
            formatter.format("stop it!")
        )
    }

    @Test
    fun `a filler-only lead leaves no lead line`() {
        assertEquals(
            "1. is a.\n2. is b.",
            formatter.format("um, one is a, two is b")
        )
        assertEquals(
            "- a.\n- b.",
            formatter.format("uh first a, second b")
        )
    }

    @Test
    fun `a lead ending in a question mark keeps it`() {
        assertEquals(
            "He said?\n\n1. is a.\n2. is b.",
            formatter.format("He said? one is a, two is b")
        )
    }

    @Test
    fun `a lead ending in an exclamation mark keeps it`() {
        assertEquals(
            "He said!\n\n1. is a.\n2. is b.",
            formatter.format("He said! one is a, two is b")
        )
    }

    @Test
    fun `trailing spaces after a terminal mark are trimmed, not doubled`() {
        assertEquals(
            "The dog sat down.",
            formatter.format("the dog sat down.   ")
        )
    }

    @Test
    fun `a capitalised ordinal marker still forms a list`() {
        assertEquals(
            "1. is milk.\n2. is eggs.",
            formatter.format("One is milk, two is eggs.")
        )
    }

    @Test
    fun `all-capital ordinal markers still form a list`() {
        assertEquals(
            "1. is milk.\n2. is eggs.",
            formatter.format("ONE is milk, TWO is eggs.")
        )
    }

    @Test
    fun `mixed-case ordinal markers form a list`() {
        assertEquals(
            "1. is milk.\n2. is eggs.\n3. is tea.",
            formatter.format("One is milk, two is eggs, Three is tea.")
        )
    }

    @Test
    fun `the is join is case-insensitive`() {
        assertEquals(
            "1. is milk.\n2. is eggs.",
            formatter.format("ONE IS milk, TWO IS eggs.")
        )
    }

    @Test
    fun `a capitalised non-ordinal word is not a marker`() {
        assertEquals(
            "Once is milk, two is eggs.",
            formatter.format("Once is milk, two is eggs.")
        )
    }

    @Test
    fun `ordinal matching is independent of the default locale`() {
        // the dotted/dotless I lives in FIRST/THIRD/FIVE: under a Turkish
        // default locale a locale-sensitive lowercasing of those words would
        // not find the ordinals and the lists would be missed
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "- coffee.\n- tea.\n- milk.",
                formatter.format("FIRST coffee, SECOND tea, THIRD milk")
            )
            assertEquals(
                "1. is a.\n2. is b.\n3. is c.\n4. is d.\n5. is e.",
                formatter.format("One is a, two is b, three is c, four is d, FIVE is e")
            )
        } finally {
            Locale.setDefault(saved)
        }
    }
}
