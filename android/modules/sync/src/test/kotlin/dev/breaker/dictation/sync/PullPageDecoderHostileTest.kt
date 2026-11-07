package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Hostile and privacy tests over malformed JSON bodies. */
class PullPageDecoderHostileTest {

    @Test
    fun `only PullPageException leaves decode over hostile texts`() {
        val text1 = ""
        val text2 = "{"
        val text3 = "[" .repeat(100000)
        val text4 = "123"
        val text5 = """{"items":[],"next":"x","more":true,"bad":"\uXXXX"}"""
        val text6 = """{"items":[],"next":"x","more":true,"text":"\uD800"}"""
        val text7 = """{"items":[],"next":"x","more":true,"text":"\uDC00"}"""
        val hostile = listOf(text1, text2, text3, text4, text5, text6, text7)
        for (text in hostile) {
            val thrown = assertThrows(
                "hostile text must throw PullPageException",
                PullPageException::class.java,
            ) { PullPageDecoder.decode(text) }
            assertEquals("must be PullPageException", PullPageException::class.java, thrown.javaClass)
        }
    }

    @Test
    fun `valid surrogate pair decodes`() {
        val page = PullPageDecoder.decode("""{"items":[],"next":"x","more":true,"text":"\uD800\uDC00"}""")
        assertEquals("next passed through", "x", page.next)
    }

    @Test
    fun `valid null escape decodes`() {
        val page = PullPageDecoder.decode("""{"items":[],"next":"x","more":true,"text":"\u0000"}""")
        assertEquals("next passed through", "x", page.next)
    }

    @Test
    fun `bare top-level value throws`() {
        assertThrows(
            "bare number must refuse",
            PullPageException::class.java,
        ) { PullPageDecoder.decode("42") }
        val ok = PullPageDecoder.decode("""{"items":[],"next":"x","more":true}""")
        assertEquals("control decodes", 0, ok.items.size)
    }
}
