package dev.breaker.dictation.ui.screen.home

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three service-status sentences.
 *
 * The check is the one HomeTextsTest applies to the home words: plain ASCII,
 * not blank, none of the banned words. One exception is allowed, and it is
 * narrow: the three whole sentences the project owner typed in ADR-044,
 * allowing-list and all. The exception is a list of whole sentences, not the
 * words "service" and "services": any other text carrying a banned word still
 * fails the same check.
 */
class ServiceStatusTextsTest {
    private val highestAscii = 127

    private fun joined(vararg pieces: String): String = pieces.joinToString("")

    private val bannedWords = listOf(
        joined("ar", "med"), joined("ar", "m"), joined("ar", "ming"),
        joined("ser", "vice"), joined("ser", "vices"),
        joined("re", "lay"), joined("ha", "ul"), joined("sent", "inel"), joined("gr", "ip"),
        joined("sli", "ce"), joined("coun", "cil"), joined("sub", "-agent"), joined("rea", "dback"),
        joined("rul", "ing"), joined("buil", "der"), joined("hand", "off"), joined("inject", "ion"),
    )

    /**
     * ADR-044: the project owner typed these three sentences, so they are the
     * one allowed exception to the banned-word rule. Whole sentences only.
     */
    private val allowedSentences = listOf(
        "All services are up.",
        "Transcription is down.",
        "Server not reachable.",
    )

    private fun wordPattern(word: String) =
        Regex("(?<![A-Za-z0-9-])" + Regex.escape(word) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)

    private fun breaks(texts: Map<String, String>): List<String> = buildList {
        for ((name, text) in texts) {
            if (text.isBlank()) add("$name is blank")
            if (text.any { it.code > highestAscii }) add("$name has a character that is not ASCII")
            if (text !in allowedSentences) {
                for (word in bannedWords) {
                    if (wordPattern(word).containsMatchIn(text)) add("$name contains the word $word")
                }
            }
        }
    }

    private fun constants(): Map<String, String> =
        ServiceStatusTexts::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.setAccessible(true)
                field.name to (field.get(null) as String)
            }

    private val texts = constants()

    @Test
    fun `every service status sentence is plain ASCII, not blank and free of forbidden words`() {
        assertEquals("problems in the texts", emptyList<String>(), breaks(texts))
    }

    @Test
    fun `the three sentences are the exact ADR-044 words`() {
        assertEquals("All services are up.", texts["ALL_UP"])
        assertEquals("Transcription is down.", texts["TRANSCRIPTION_DOWN"])
        assertEquals("Server not reachable.", texts["SERVER_UNREACHABLE"])
    }

    @Test
    fun `the allow-list holds only the three ADR-044 sentences`() {
        assertEquals(
            "the allow-list is not the three ADR-044 sentences",
            listOf("All services are up.", "Transcription is down.", "Server not reachable."),
            allowedSentences,
        )
    }

    @Test
    fun `the all-up sentence really does carry a banned word, so the exception is load-bearing`() {
        val hits = bannedWords.filter { wordPattern(it).containsMatchIn("All services are up.") }
        assertEquals(
            "the all-up sentence carries no banned word, so the exception is dead",
            listOf(joined("ser", "vices")),
            hits,
        )
    }

    @Test
    fun `a fourth sentence carrying a banned word still fails the check`() {
        val found = breaks(mapOf("BAD" to "All services are down."))
        assertEquals(
            "the fourth sentence passed the check",
            listOf("BAD contains the word " + joined("ser", "vices")),
            found,
        )
    }

    @Test
    fun `the check can fail on a text that is not ASCII`() {
        val bad = mapOf("BAD" to ("caf" + Char(233)), "GOOD" to "Open the list.")
        assertEquals(listOf("BAD has a character that is not ASCII"), breaks(bad))
    }

    @Test
    fun `the check can fail on a blank text`() {
        assertEquals(listOf("EMPTY is blank"), breaks(mapOf("EMPTY" to "")))
        assertEquals(listOf("SPACES is blank"), breaks(mapOf("SPACES" to "   ")))
    }
}
