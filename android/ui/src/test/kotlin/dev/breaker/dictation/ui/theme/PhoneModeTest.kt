package dev.breaker.dictation.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Only the night bits (mask 0x30) of the phone's uiMode decide dark; 0x20 is the only dark value. */
class PhoneModeTest {
    @Test
    fun `night bits 0x20 are dark whatever the other bits are`() {
        for (uiMode in listOf(0x20, 0x21, 0x22, 0x24, 0x2F, 0x120, Int.MIN_VALUE or 0x20)) {
            assertEquals("uiMode 0x${uiMode.toString(16)}", true, isNightMode(uiMode))
        }
    }

    @Test
    fun `night bits 0x10 (not night) are light whatever the other bits are`() {
        for (uiMode in listOf(0x10, 0x11, 0x14, 0x1F, 0x110, Int.MIN_VALUE or 0x10)) {
            assertEquals("uiMode 0x${uiMode.toString(16)}", false, isNightMode(uiMode))
        }
    }

    @Test
    fun `night bits 0x00 (undefined) are light whatever the other bits are`() {
        for (uiMode in listOf(0x00, 0x01, 0x04, 0x0F, 0x100, Int.MIN_VALUE)) {
            assertEquals("uiMode 0x${uiMode.toString(16)}", false, isNightMode(uiMode))
        }
    }

    @Test
    fun `the unused night bits 0x30 are light`() {
        for (uiMode in listOf(0x30, 0x31, 0x3F, -1)) {
            assertEquals("uiMode 0x${uiMode.toString(16)}", false, isNightMode(uiMode))
        }
    }
}
