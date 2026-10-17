package dev.breaker.dictation.probe

import org.junit.Assert.assertEquals
import org.junit.Test

internal class KeyboardSignalSettingTest {

    @Test
    fun `the words map to their values`() {
        assertEquals("probe: the word OFF must read as OFF", KeyboardSignalSetting.OFF, keyboardSignalFromWord("OFF"))
        assertEquals(
            "probe: the word FIELD_FOCUS must read as FIELD_FOCUS",
            KeyboardSignalSetting.FIELD_FOCUS,
            keyboardSignalFromWord("FIELD_FOCUS"),
        )
        assertEquals(
            "probe: the word KEYBOARD_VISIBLE must read as KEYBOARD_VISIBLE",
            KeyboardSignalSetting.KEYBOARD_VISIBLE,
            keyboardSignalFromWord("KEYBOARD_VISIBLE"),
        )
    }

    @Test
    fun `an unknown word reads as off`() {
        assertEquals(
            "probe: a lower case word must read as OFF",
            KeyboardSignalSetting.OFF,
            keyboardSignalFromWord("keyboard_visible"),
        )
        assertEquals("probe: an unknown word must read as OFF", KeyboardSignalSetting.OFF, keyboardSignalFromWord("NOPE"))
    }

    @Test
    fun `a missing word reads as off`() {
        assertEquals("probe: a missing word must read as OFF", KeyboardSignalSetting.OFF, keyboardSignalFromWord(null))
        assertEquals("probe: an empty word must read as OFF", KeyboardSignalSetting.OFF, keyboardSignalFromWord(""))
    }
}
