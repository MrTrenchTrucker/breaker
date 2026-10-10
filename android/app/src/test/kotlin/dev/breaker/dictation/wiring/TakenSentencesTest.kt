package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pinned taken sentences must be word for word and the right length. Each constant is checked
 * against its exact sentence and against its character count, so a changed letter or an off-by-one in the
 * count fails the test. The lengths are the ones committed here; they are derived from the approved strings.
 */
internal class TakenSentencesTest {

    @Test
    fun `the pinned sentences are word for word and the right length`() {
        assertEquals("Recording stopped for a call or another app.", TakenSentences.LEAD)
        assertEquals(44, TakenSentences.LEAD.length)

        assertEquals("Recording stopped for a call or another app. Text is in History and copied.", TakenSentences.SAVED_AND_COPIED)
        assertEquals(75, TakenSentences.SAVED_AND_COPIED.length)

        assertEquals("Recording stopped for a call or another app. Nothing was heard.", TakenSentences.TAKEN_NOTHING_HEARD)
        assertEquals(63, TakenSentences.TAKEN_NOTHING_HEARD.length)

        assertEquals("Recording stopped for a call or another app. The speech could not be converted.", TakenSentences.NOT_CONVERTED)
        assertEquals(79, TakenSentences.NOT_CONVERTED.length)

        assertEquals("Another app still has the microphone. Breaker listens again once it is free.", TakenSentences.STILL_TAKEN)
        assertEquals(76, TakenSentences.STILL_TAKEN.length)
    }
}
