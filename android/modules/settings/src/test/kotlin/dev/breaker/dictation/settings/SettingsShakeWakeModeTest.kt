package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shake mode and the tile-on-keyboard switch: each is stored under its own
 * key, survives a restart, and changes without touching the other.
 */
class SettingsShakeWakeModeTest : SettingsFileStoreTestBase() {

    private val legacyKey = "wake_gesture_enabled"

    @Test
    fun `every shake wake mode and both switch values survive a new store instance over the same file`() {
        for (mode in ShakeWakeMode.entries) {
            for (showTile in listOf(true, false)) {
                val file = newFile()
                store(file).save(validSettings().copy(shakeWakeMode = mode, showTileOnKeyboard = showTile))

                val reloaded = store(file).load()

                assertEquals("the shake mode ${mode.name} comes back", mode, reloaded.shakeWakeMode)
                assertEquals("the tile switch $showTile comes back", showTile, reloaded.showTileOnKeyboard)
            }
        }
    }

    @Test
    fun `the shake mode and the tile switch change independently of each other`() {
        val file = newFile()
        val writer = store(file)

        writer.save(validSettings().copy(shakeWakeMode = ShakeWakeMode.ALWAYS, showTileOnKeyboard = true))
        writer.save(validSettings().copy(shakeWakeMode = ShakeWakeMode.ALWAYS, showTileOnKeyboard = false))
        val switchChanged = store(file).load()
        assertEquals("changing the switch leaves the mode", ShakeWakeMode.ALWAYS, switchChanged.shakeWakeMode)
        assertFalse("the switch moved to off", switchChanged.showTileOnKeyboard)

        writer.save(validSettings().copy(shakeWakeMode = ShakeWakeMode.OFF, showTileOnKeyboard = false))
        val modeChanged = store(file).load()
        assertEquals("the mode moved to OFF", ShakeWakeMode.OFF, modeChanged.shakeWakeMode)
        assertFalse("changing the mode leaves the switch off", modeChanged.showTileOnKeyboard)
    }

    @Test
    fun `encode never writes the old wake_gesture_enabled key`() {
        val file = newFile()

        store(file).save(validSettings().copy(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY, showTileOnKeyboard = true))

        val raw = rawPropertiesOf(file)
        assertNull("the old key was written", raw.getProperty(legacyKey))
        assertEquals("the new mode key is written", "KEYBOARD_ONLY", raw.getProperty(KEY_SHAKE_WAKE))
        assertEquals("the new switch key is written", "true", raw.getProperty(KEY_SHOW_TILE))
    }

    @Test
    fun `an absent show_tile_on_keyboard loads as true`() {
        // fileWith writes the nine other keys and no switch key.
        val loaded = store(fileWith(KEY_SHAKE_WAKE, ShakeWakeMode.OFF.name)).load()

        assertTrue("an absent switch is the default, on", loaded.showTileOnKeyboard)
    }

    @Test
    fun `show_tile_on_keyboard false loads as false`() {
        val loaded = store(fileWith(KEY_SHOW_TILE, "false")).load()

        assertFalse("the written false loads as false", loaded.showTileOnKeyboard)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SHOW_TILE)
    }

    @Test
    fun `show_tile_on_keyboard is never read from the old key`() {
        // The old key says false and the switch key is absent: the switch is still on.
        val loaded = store(fileWith(legacyKey, "false")).load()

        assertTrue("the old key does not decide the switch", loaded.showTileOnKeyboard)
    }

    @Test
    fun `an unparseable shake wake name falls back alone`() {
        val loaded = store(fileWith(KEY_SHAKE_WAKE, "maybe")).load()

        assertEquals("shake_wake_mode", AppSettings().shakeWakeMode, loaded.shakeWakeMode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SHAKE_WAKE)
    }
}
