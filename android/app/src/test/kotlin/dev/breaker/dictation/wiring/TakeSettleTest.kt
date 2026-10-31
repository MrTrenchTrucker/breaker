package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distinct words per outcome, so a line that answers the wrong outcome is named by its word. */
private val probe = OutcomeLines(savedAndCopied = "SAVED", copiedNotSaved = "COPIED", blank = "BLANK", noAudio = "NOAUDIO", notConverted = "NOTCONVERTED")

/** A probe with a `failed` function that prepends a marker to the engine's sentence. */
private val failedProbe = OutcomeLines(savedAndCopied = "SAVED", copiedNotSaved = "COPIED", blank = "BLANK", noAudio = "NOAUDIO", notConverted = "NOTCONVERTED", failed = { s -> "SHOWN:" + s })

/** A clipboard that throws on every copy. */
private class ThrowingClipboard : TakenClipboard {
    override fun copy(text: String) {
        throw IllegalStateException("the clipboard refused")
    }
}

internal class TakeSettleTest {

    @Test
    fun `a text that is saved answers the saved line and copies the text`() {
        val history = RecordingHistory()
        val clipboard = RecordingClipboard()
        val settle = TakeSettle(history, clipboard)
        val finished = FinishResult.ReadyToSend(tileTranscription("captured text"))
        val saveFailed: Boolean = settle.save(finished)
        assertFalse("settle: a working save reports no failure", saveFailed)
        assertEquals("settle: the saved text answers the saved line", "SAVED", settle.line(finished, saveFailed, probe))
        assertEquals("settle: the saved text is copied once", listOf("captured text"), clipboard.copies)
        assertEquals("settle: the saved text is stored once", 1, history.saved.size)
    }

    @Test
    fun `a text whose save throws answers the copied not saved line and still copies`() {
        val clipboard = RecordingClipboard()
        val settle = TakeSettle(TakeYieldSaveFailedHistory(), clipboard)
        val finished = FinishResult.ReadyToSend(tileTranscription("captured text"))
        val saveFailed: Boolean = settle.save(finished)
        assertTrue("settle: a save that throws is reported", saveFailed)
        assertEquals("settle: a failed save answers the copied not saved line", "COPIED", settle.line(finished, saveFailed, probe))
        assertEquals("settle: a failed save still copies the text", listOf("captured text"), clipboard.copies)
    }

    @Test
    fun `a blank text is neither saved nor copied and answers the blank line`() {
        val history = RecordingHistory()
        val clipboard = RecordingClipboard()
        val settle = TakeSettle(history, clipboard)
        val finished = FinishResult.ReadyToSend(tileTranscription("   "))
        val saveFailed: Boolean = settle.save(finished)
        assertFalse("settle: a blank text reports no save failure", saveFailed)
        assertEquals("settle: a blank text answers the blank line", "BLANK", settle.line(finished, saveFailed, probe))
        assertEquals("settle: a blank text is not copied", emptyList<String>(), clipboard.copies)
        assertEquals("settle: a blank text is not stored", 0, history.saved.size)
    }

    @Test
    fun `the empty take sentence answers the no audio line and nothing is saved or copied`() {
        val history = RecordingHistory()
        val clipboard = RecordingClipboard()
        val settle = TakeSettle(history, clipboard)
        val finished = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        val saveFailed: Boolean = settle.save(finished)
        assertFalse("settle: an empty take reports no save failure", saveFailed)
        assertEquals("settle: the empty take sentence answers the no audio line", "NOAUDIO", settle.line(finished, saveFailed, probe))
        assertEquals("settle: an empty take is not copied", emptyList<String>(), clipboard.copies)
        assertEquals("settle: an empty take is not stored", 0, history.saved.size)
    }

    @Test
    fun `another failure and a missing result answer the not converted line and never their detail`() {
        val settle = TakeSettle(RecordingHistory(), RecordingClipboard())
        val other = FinishResult.Failed("The speech could not be converted.")
        assertEquals("settle: another failure answers the not converted line", "NOTCONVERTED", settle.line(other, false, probe))
        assertEquals("settle: a missing result answers the not converted line", "NOTCONVERTED", settle.line(null, false, probe))
        assertFalse("settle: a failure saves nothing", settle.save(other))
        assertFalse("settle: a missing result saves nothing", settle.save(null))
    }

    @Test
    fun `a take set to show the engine sentence answers that sentence for a failure`() {
        val settle = TakeSettle(RecordingHistory(), RecordingClipboard())
        val finished = FinishResult.Failed("The server key is not set.")
        assertEquals("settle: a failed take with a `failed` probe answers the engine sentence", "SHOWN:The server key is not set.", settle.line(finished, false, failedProbe))
    }

    @Test
    fun `with that probe the empty take sentence still answers the no-audio line and a missing result still answers the not-converted line`() {
        val settle = TakeSettle(RecordingHistory(), RecordingClipboard())
        val empty = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        assertEquals("settle: an empty take sentence answers the no audio line", "NOAUDIO", settle.line(empty, false, failedProbe))
        assertEquals("settle: a missing result answers the not converted line", "NOTCONVERTED", settle.line(null, false, failedProbe))
    }

    @Test
    fun `a clipboard that throws does not escape and the line is still answered`() {
        val settle = TakeSettle(RecordingHistory(), ThrowingClipboard())
        val finished = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertEquals("settle: a throwing clipboard still answers the saved line", "SAVED", settle.line(finished, false, probe))
    }

    @Test
    fun `a history that throws does not escape the save and no store saves nothing`() {
        val throwing = TakeSettle(TakeYieldSaveFailedHistory(), RecordingClipboard())
        assertTrue("settle: a history that throws is reported, not raised", throwing.save(FinishResult.ReadyToSend(tileTranscription("text"))))
        val none = TakeSettle(null, RecordingClipboard())
        assertFalse("settle: with no history store nothing is saved or reported", none.save(FinishResult.ReadyToSend(tileTranscription("text"))))
    }

    @Test
    fun `the empty take sentence is the one core answers with`() {
        assertEquals("settle: the shared empty take sentence", "No audio was captured", EMPTY_TAKE_SENTENCE)
    }
}
