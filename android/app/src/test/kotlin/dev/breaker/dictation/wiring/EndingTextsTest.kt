package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The endings refactor is byte-identical: each full line of [TakenSentences] and
 * [DeviceFailureSentences] still equals the literal the ledger approved before the refactor, and
 * [EndingTexts] holds the shared constant behind them. A changed letter or an off-by-one space is
 * a red test, not a typo. The existing TakenSentencesTest and DeviceFailureSentencesTest pin the
 * same literals and their lengths; this file adds the relationship: each branch line is its lead
 * plus the shared ending, or the runner's own nothing-heard line.
 */
internal class EndingTextsTest {

    @Test
    fun `the taken lines are the approved literals`() {
        assertEquals("Recording stopped for a call or another app.", TakenSentences.LEAD)
        assertEquals("Recording stopped for a call or another app. Text is in History and copied.", TakenSentences.SAVED_AND_COPIED)
        assertEquals("Recording stopped for a call or another app. Nothing was heard.", TakenSentences.TAKEN_NOTHING_HEARD)
        assertEquals("Recording stopped for a call or another app. The speech could not be converted.", TakenSentences.NOT_CONVERTED)
        assertEquals("Recording stopped for a call or another app. Text copied, but not saved to History.", TakenSentences.COPIED_NOT_SAVED)
        assertEquals("Another app still has the microphone. Tap the tile again when it is free.", TakenSentences.STILL_TAKEN)
    }

    @Test
    fun `the device failure lines are the approved literals`() {
        assertEquals("Recording stopped because the microphone failed.", DeviceFailureSentences.LEAD)
        assertEquals("Recording stopped because the microphone failed. Text is in History and copied.", DeviceFailureSentences.SAVED_AND_COPIED)
        assertEquals("Recording stopped because the microphone failed. Text copied, but not saved to History.", DeviceFailureSentences.COPIED_NOT_SAVED)
        assertEquals("Recording stopped because the microphone failed. Nothing was heard.", DeviceFailureSentences.NOTHING_HEARD)
        assertEquals("Recording stopped because the microphone failed. The speech could not be converted.", DeviceFailureSentences.NOT_CONVERTED)
    }

    @Test
    fun `EndingTexts holds the shared endings`() {
        assertEquals("Text is in History and copied.", EndingTexts.SAVED_AND_COPIED)
        assertEquals("Text copied, but not saved to History.", EndingTexts.COPIED_NOT_SAVED)
        assertEquals("The speech could not be converted.", EndingTexts.NOT_CONVERTED)
    }

    @Test
    fun `each branch line is its lead plus the shared ending`() {
        assertEquals(TakenSentences.LEAD + " " + EndingTexts.SAVED_AND_COPIED, TakenSentences.SAVED_AND_COPIED)
        assertEquals(TakenSentences.LEAD + " " + EndingTexts.NOT_CONVERTED, TakenSentences.NOT_CONVERTED)
        assertEquals(TakenSentences.LEAD + " " + EndingTexts.COPIED_NOT_SAVED, TakenSentences.COPIED_NOT_SAVED)
        assertEquals(TakenSentences.LEAD + " " + RunnerSentences.NOTHING_HEARD, TakenSentences.TAKEN_NOTHING_HEARD)
        assertEquals(DeviceFailureSentences.LEAD + " " + EndingTexts.SAVED_AND_COPIED, DeviceFailureSentences.SAVED_AND_COPIED)
        assertEquals(DeviceFailureSentences.LEAD + " " + EndingTexts.COPIED_NOT_SAVED, DeviceFailureSentences.COPIED_NOT_SAVED)
        assertEquals(DeviceFailureSentences.LEAD + " " + RunnerSentences.NOTHING_HEARD, DeviceFailureSentences.NOTHING_HEARD)
        assertEquals(DeviceFailureSentences.LEAD + " " + EndingTexts.NOT_CONVERTED, DeviceFailureSentences.NOT_CONVERTED)
    }
}
