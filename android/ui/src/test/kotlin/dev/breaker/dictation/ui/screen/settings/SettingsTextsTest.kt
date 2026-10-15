package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every new settings word equals its approved text, and the language name is the only one that maps a code. */
class SettingsTextsTest {
    @Test
    fun `every new settings text equals its approved words exactly`() {
        assertEquals("System", SettingsTexts.SEGMENT_SYSTEM)
        assertEquals("Light", SettingsTexts.SEGMENT_LIGHT)
        assertEquals("Dark", SettingsTexts.SEGMENT_DARK)
        assertEquals("System matches your phone's light or dark setting.", SettingsTexts.APPEARANCE_SYSTEM_HELP)
        assertEquals("Where speech turns into text", SettingsTexts.ROUTING_HEADING)
        assertEquals("Automatic", SettingsTexts.ROUTING_AUTO)
        assertEquals("Phone", SettingsTexts.ROUTING_PHONE)
        assertEquals("Server", SettingsTexts.ROUTING_SERVER)
        assertEquals(
            "Automatic uses the server when it answers, and the phone when it does not.",
            SettingsTexts.ROUTING_AUTO_HELP,
        )
        assertEquals("Keeps the model ready for a faster start.", SettingsTexts.PRELOAD_HELP)
        assertEquals("Shake the phone to bring the tile back.", SettingsTexts.WAKE_HELP)
        assertEquals("Spoken lists become lists. Filler words go.", SettingsTexts.FORMATTING_HELP)
        assertEquals("Server address", SettingsTexts.SERVER_ROW_LABEL)
        assertEquals("English", SettingsTexts.LANGUAGE_ENGLISH)
    }

    @Test
    fun `the language name maps english and returns every other code as stored`() {
        assertEquals("English", languageName("en"))
        assertEquals("de", languageName("de"))
        assertEquals("fr-CA", languageName("fr-CA"))
        assertEquals("EN", languageName("EN"))
    }

    @Test
    fun `the model label is the set-up model heading`() {
        assertEquals("Speech model", SetupTexts.HEADING_MODEL)
    }
}
