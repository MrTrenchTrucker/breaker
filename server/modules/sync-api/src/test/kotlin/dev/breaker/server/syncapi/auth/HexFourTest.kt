package dev.breaker.server.syncapi.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check behind every unicode escape of the hand-written readers: exactly four ASCII hex digits. */
class HexFourTest {

    @Test
    fun `four ascii hex digits of either case are accepted`() {
        for (text in listOf("0000", "09af", "AF09", "aBcD", "7e1F")) {
            assertTrue("accepted " + text, isFourHexDigits(text))
        }
    }

    @Test
    fun `a sign a space a wrong length or a letter past f is refused`() {
        val arabicDigit = String(Character.toChars(0x0661))
        val refused = listOf("", "123", "12345", "ZZZZ", "+123", "-001", " 123", "12 3", "12g4", "0x12", arabicDigit + "234")
        for (text in refused) {
            assertFalse("refused " + text, isFourHexDigits(text))
        }
    }
}
