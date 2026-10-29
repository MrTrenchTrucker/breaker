package dev.breaker.server.syncapi.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strict base64 rule: canonical standard base64 only, decided on the text itself. */
internal class StrictBase64Test {

    @Test
    fun `canonical padded base64 is strict`() {
        assertTrue("four characters", isStrictBase64("AQID"))
        assertTrue("two bytes with one padding character", isStrictBase64("AQI="))
        assertTrue("one byte with two padding characters", isStrictBase64("AQ=="))
    }

    @Test
    fun `unpadded base64 is not strict`() {
        assertFalse("two characters", isStrictBase64("AQ"))
        assertFalse("three characters", isStrictBase64("AQI"))
    }

    @Test
    fun `base64 with non zero trailing bits is not strict`() {
        assertFalse("AR== has a set bit below the byte", isStrictBase64("AR=="))
        assertFalse("AQJ= has set bits below the byte", isStrictBase64("AQJ="))
    }

    @Test
    fun `the url safe alphabet is not strict`() {
        assertFalse(isStrictBase64("-_8="))
    }

    @Test
    fun `whitespace inside base64 is not strict`() {
        assertFalse("a space", isStrictBase64("AQ ID"))
        assertFalse("a line feed", isStrictBase64("AQID\n"))
    }

    @Test
    fun `a character after the padding or a third padding character is not strict`() {
        assertFalse(isStrictBase64("AQ=I"))
        assertFalse(isStrictBase64("AQ==="))
    }

    @Test
    fun `an empty text is not strict`() {
        assertFalse(isStrictBase64(""))
    }

    @Test
    fun `canonical base64 with a plus and a slash is strict`() {
        assertTrue("four pluses encode to three bytes that re-encode to the same text", isStrictBase64("++++"))
        assertTrue("four slashes encode to three bytes that re-encode to the same text", isStrictBase64("////"))
    }

    @Test
    fun `the url safe runs of minus and underscore are not strict`() {
        assertFalse("four minus signs", isStrictBase64("----"))
        assertFalse("four underscores", isStrictBase64("____"))
    }
}
