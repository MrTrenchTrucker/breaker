package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A settings store, a test or a caller may build [AppSettings] by position. These
 * are built without names on purpose: what they pin is which argument lands in
 * which field, so a reorder of fields of the same type cannot go unnoticed.
 */
class AppSettingsPositionTest {
    private val tile = TilePosition(0.25f, 0.75f)

    @Test
    fun `positional arguments bind to the fields in their declared order`() {
        val settings = AppSettings(SttMode.SERVER, "base", "https://box.local", "key-ref", ShakeWakeMode.OFF, false, tile, "de", false, false)

        assertEquals(SttMode.SERVER, settings.mode)
        assertEquals("base", settings.modelSize)
        assertEquals("https://box.local", settings.serverUrl)
        assertEquals("key-ref", settings.apiKeyRef)
        assertEquals(ShakeWakeMode.OFF, settings.shakeWakeMode)
        assertFalse(settings.showTileOnKeyboard)
        assertEquals(tile, settings.tilePosition)
        assertEquals("de", settings.language)
        assertFalse(settings.preloadModel)
        assertFalse(settings.formattingEnabled)
    }

    @Test
    fun `the three switches are told apart by position and formatting comes last`() {
        // Each case sets one of the three to a value the others do not share, so no two can trade places unseen.
        val wakeOnly = AppSettings(SttMode.AUTO, "small", "", null, ShakeWakeMode.KEYBOARD_ONLY, true, tile, "en", false, false)
        assertEquals(Triple(ShakeWakeMode.KEYBOARD_ONLY, false, false), Triple(wakeOnly.shakeWakeMode, wakeOnly.preloadModel, wakeOnly.formattingEnabled))

        val preloadOnly = AppSettings(SttMode.AUTO, "small", "", null, ShakeWakeMode.OFF, true, tile, "en", true, false)
        assertEquals(Triple(ShakeWakeMode.OFF, true, false), Triple(preloadOnly.shakeWakeMode, preloadOnly.preloadModel, preloadOnly.formattingEnabled))

        val formattingOnly = AppSettings(SttMode.AUTO, "small", "", null, ShakeWakeMode.OFF, true, tile, "en", false, true)
        assertEquals(
            Triple(ShakeWakeMode.OFF, false, true),
            Triple(formattingOnly.shakeWakeMode, formattingOnly.preloadModel, formattingOnly.formattingEnabled),
        )
    }
}
