package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.history.HistoryTexts
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words the home screen shows. The wording is a draft and will change; these tests pin what
 * must hold whatever it says: plain ASCII, nothing blank, none of the words a user must never
 * see, and the reused words matching their sources.
 */
class HomeTextsTest {
    private val highestAscii = 127

    private fun joined(vararg pieces: String): String = pieces.joinToString("")

    private val bannedWords = listOf(
        joined("ar", "med"), joined("ar", "m"), joined("ar", "ming"),
        joined("ser", "vice"), joined("ser", "vices"),
        joined("re", "lay"), joined("ha", "ul"), joined("sent", "inel"), joined("gr", "ip"),
        joined("sli", "ce"), joined("coun", "cil"), joined("sub", "-agent"), joined("rea", "dback"),
        joined("rul", "ing"), joined("buil", "der"), joined("hand", "off"), joined("inject", "ion"),
    )

    private fun wordPattern(word: String) =
        Regex("(?<![A-Za-z0-9-])" + Regex.escape(word) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)

    private fun breaks(texts: Map<String, String>): List<String> = buildList {
        for ((name, text) in texts) {
            if (text.isBlank()) add("$name is blank")
            if (text.any { it.code > highestAscii }) add("$name has a character that is not ASCII")
            for (word in bannedWords) {
                if (wordPattern(word).containsMatchIn(text)) add("$name contains the word $word")
            }
        }
    }

    private fun constants(): Map<String, String> =
        HomeTexts::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.setAccessible(true)
                field.name to (field.get(null) as String)
            }

    private val texts = constants()

    private fun text(name: String): String = texts[name] ?: error("no constant named $name")

    @Test
    fun `every text constant is plain ASCII, not blank and free of forbidden words`() {
        val all = texts + mapOf(
            "MENU_HISTORY" to HomeTexts.MENU_HISTORY,
            "MENU_SETTINGS" to HomeTexts.MENU_SETTINGS,
            "BUTTON_COPY" to HomeTexts.BUTTON_COPY,
            "EMPTY_BOX" to HomeTexts.EMPTY_BOX,
            "TAG_SERVER" to HomeTexts.TAG_SERVER,
            "HEADING_MODEL" to HomeTexts.HEADING_MODEL,
            "SWITCH_ON" to HomeTexts.SWITCH_ON,
            "SWITCH_OFF" to HomeTexts.SWITCH_OFF,
            "STATUS_MODEL_READY" to HomeTexts.STATUS_MODEL_READY,
            "CANCEL" to HomeTexts.CANCEL,
            "DONE" to HomeTexts.DONE,
        )
        assertEquals("problems in the texts", emptyList<String>(), breaks(all))
    }

    @Test
    fun `the approved words are the literals the plan names`() {
        assertEquals("Tap to talk", text("TAP_TO_TALK"))
        assertEquals("Not available yet.", text("MENU_NOT_BUILT"))
        assertEquals("Not downloaded", text("STATUS_MODEL_MISSING"))
        assertEquals("Not available", text("STATUS_NOT_AVAILABLE"))
        assertEquals("Cancel", text("CANCEL"))
        assertEquals("Done", text("DONE"))
    }

    @Test
    fun `the seven ledger words are the values the strings ledger approves`() {
        assertEquals("How it works", text("MENU_HOW_IT_WORKS"))
        assertEquals("About", text("MENU_ABOUT"))
        assertEquals("Menu", text("MENU_BUTTON"))
        assertEquals("Your words go straight to the clipboard.", text("TAP_HINT"))
        assertEquals("Last transcript", text("LAST_TRANSCRIPT"))
        assertEquals("Breaker", text("LOGO"))
        assertEquals("Last dictation", text("STATUS_LAST_DICTATION"))
    }

    @Test
    fun `the reused words are the other objects' own constants`() {
        assertEquals(HistoryTexts.TITLE, HomeTexts.MENU_HISTORY)
        assertEquals(SettingsScreen.TITLE, HomeTexts.MENU_SETTINGS)
        assertEquals(HistoryTexts.BUTTON_COPY, HomeTexts.BUTTON_COPY)
        assertEquals(HistoryTexts.EMPTY, HomeTexts.EMPTY_BOX)
        assertEquals(HistoryTexts.TAG_SERVER, HomeTexts.TAG_SERVER)
        assertEquals(SetupTexts.HEADING_MODEL, HomeTexts.HEADING_MODEL)
        assertEquals(SetupTexts.SWITCH_ON_STATE, HomeTexts.SWITCH_ON)
        assertEquals(SetupTexts.SWITCH_OFF_STATE, HomeTexts.SWITCH_OFF)
        assertEquals(ControlTexts.BADGE_READY, HomeTexts.STATUS_MODEL_READY)
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

    @Test
    fun `the check can fail on every forbidden word`() {
        for (word in bannedWords) {
            val found = breaks(mapOf("BAD" to "Please do $word now."))
            assertEquals("the word $word", listOf("BAD contains the word $word"), found)
        }
    }
}
