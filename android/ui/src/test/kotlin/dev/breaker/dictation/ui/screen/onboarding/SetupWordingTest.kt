package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the set-up screen shows, part two: the facts each text must state (what is lost without
 * notifications, the limits of accessibility access, the menu words, the page names and the privacy line).
 * The rules that hold for every text are in SetupTextsTest.
 */
internal class SetupWordingTest : SetupTextsTestBase() {

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
        assertTrue(
            "the limits do not add the keyboard sentence right after the field sentence",
            limits.contains(
                "It reads only the one field it types into. It also notices, without reading anything, " +
                    "whether a text field is selected and whether the keyboard is open. It never fills a password field.",
            ),
        )
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
    fun `the keyboard tile page has the heading and the reason the set-up texts give it`() {
        assertEquals("Floating tile with your keyboard", text("HEADING_KEYBOARD_TILE"))
        assertEquals(
            "When you open the keyboard to type in any app, Breaker can bring up its tile for you. " +
                "It uses the same accessibility access as the page before.",
            text("WHY_KEYBOARD_TILE"),
        )
    }

    @Test
    fun `the keyboard tile privacy line names what is looked at and what is remembered`() {
        val privacy = text("KEYBOARD_TILE_LIMITS")
        assertEquals(
            "the privacy line is not the exact sentence",
            "What Breaker looks at for this: only whether a text field is selected and whether the keyboard is open. " +
                "It never reads what is in the field or on your screen. " +
                "It remembers only the latest of those two yes-or-no answers while the app runs, saves nothing, " +
                "and sends nothing off the phone.",
            privacy,
        )
        assertTrue("the privacy line does not say the field and the screen are never read", privacy.contains("It never reads what is in the field or on your screen."))
        assertTrue("the privacy line does not say what is remembered", privacy.contains("It remembers only the latest of those two yes-or-no answers while the app runs"))
        assertTrue("the privacy line does not say nothing is saved", privacy.contains("saves nothing"))
        assertTrue("the privacy line does not say nothing is sent off the phone", privacy.contains("sends nothing off the phone."))
    }
}
