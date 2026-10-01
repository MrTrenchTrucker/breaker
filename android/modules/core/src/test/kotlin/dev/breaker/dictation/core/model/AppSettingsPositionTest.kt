package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val settings = AppSettings(SttMode.SERVER, "base", "https://box.local", "key-ref", true, tile, "de", false, false)

        assertEquals(SttMode.SERVER, settings.mode)
        assertEquals("base", settings.modelSize)
        assertEquals("https://box.local", settings.serverUrl)
        assertEquals("key-ref", settings.apiKeyRef)
        assertTrue(settings.wakeGestureEnabled)
        assertEquals(tile, settings.tilePosition)
        assertEquals("de", settings.language)
        assertFalse(settings.preloadModel)
        assertFalse(settings.formattingEnabled)
    }

    @Test
    fun `the three switches are told apart by position and formatting comes last`() {
        // Exactly one of the three is on in each, so no two of them can trade places unseen.
        val wakeOnly = AppSettings(SttMode.AUTO, "small", "", null, true, tile, "en", false, false)
        assertEquals(Triple(true, false, false), Triple(wakeOnly.wakeGestureEnabled, wakeOnly.preloadModel, wakeOnly.formattingEnabled))

        val preloadOnly = AppSettings(SttMode.AUTO, "small", "", null, false, tile, "en", true, false)
        assertEquals(Triple(false, true, false), Triple(preloadOnly.wakeGestureEnabled, preloadOnly.preloadModel, preloadOnly.formattingEnabled))

        val formattingOnly = AppSettings(SttMode.AUTO, "small", "", null, false, tile, "en", false, true)
        assertEquals(
            Triple(false, false, true),
            Triple(formattingOnly.wakeGestureEnabled, formattingOnly.preloadModel, formattingOnly.formattingEnabled),
        )
    }
}
