package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins that the once-per-take send rule has one definition in the app sources. The rule reads the real main
 * files, with comments and string text removed first, so a comment that repeats the rule is not counted.
 */
internal class PhraseSingleSendRuleGateTest {

    private val definition: Regex = Regex("""\bfun\s+claimSend\s*\(""")

    private val compare: Regex = Regex("""\bclaimed\s*\.\s*compareAndSet\s*\(\s*false\s*,\s*true\s*\)""")

    private fun countIn(sources: Map<String, String>, pattern: Regex): Int =
        sources.values.map { pattern.findAll(AppSourceFiles.strip(it).code).count() }.sum()

    @Test
    fun `the once-per-take send rule is defined in exactly one place in the app sources`() {
        val sources = AppSourceFiles.mainKotlinSources()
        assertEquals("app: the one-send rule must be defined once", 1, countIn(sources, definition))
        assertEquals("app: the one-send compare must appear once", 1, countIn(sources, compare))
    }

    @Test
    fun `a second definition of the one-send rule is counted`() {
        val sources = AppSourceFiles.mainKotlinSources()
        val copy = mapOf("wiring/Copy.kt" to "class Copy { fun claimSend(): Boolean = claimed.compareAndSet(false, true) }\n")
        assertEquals("app: a second definition of the one-send rule was not counted", 2, countIn(sources + copy, definition))
    }
}
