package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.service.DictationServiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The open-refused-as-taken path: an open refused because another app holds the microphone answers
 * the taken result and leaves the session idle, and any other failure still answers failed. The
 * reset after a taken take: it moves the session from SENDING to IDLE without stopping or closing
 * again, so a taken take pays its single audio stop at most once. Written RED first (the taken
 * result and the reset do not exist before the production edits) with the expected failure
 * recorded; the tests never touch the audio module.
 */
class DictationRunnerTakenTest {

    @Test
    fun `an open refused because another app holds the microphone is answered taken`() {
        val mic = ScriptedMic()
        mic.openError = MicSourceException("the microphone is in use by another app", null, MicSourceException.Reason.MICROPHONE_TAKEN)
        val built = Built(mic = mic)
        assertEquals("app: a refused-as-taken open must answer taken", BeginResult.Taken, built.runner.begin())
        assertEquals("app: a refused-as-taken open must leave the session idle", DictationState.IDLE, built.runner.sessionState)
        assertTrue("app: a refused-as-taken open must leave the service armed", built.controller.isArmed)
    }

    @Test
    fun `an open refused by a broken device is answered failed and not taken`() {
        val mic = ScriptedMic()
        mic.openError = MicSourceException("the microphone would not open", null, MicSourceException.Reason.DEVICE_FAILED)
        val built = Built(mic = mic)
        assertEquals(
            "app: a refused-as-broken-device open must answer failed, not taken",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            built.runner.begin(),
        )
        assertEquals("app: a broken-device open must leave the session idle", DictationState.IDLE, built.runner.sessionState)
        // A failure with no reason defaults to the device-failed default.
        val plainMic = ScriptedMic()
        plainMic.openError = MicSourceException("the microphone would not open")
        assertEquals(
            "app: an open refused without a reason defaults to the failed sentence",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            Built(mic = plainMic).runner.begin(),
        )
    }

    @Test
    fun `a reset after a taken take goes SENDING to IDLE without adding a stop or close`() {
        // A runner driven over a counting speaker: the reset must move the session from SENDING back
        // to IDLE and leave both counters unchanged, and the dropped transcription means a send now
        // answers null.
        val mic = ScriptedMic()
        val countingAudio = CountingStops(mic)
        val dictate = DictateUseCase(
            settings = FakeSettingsStore(AppSettings(mode = SttMode.LOCAL)),
            probe = FakeProbe(),
            localEngine = FakeSttEngine(SttResult.Success("hello world")),
            serverEngine = FakeSttEngine(SttResult.Success("from the server")),
            serverFormatter = CountingFormatter(),
            wavEncoder = FixedWavEncoder(),
            clock = fixedClock,
            ids = SequenceIds(),
            localFormatter = CountingFormatter(),
        )
        val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val runner = DictationRunner(dictate, SendUseCase(FakeCommitter(), RecordingHistoryStore()), countingAudio, controller, null)
        controller.adopt()
        runner.begin()
        countingAudio.speak()
        assertEquals("app: after speak the session must be RECORDING", DictationState.RECORDING, runner.sessionState)
        val finished = runner.finish()
        assertEquals(
            "app: a finished dictation must be ready to send",
            true,
            finished is FinishResult.ReadyToSend,
        )
        val stopsBefore = countingAudio.stops
        val closesBefore = mic.closes.get()
        runner.resetAfterTaken()
        assertEquals("app: a reset after a taken take must leave the session idle", DictationState.IDLE, runner.sessionState)
        assertEquals("app: a reset after a taken take must not stop the audio", stopsBefore, countingAudio.stops)
        assertEquals("app: a reset after a taken take must not close the microphone", closesBefore, mic.closes.get())
        assertNull("app: a taken take that is reset has nothing left to send", runner.send())
    }
}
