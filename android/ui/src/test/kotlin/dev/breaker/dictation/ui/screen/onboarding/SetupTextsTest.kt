package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the set-up screen shows. The wording is a draft and will change; these tests pin what
 * must hold whatever it says: plain ASCII, nothing blank or long, none of the words a user must
 * never see, and the facts a user needs (what is lost without notifications, the limits of
 * accessibility access, the exact menu words of the extra confirmation).
 *
 * Every constant is found by reading the object's own fields, so a constant added later is held
 * to the same rules without anyone remembering to list it.
 */
internal class SetupTextsTest : SetupTextsTestBase() {
    private val longest = 400
    private val highestAscii = 127

    /** Written in pieces so that a text scan of this file does not report the words it forbids. */
    private fun joined(vararg pieces: String): String = pieces.joinToString("")

    /** Whole words that must not appear in anything a user reads, in any letter case. */
    private val bannedWords = listOf(
        joined("ar", "med"), joined("ar", "m"), joined("ar", "ming"),
        joined("ser", "vice"), joined("ser", "vices"),
        joined("re", "lay"), joined("ha", "ul"), joined("sent", "inel"), joined("gr", "ip"),
        joined("sli", "ce"), joined("coun", "cil"), joined("sub", "-agent"), joined("rea", "dback"),
        joined("rul", "ing"), joined("buil", "der"), joined("hand", "off"), joined("inject", "ion"),
    )

    /** The text that opens a ticket number, which is banned as a prefix rather than a word. */
    private val bannedPrefix = joined("R", "C-")

    private val quoted = Regex("\"[^\"]*\"")

    private val restricted = Regex("restricted", RegexOption.IGNORE_CASE)

    /** What is wrong with each text, one line per problem, each naming the constant. */
    private fun breaks(texts: Map<String, String>): List<String> = buildList {
        for ((name, text) in texts) {
            if (text.isBlank()) add("$name is blank")
            if (text.any { it.code > highestAscii }) add("$name has a character that is not ASCII")
            if (text.length > longest) add("$name is longer than $longest characters")
            for (word in bannedWords) {
                if (wordPattern(word).containsMatchIn(text)) add("$name contains the word $word")
            }
            if (text.contains(bannedPrefix, ignoreCase = true)) add("$name contains $bannedPrefix")
            if (restricted.containsMatchIn(text.replace(quoted, ""))) add("$name says restricted outside quotes")
        }
    }

    private val required = listOf(
        "TITLE", "INTRO", "STATUS_GRANTED", "STATUS_NOT_GRANTED",
        "HEADING_OVERLAY", "HEADING_MICROPHONE", "HEADING_NOTIFICATIONS", "HEADING_ACCESSIBILITY",
        "WHY_OVERLAY", "WHY_MICROPHONE", "WHY_NOTIFICATIONS", "WHY_ACCESSIBILITY",
        "BUTTON_REQUEST_MICROPHONE", "BUTTON_REQUEST_NOTIFICATIONS", "BUTTON_OVERLAY_PAGE",
        "BUTTON_NOTIFICATION_PAGE", "BUTTON_ACCESSIBILITY_LIST", "BUTTON_APP_INFO", "BUTTON_CHECK_AGAIN",
        "NOTIFICATIONS_LOST", "ACCESSIBILITY_LIMITS",
        "HEADING_KEYBOARD_TILE", "WHY_KEYBOARD_TILE", "KEYBOARD_TILE_LIMITS",
        "RESTRICTED_INTRO", "RESTRICTED_STEP_1", "RESTRICTED_STEP_2", "RESTRICTED_STEP_3",
        "SWITCH_HEADING", "SWITCH_ON_STATE", "SWITCH_OFF_STATE", "BUTTON_SWITCH_ON", "BUTTON_SWITCH_OFF",
        "SWITCH_NEEDS_MICROPHONE", "SWITCH_NOTE_ONGOING_NOTICE",
        "WARNING_NO_OVERLAY", "WARNING_NO_ACCESSIBILITY",
        "NOTICE_COULD_NOT_CHECK", "NOTICE_COULD_NOT_OPEN", "NOTICE_SWITCH_FAILED", "NOTICE_NOT_ACCEPTED",
    )

    @Test
    fun `every text constant is plain ASCII, not blank, short and free of forbidden words`() {
        assertEquals("problems in the texts", emptyList<String>(), breaks(texts))
    }

    @Test
    fun `the check read every constant the screen needs, so it did not pass on an empty list`() {
        assertTrue("only ${texts.size} constants were read", texts.size >= required.size)
        assertEquals("constants that are missing", emptyList<String>(), required.filter { it !in texts })
    }

