package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.ShakeWakeMode
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * The legacy boolean `wake_gesture_enabled` migrates to [ShakeWakeMode] on read,
 * and the new `shake_wake_mode` key wins when both are present. The legacy key is
 * read only: the codec never writes it, so these files are built by hand, the way
 * an older build left them.
 */
class SettingsShakeWakeMigrationTest : SettingsFileStoreTestBase() {

    private val legacyKey = "wake_gesture_enabled"

    /**
     * A file with the other settings keys at non-default values, the shake key set
     * to [newMode] when it is not null, and the legacy key set to [legacy] when it
     * is not null.
     */
    private fun settingsFile(newMode: String?, legacy: String?): File {
        val props = Properties()
        props.setProperty(KEY_MODE, SttMode.SERVER.name)
        props.setProperty(KEY_MODEL_SIZE, "medium")
        props.setProperty(KEY_SERVER_URL, "https://box.local")
        props.setProperty(KEY_TILE_POSITION, "0.25,0.75")
        props.setProperty(KEY_LANGUAGE, "de")
        props.setProperty(KEY_PRELOAD_MODEL, "false")
        props.setProperty(KEY_FORMATTING, "false")
        props.setProperty(KEY_THEME_MODE, ThemeMode.DARK.name)
        if (newMode != null) props.setProperty(KEY_SHAKE_WAKE, newMode)
        if (legacy != null) props.setProperty(legacyKey, legacy)
        val file = newFile()
        file.outputStream().use { props.store(it, "migration") }
        return file
    }

    @Test
    fun `a file with only wake_gesture_enabled true migrates to ALWAYS`() {
        assertEquals(
            "wake_gesture_enabled=true becomes ALWAYS",
            ShakeWakeMode.ALWAYS,
            store(settingsFile(newMode = null, legacy = "true")).load().shakeWakeMode,
        )
    }

    @Test
    fun `a file with only wake_gesture_enabled false migrates to OFF`() {
        assertEquals(
            "wake_gesture_enabled=false becomes OFF",
            ShakeWakeMode.OFF,
            store(settingsFile(newMode = null, legacy = "false")).load().shakeWakeMode,
        )
    }

    @Test
    fun `an unparsable wake_gesture_enabled migrates to ALWAYS`() {
        // The legacy boolean is read with a default of true, so a value that is
        // neither true nor false lands on ALWAYS, not OFF.
        assertEquals(
            "an unparsable legacy boolean takes the legacy default, ALWAYS",
            ShakeWakeMode.ALWAYS,
            store(settingsFile(newMode = null, legacy = "maybe")).load().shakeWakeMode,
        )
    }

    @Test
    fun `when both keys are present the new key wins`() {
        // Different values on purpose: the legacy key says ALWAYS, the new key says OFF.
        assertEquals(
            "the new shake_wake_mode wins over the legacy boolean",
            ShakeWakeMode.OFF,
            store(settingsFile(newMode = "OFF", legacy = "true")).load().shakeWakeMode,
        )
    }

    @Test
    fun `an unparsable new key with no legacy key falls back to ALWAYS`() {
        val loaded = store(settingsFile(newMode = "BANANA", legacy = null)).load()

        assertEquals(
            "an unknown mode name costs the key, and there is no legacy key, so the default",
            ShakeWakeMode.ALWAYS,
            loaded.shakeWakeMode,
        )
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SHAKE_WAKE)
    }

    @Test
    fun `an unparsable new key falls back to the legacy value when it is present`() {
        // BANANA is not a mode, so the legacy false is what the key falls back to.
        assertEquals(
            "the unparsable new key falls to the legacy value, OFF",
            ShakeWakeMode.OFF,
            store(settingsFile(newMode = "BANANA", legacy = "false")).load().shakeWakeMode,
        )
    }

    @Test
    fun `a file with neither shake key loads ALWAYS`() {
        assertEquals(
            "no shake key of either kind, so the default",
            ShakeWakeMode.ALWAYS,
            store(settingsFile(newMode = null, legacy = null)).load().shakeWakeMode,
        )
    }

    @Test
    fun `a file with the old key false loads as OFF and after one save holds shake_wake_mode OFF and no old key`() {
        val file = settingsFile(newMode = null, legacy = "false")
        val loaded = store(file).load()
        assertEquals("the legacy false loads as OFF", ShakeWakeMode.OFF, loaded.shakeWakeMode)

        store(file).save(loaded)

        val raw = rawPropertiesOf(file)
        assertEquals("the saved file holds the new key", "OFF", raw.getProperty(KEY_SHAKE_WAKE))
        assertNull("the saved file no longer holds the old key", raw.getProperty(legacyKey))
    }
}
