package dev.breaker.dictation.ui.screen.onboarding

import java.lang.reflect.Modifier
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
class SetupTextsTest {
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

    private fun wordPattern(word: String) =
        Regex("(?<![A-Za-z0-9-])" + Regex.escape(word) + "(?![A-Za-z0-9-])", RegexOption.IGNORE_CASE)

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

    /** Every String constant of the texts object, by name, read from its fields. */
    private fun constants(): Map<String, String> =
        SetupTexts::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { field ->
                field.setAccessible(true)
                field.name to (field.get(null) as String)
            }

    private val texts = constants()

    private fun text(name: String): String = texts[name] ?: error("no constant named $name")

    private fun countOf(text: String, needle: String): Int = text.split(needle).size - 1

    private val required = listOf(
        "TITLE", "INTRO", "STATUS_GRANTED", "STATUS_NOT_GRANTED",
        "HEADING_OVERLAY", "HEADING_MICROPHONE", "HEADING_NOTIFICATIONS", "HEADING_ACCESSIBILITY",
        "WHY_OVERLAY", "WHY_MICROPHONE", "WHY_NOTIFICATIONS", "WHY_ACCESSIBILITY",
        "BUTTON_REQUEST_MICROPHONE", "BUTTON_REQUEST_NOTIFICATIONS", "BUTTON_OVERLAY_PAGE",
        "BUTTON_NOTIFICATION_PAGE", "BUTTON_ACCESSIBILITY_LIST", "BUTTON_APP_INFO", "BUTTON_CHECK_AGAIN",
        "NOTIFICATIONS_LOST", "ACCESSIBILITY_LIMITS",
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
    fun `the notifications note names all three things that are lost`() {
        val note = text("NOTIFICATIONS_LOST")
        assertTrue("it says Breaker still works", note.contains("Breaker still works"))
        assertTrue("no notice while it is on", note.contains("you see no notice while it is on"))
        assertTrue("no switching off from the shade", note.contains("you cannot switch it off from the notification shade"))
        assertTrue("no opening of the history", note.contains("the notice cannot open your history"))
    }

    @Test
    fun `the accessibility limits state all five promises`() {
        val limits = text("ACCESSIBILITY_LIMITS")
        val promises = listOf(
            "it acts only when you send",
            "reads only the one field it types into",
            "never fills a password field",
            "keeps nothing from your screen",
            "sends nothing off the phone",
        )
        for (promise in promises) {
            assertTrue("the limits do not say \"$promise\"", limits.contains(promise))
        }
    }

    @Test
    fun `the three restricted steps name the menu words exactly, and the allow words once`() {
        val steps = listOf(text("RESTRICTED_STEP_1"), text("RESTRICTED_STEP_2"), text("RESTRICTED_STEP_3"))
        val allow = "Allow restricted settings"
        assertEquals("the exact menu words, counted over the three steps", 1, steps.sumOf { countOf(it, allow) })
        assertEquals("the menu words belong in step 2", 1, countOf(steps[1], allow))
        assertTrue("step 2 quotes the menu words", steps[1].contains("\"$allow\""))
        assertTrue("step 1 quotes Android's own label", steps[0].contains("\"Restricted setting\""))
        assertTrue("step 1 opens accessibility settings", steps[0].startsWith("Open accessibility settings. "))
        assertTrue("step 1 says to tap Breaker", steps[0].contains("tap Breaker"))
        assertTrue("step 1 names the list of installed apps", steps[0].contains("\"Installed apps\""))
        assertTrue("step 1 names the list of downloaded apps", steps[0].contains("\"Downloaded apps\""))
        assertTrue("step 2 says app info", steps[1].contains("app info"))
        assertTrue("step 2 says where the dots are", steps[1].contains("three dots at the top right"))
        assertTrue("step 3 goes back to accessibility settings", steps[2].startsWith("Go back to accessibility settings and "))
        assertTrue("step 3 says to turn on Breaker's access", steps[2].contains("turn on Breaker's accessibility access"))
        assertTrue("step 3 says to press Allow", steps[2].contains("press Allow"))
        assertTrue("step 3 ends by sending the user back", steps[2].endsWith("Then come back here."))
        assertFalse("step 3 repeats the menu words", steps[2].contains(allow))
    }

    @Test
    fun `the word switch is kept for Breaker's own switch and never names the accessibility toggle`() {
        val about = listOf(
            "HEADING_ACCESSIBILITY", "WHY_ACCESSIBILITY", "ACCESSIBILITY_LIMITS", "BUTTON_ACCESSIBILITY_LIST",
            "RESTRICTED_INTRO", "RESTRICTED_STEP_1", "RESTRICTED_STEP_2", "RESTRICTED_STEP_3", "WARNING_NO_ACCESSIBILITY",
        )
        for (name in about) {
            assertFalse("$name uses the word switch", wordPattern("switch").containsMatchIn(text(name)))
        }
    }

    @Test
    fun `the display over other apps page has one name in every text that names it`() {
        val name = "Display over other apps"
        val anyName = Regex("over other apps", RegexOption.IGNORE_CASE)
        for ((constant, shown) in texts) {
            val named = anyName.findAll(shown).count()
            val exact = countOf(shown, name)
            assertEquals("$constant names the page in a different way", named, exact)
        }
        assertEquals(name, text("HEADING_OVERLAY"))
        for (constant in listOf("WHY_OVERLAY", "BUTTON_OVERLAY_PAGE", "WARNING_NO_OVERLAY")) {
            assertTrue("$constant does not name the page", text(constant).contains("\"$name\""))
        }
    }

    @Test
    fun `the reasons for the tile and the microphone promise no more than is true`() {
        assertTrue("the tile starts it", text("WHY_OVERLAY").contains("You tap the tile to start."))
        assertFalse("the tile is not said to stop it", text("WHY_OVERLAY").contains("stop"))
        assertTrue("the microphone records when started", text("WHY_MICROPHONE").contains("It records only when you start it."))
        assertFalse("the microphone is not tied to the tile", text("WHY_MICROPHONE").contains("tile"))
    }

    @Test
    fun `the introduction says that only the microphone is needed to switch Breaker on`() {
        assertTrue(text("INTRO").contains("Only the microphone is needed to switch Breaker on"))
    }

    @Test
    fun `the accessibility reason leaves the clipboard to the warning`() {
        assertFalse("the reason repeats the clipboard sentence", text("WHY_ACCESSIBILITY").contains("clipboard"))
        assertTrue("the warning keeps the clipboard sentence", text("WARNING_NO_ACCESSIBILITY").contains("clipboard"))
    }

    @Test
    fun `the notifications reason promises a notice only when notices are allowed`() {
        val why = text("WHY_NOTIFICATIONS")
        assertTrue("the notice is conditional", why.startsWith("When notices are allowed, Breaker shows one while it is on."))
        assertFalse("the old unconditional wording is back", why.contains("shows a quiet notice"))
        assertTrue("the step is still optional", why.contains("You can skip this step."))
    }

    @Test
    fun `the accessibility settings page has one name in the steps and on the button`() {
        val page = "accessibility settings"
        for (constant in listOf("RESTRICTED_STEP_1", "RESTRICTED_STEP_3", "BUTTON_ACCESSIBILITY_LIST")) {
            assertTrue("$constant does not call the page \"$page\"", text(constant).contains(page, ignoreCase = true))
        }
        for ((constant, shown) in texts) {
            assertFalse("$constant calls the page a list", shown.contains("accessibility list", ignoreCase = true))
        }
    }

    @Test
    fun `the switch texts say what the user must know before turning it on`() {
        val note = text("SWITCH_NOTE_ONGOING_NOTICE")
        assertTrue("the notice is promised only when notices are allowed", note.startsWith("When notices are allowed"))
        assertTrue("the notice is shown while Breaker is on", note.contains("shows one while it is on"))
        assertFalse("no promise that depends on the phone", note.contains("stays") || note.contains("indicator"))
        assertTrue("the microphone is the reason", text("SWITCH_NEEDS_MICROPHONE").contains("microphone"))
        assertTrue("no tile", text("WARNING_NO_OVERLAY").contains("no tile"))
        assertTrue("the clipboard", text("WARNING_NO_ACCESSIBILITY").contains("clipboard"))
    }

    @Test
    fun `no text is left for a warning about notifications`() {
        assertFalse("WARNING_NO_NOTIFICATIONS is still defined", "WARNING_NO_NOTIFICATIONS" in texts)
        assertEquals("the warning texts", listOf("WARNING_NO_ACCESSIBILITY", "WARNING_NO_OVERLAY"), texts.keys.filter { it.startsWith("WARNING_") }.sorted())
    }
}