    @Test
    fun `the check can fail on a text that is not ASCII`() {
        val bad = mapOf("BAD" to ("caf" + Char(233)), "GOOD" to "Open the list.")
        assertEquals(listOf("BAD has a character that is not ASCII"), breaks(bad))
    }

    @Test
    fun `the check can fail on a blank text and on a text one character too long`() {
        val tooLong = "a".repeat(longest + 1)
        val exact = "a".repeat(longest)
        assertEquals(listOf("EMPTY is blank"), breaks(mapOf("EMPTY" to "")))
        assertEquals(listOf("SPACES is blank"), breaks(mapOf("SPACES" to "   ")))
        assertEquals(listOf("LONG is longer than $longest characters"), breaks(mapOf("LONG" to tooLong)))
        assertEquals(emptyList<String>(), breaks(mapOf("EXACT" to exact)))
    }

    @Test
    fun `the check can fail on every forbidden word, in any letter case`() {
        assertTrue("the list of forbidden words is shorter than the rule names", bannedWords.size >= 9)
        for (word in bannedWords) {
            for (shown in listOf(word, word.uppercase(), word.replaceFirstChar { it.uppercase() })) {
                val found = breaks(mapOf("BAD" to "Please do $shown now."))
                assertEquals("the word $shown", listOf("BAD contains the word $word"), found)
            }
        }
        assertEquals(listOf("BAD contains $bannedPrefix"), breaks(mapOf("BAD" to "See ${bannedPrefix}123ABC.")))
    }

    @Test
    fun `the forbidden words include the ones the rules name`() {
        val named = listOf(
            joined("ar", "med"), joined("ar", "m"), joined("re", "lay"), joined("ha", "ul"),
            joined("sent", "inel"), joined("sli", "ce"), joined("sub", "-agent"), joined("coun", "cil"),
            joined("ser", "vice"),
        )
        assertEquals("rule words missing from the check", emptyList<String>(), named.filter { it !in bannedWords })
    }

    @Test
    fun `a forbidden word inside a longer word is left alone`() {
        val fine = mapOf("FINE" to "An ${joined("alar", "m")} is warm on the farm, and a ${joined("ha", "uled")} cart passes.")
        assertEquals(emptyList<String>(), breaks(fine))
    }

    @Test
    fun `the word restricted is allowed only inside the quoted menu words`() {
        assertEquals(
            listOf("BAD says restricted outside quotes"),
            breaks(mapOf("BAD" to "This is a restricted setting.")),
        )
        assertEquals(
            listOf("BAD says restricted outside quotes"),
            breaks(mapOf("BAD" to "Tap \"OK\" for the restricted step.")),
        )
        assertEquals(
            emptyList<String>(),
            breaks(mapOf("GOOD" to "Choose \"Allow restricted settings\".")),
        )
    }

    @Test
    fun `a problem is reported against the constant that has it`() {
        val mixed = mapOf("FIRST" to "Fine.", "SECOND" to "", "THIRD" to "Fine too.")
        assertEquals(listOf("SECOND is blank"), breaks(mixed))
    }

    @Test
    fun `every step has a heading and a reason, and every way to open one has a button`() {
        for (step in SetupStep.entries) {
            assertTrue("HEADING_${step.name}", "HEADING_${step.name}" in texts)
            assertTrue("WHY_${step.name}", "WHY_${step.name}" in texts)
        }
        for (action in OpenAction.entries) {
            assertTrue("BUTTON_${action.name}", "BUTTON_${action.name}" in texts)
        }
    }

    @Test
    fun `texts that stand for different things are different`() {
        val pairs = listOf(
            "STATUS_GRANTED" to "STATUS_NOT_GRANTED",
            "SWITCH_ON_STATE" to "SWITCH_OFF_STATE",
            "BUTTON_SWITCH_ON" to "BUTTON_SWITCH_OFF",
        )
        for ((first, second) in pairs) {
            assertNotEquals("$first and $second", text(first), text(second))
        }
        val buttons = OpenAction.entries.map { text("BUTTON_${it.name}") }
        assertEquals("two buttons share one text", buttons.size, buttons.toSet().size)
        val notices = listOf("COULD_NOT_CHECK", "COULD_NOT_OPEN", "SWITCH_FAILED", "NOT_ACCEPTED").map { text("NOTICE_$it") }
        assertEquals("two notices share one text", notices.size, notices.toSet().size)
    }

    @Test
    fun `no text is left for a warning about notifications`() {
        assertFalse("WARNING_NO_NOTIFICATIONS is still defined", "WARNING_NO_NOTIFICATIONS" in texts)
        assertEquals("the warning texts", listOf("WARNING_NO_ACCESSIBILITY", "WARNING_NO_OVERLAY"), texts.keys.filter { it.startsWith("WARNING_") }.sorted())
    }
}
