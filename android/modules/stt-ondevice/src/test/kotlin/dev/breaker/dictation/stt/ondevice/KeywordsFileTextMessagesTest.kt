package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixed sentences of the keywords file text: distinct, plain, and never carrying the input. */
class KeywordsFileTextMessagesTest {

    private val all = listOf(
        KeywordsFileText.NO_LINES,
        KeywordsFileText.NO_TOKENS,
        KeywordsFileText.BAD_TOKEN,
        KeywordsFileText.BAD_NUMBER,
        KeywordsFileText.EMPTY_LINE,
        KeywordsFileText.SPACE_AFTER_MARKER,
        KeywordsFileText.UNKNOWN_TOKEN,
        KeywordsFileText.TOO_MANY_LINES,
        KeywordsFileText.TOO_LARGE,
        KeywordsFileText.MALFORMED_LINE,
    )

    @Test
    fun `the ten sentences differ from each other`() {
        assertEquals("stt-ondevice: sentence count", 10, all.size)
        assertEquals("stt-ondevice: two sentences share a text: $all", all.size, all.toSet().size)
    }

    @Test
    fun `each sentence is plain text with a full stop and no path or exception name`() {
        var scanned = 0
        for (sentence in all) {
            assertTrue("stt-ondevice: not ASCII: $sentence", sentence.all { it.code in 0x20..0x7e })
            assertTrue("stt-ondevice: no full stop: $sentence", sentence.endsWith("."))
            for (forbidden in listOf("/", "\\", "Exception", "Error", "null")) {
                assertFalse("stt-ondevice: '$sentence' holds '$forbidden'", sentence.contains(forbidden))
            }
            scanned++
        }
        assertEquals(10, scanned)
    }

    @Test
    fun `a refusal never repeats the token or number it refused`() {
        val unknownA = KeywordsFileText.refusal("SECRETONE\n", setOf("A"))
        val unknownB = KeywordsFileText.refusal("SECRETTWO\n", setOf("A"))
        assertEquals(KeywordsFileText.UNKNOWN_TOKEN, unknownA)
        assertEquals("stt-ondevice: two unknown tokens give one sentence", unknownA, unknownB)
        val numberA = KeywordsFileText.refusal("A :987654\n", setOf("A"))
        val numberB = KeywordsFileText.refusal("A :123456\n", setOf("A"))
        assertEquals(KeywordsFileText.BAD_NUMBER, numberA)
        assertEquals("stt-ondevice: two bad numbers give one sentence", numberA, numberB)
        for (sentence in listOfNotNull(unknownA, unknownB, numberA, numberB)) {
            assertFalse(sentence, sentence.contains("SECRET") || sentence.contains("987654") || sentence.contains("123456"))
        }
    }

    @Test
    fun `the size caps are the numbers the rules name`() {
        assertEquals(16, KeywordsFileText.MAX_LINES)
        assertEquals(4096, KeywordsFileText.MAX_BYTES)
    }
}
