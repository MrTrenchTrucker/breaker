package dev.breaker.dictation.wiring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [isNight], the pure check of a Configuration.uiMode field's night bits.
 *
 * The night bits in uiMode are UI_MODE_NIGHT_MASK (0x30) and the night value is
 * UI_MODE_NIGHT_YES (0x20). The check is (uiMode and 0x30) == 0x20.
 */
internal class IsNightTest {

    @Test
    fun `UI_MODE_NIGHT_NO is not night`() {
        // Configuration.UI_MODE_NIGHT_NO = 0x10
        assertFalse(isNight(0x10))
    }

    @Test
    fun `UI_MODE_NIGHT_YES is night`() {
        // Configuration.UI_MODE_NIGHT_YES = 0x20
        assertTrue(isNight(0x20))
    }

    @Test
    fun `both night bits set is not night`() {
        // 0x30 carries both UI_MODE_NIGHT_MASK bits; masked it is 0x30, not 0x20
        assertFalse(isNight(0x30))
    }

    @Test
    fun `undefined is not night`() {
        assertFalse(isNight(0x00))
    }

    @Test
    fun `unrelated bits set with night is still night`() {
        // 0x21 = night bit (0x20) | unrelated bit (0x01)
        assertTrue(isNight(0x21))
    }

    @Test
    fun `unrelated bits set without night is not night`() {
        // 0x11 = UI_MODE_NIGHT_NO (0x10) | unrelated bit (0x01)
        assertFalse(isNight(0x11))
    }
}
