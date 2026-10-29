package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShakeWakeMode.shakeArms] is the one pure rule the shake-to-wake setting comes
 * down to: given whether a keyboard is on screen, may a shake bring the tile
 * back? Every mode is checked with the keyboard up and with it down.
 */
class ShakeWakeModeTest {

    @Test
    fun `ALWAYS shakes the arms whether the keyboard is up or down`() {
        assertTrue("always, keyboard up", ShakeWakeMode.ALWAYS.shakeArms(keyboardUp = true))
        assertTrue("always, keyboard down", ShakeWakeMode.ALWAYS.shakeArms(keyboardUp = false))
    }

    @Test
    fun `OFF never shakes the arms whether the keyboard is up or down`() {
        assertFalse("off, keyboard up", ShakeWakeMode.OFF.shakeArms(keyboardUp = true))
        assertFalse("off, keyboard down", ShakeWakeMode.OFF.shakeArms(keyboardUp = false))
    }

    @Test
    fun `KEYBOARD_ONLY shakes the arms exactly when the keyboard is up`() {
        assertTrue("keyboard only, keyboard up", ShakeWakeMode.KEYBOARD_ONLY.shakeArms(keyboardUp = true))
        assertFalse("keyboard only, keyboard down", ShakeWakeMode.KEYBOARD_ONLY.shakeArms(keyboardUp = false))
    }

    @Test
    fun `ShakeWakeMode has exactly the three values in order`() {
        assertEquals(listOf("ALWAYS", "KEYBOARD_ONLY", "OFF"), ShakeWakeMode.entries.map { it.name })
    }

    @Test
    fun `a fresh settings value shakes ALWAYS and shows the tile on a keyboard`() {
        assertEquals(ShakeWakeMode.ALWAYS, AppSettings().shakeWakeMode)
        assertTrue(AppSettings().showTileOnKeyboard)
    }
}
