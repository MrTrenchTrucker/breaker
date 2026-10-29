package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone prompt flag is bookkeeping on [AppSettings]: it defaults to
 * off, it moves on its own, and it is part of the value's equality.
 */
class AppSettingsMicrophoneAskedTest {

    /** Every field except the flag, each at a value other than its default. */
    private val withEveryOtherFieldChanged = AppSettings(
        mode = SttMode.SERVER,
        modelSize = "medium",
        serverUrl = "https://box.local",
        apiKeyRef = "key-ref",
        shakeWakeMode = ShakeWakeMode.OFF,
        tilePosition = TilePosition(0.25f, 0.75f),
        language = "de",
        preloadModel = false,
        formattingEnabled = false,
        themeMode = ThemeMode.DARK,
    )

    @Test
    fun `the microphone prompt flag is off by default`() {
        assertFalse(AppSettings().microphoneAsked)
    }

    @Test
    fun `changing only the flag leaves every other field as it was`() {
        val before = withEveryOtherFieldChanged
        val after = before.copy(microphoneAsked = true)

        assertTrue(after.microphoneAsked)
        assertEquals("mode", before.mode, after.mode)
        assertEquals("model size", before.modelSize, after.modelSize)
        assertEquals("server address", before.serverUrl, after.serverUrl)
        assertEquals("key reference", before.apiKeyRef, after.apiKeyRef)
        assertEquals("wake gesture", before.shakeWakeMode, after.shakeWakeMode)
        assertEquals("tile position", before.tilePosition, after.tilePosition)
        assertEquals("language", before.language, after.language)
        assertEquals("preload model", before.preloadModel, after.preloadModel)
        assertEquals("formatting", before.formattingEnabled, after.formattingEnabled)
        assertEquals("theme mode", before.themeMode, after.themeMode)
    }

    @Test
    fun `two settings that differ only in the flag are not equal`() {
        val unasked = withEveryOtherFieldChanged
        val asked = unasked.copy(microphoneAsked = true)

        assertNotEquals(unasked, asked)
    }
}
