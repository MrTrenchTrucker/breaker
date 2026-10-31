package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device failure sentences must be word for word and the right length, and each outcome must
 * answer with its own sentence. The lengths are the ones of the approved texts.
 */
internal class DeviceFailureSentencesTest {

    @Test
    fun `the pinned device failure sentences are word for word and the right length`() {
        assertEquals("Recording stopped because the microphone failed.", DeviceFailureSentences.LEAD)
        assertEquals(48, DeviceFailureSentences.LEAD.length)

        assertEquals("Recording stopped because the microphone failed. Text is in History and copied.", DeviceFailureSentences.SAVED_AND_COPIED)
        assertEquals(79, DeviceFailureSentences.SAVED_AND_COPIED.length)

        assertEquals("Recording stopped because the microphone failed. Text copied, but not saved to History.", DeviceFailureSentences.COPIED_NOT_SAVED)
        assertEquals(87, DeviceFailureSentences.COPIED_NOT_SAVED.length)

        assertEquals("Recording stopped because the microphone failed. Nothing was heard.", DeviceFailureSentences.NOTHING_HEARD)
        assertEquals(67, DeviceFailureSentences.NOTHING_HEARD.length)

        assertEquals("Recording stopped because the microphone failed. The speech could not be converted.", DeviceFailureSentences.NOT_CONVERTED)
        assertEquals(83, DeviceFailureSentences.NOT_CONVERTED.length)
    }

    @Test
    fun `each outcome of the device failure answers with its own sentence`() {
        val lines: OutcomeLines = DeviceFailureSentences.lines
        assertEquals(DeviceFailureSentences.SAVED_AND_COPIED, lines.savedAndCopied)
        assertEquals(DeviceFailureSentences.COPIED_NOT_SAVED, lines.copiedNotSaved)
        assertEquals(DeviceFailureSentences.NOTHING_HEARD, lines.blank)
        assertEquals(DeviceFailureSentences.LEAD, lines.noAudio)
        assertEquals(DeviceFailureSentences.NOT_CONVERTED, lines.notConverted)
    }

    @Test
    fun `device failure lines expose no failed function`() {
        assertNull("settle: device failure lines must not set `failed`", DeviceFailureSentences.lines.failed)
    }
}
