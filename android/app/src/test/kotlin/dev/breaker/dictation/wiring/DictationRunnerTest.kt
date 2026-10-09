package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.ServiceSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationRunnerTest {
    private val localMissing = UnavailableSttEngine(SttError.LOCAL_MODEL_MISSING, STAND_IN_LOCAL_DETAIL)

    @Test
    fun `begin on an armed service starts one capture and leaves the service alone`() {
        val rig = Rig()
        assertEquals("app: begin on an armed service should answer Recording", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: begin should start the audio exactly once", 1, rig.audio.starts)
        assertEquals("app: begin should move the session to RECORDING", DictationState.RECORDING, rig.runner.sessionState)
        assertEquals("app: begin on an armed service must not launch the service", 0, rig.launcher.launches)
        assertEquals("app: begin must not halt the service", 0, rig.launcher.halts)
    }

    @Test
    fun `begin without microphone permission is refused with that sentence and starts nothing`() {
        val rig = Rig(armed = false, permissionGranted = false)
        assertEquals(
            "app: begin without permission should be refused with the permission sentence",
            BeginResult.Refused(ServiceSentences.MIC_PERMISSION_MISSING),
            rig.runner.begin(),
        )
        assertEquals("app: a refused begin must not start the audio", 0, rig.audio.starts)
        assertEquals("app: a refused begin must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: begin without permission must not ask the launcher", 0, rig.launcher.launches)
    }

    @Test
    fun `a cold start the platform refuses says to open Breaker once, and one it accepts records`() {
        val rig = Rig(armed = false, launchResult = LaunchResult.Refused)
        assertEquals(
            "app: a refused cold start should answer with the open-Breaker sentence",
            BeginResult.Refused("Open Breaker once to switch dictation on."),
            rig.runner.begin(),
        )
        assertEquals("app: a refused cold start should try the launcher exactly once", 1, rig.launcher.launches)
        assertEquals("app: a refused cold start must not start the audio", 0, rig.audio.starts)
        assertEquals("app: a refused cold start must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertFalse("app: a refused cold start must leave the service unarmed", rig.controller.isArmed)
        val accepted = Rig(armed = false)
        assertEquals("app: an accepted cold start should record", BeginResult.Recording, accepted.runner.begin())
        assertEquals("app: an accepted cold start should launch once", 1, accepted.launcher.launches)
        assertTrue("app: an accepted cold start should leave the service armed", accepted.controller.isArmed)
    }

    @Test
    fun `begin while listening or while a text waits is refused as busy`() {
        val rig = Rig()
        rig.runner.begin()
        assertEquals("app: a second begin while recording should be refused as busy", BeginResult.Refused("Breaker is already listening."), rig.runner.begin())
        assertEquals("app: a refused begin must not start the audio again", 1, rig.audio.starts)
        rig.speak()
        rig.runner.finish()
        assertEquals("app: begin while a text waits to be sent should be refused as busy", BeginResult.Refused(RunnerSentences.BUSY), rig.runner.begin())
    }

    @Test
    fun `a capture that cannot start answers failed, tries to stop and can be started again`() {
        val rig = Rig()
        rig.audio.startError = IllegalStateException("capture is already running")
        assertEquals(
            "app: a capture that throws on start should answer the could-not-record sentence",
            BeginResult.Failed("Breaker could not start recording."),
            rig.runner.begin(),
        )
        assertEquals("app: a failed start should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a failed start should try to stop the capture once", 1, rig.audio.stops)
        assertEquals("app: a failed start must not touch the service", 0, rig.launcher.halts)
        rig.audio.startError = null
        assertEquals("app: the runner should record again after a failed start", BeginResult.Recording, rig.runner.begin())
        rig.runner.cancel()
        rig.audio.startError = IllegalStateException("start failed")
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals(
            "app: a failing stop after a failing start should still answer failed",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            rig.runner.begin(),
        )
        assertEquals("app: the session should be idle after both failures", DictationState.IDLE, rig.runner.sessionState)
    }

    @Test
    fun `finish returns the text, stops the capture once and keeps the service armed`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        val result = rig.runner.finish()
        assertTrue("app: finish with speech should answer ReadyToSend, got $result", result is FinishResult.ReadyToSend)
        assertEquals("app: finish should return the engine's text", "hello world", (result as FinishResult.ReadyToSend).transcription.text)
        assertEquals("app: finish should stop the capture exactly once", 1, rig.audio.stops)
        assertEquals("app: finish should leave the text waiting to be sent", DictationState.SENDING, rig.runner.sessionState)
        assertEquals("app: an on-device text should go through the local formatter once", 1, rig.localFormatter.calls)
        assertEquals("app: an on-device text must not reach the server formatter", 0, rig.serverFormatter.calls)
        assertTrue("app: finish must leave the service armed", rig.controller.isArmed)
    }

    @Test
    fun `finish with an engine that cannot transcribe answers its sentence and keeps the service armed`() {
        val rig = Rig(localEngine = localMissing)
        rig.runner.begin()
        rig.speak()
        assertEquals("app: finish over the unavailable slot should carry the slot's sentence", FinishResult.Failed(STAND_IN_LOCAL_DETAIL), rig.runner.finish())
        assertEquals("app: a failed finish should still stop the capture once", 1, rig.audio.stops)
        assertEquals("app: a failed finish should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: a failed finish must leave the service armed", rig.controller.isArmed)
        assertEquals("app: a failed finish must not halt the service", 0, rig.launcher.halts)
    }

    @Test
    fun `a finish whose audio stop fails answers could-not-finish, drops the dictation and the next begin works`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals(
            "app: a finish whose audio stop throws should answer the could-not-finish sentence",
            FinishResult.Failed("Breaker could not finish listening."),
            rig.runner.finish(),
        )
        assertEquals("app: the finish should have tried the audio stop once", 1, rig.audio.stops)
        assertEquals("app: a finish that failed to stop should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: a finish that failed to stop leaves nothing to send", rig.runner.send())
        assertTrue("app: a finish that failed to stop must leave the service armed", rig.controller.isArmed)
        assertEquals("app: a finish that failed to stop must not halt the service", 0, rig.launcher.halts)
        rig.audio.stopError = null
        assertEquals("app: the runner should record again after a finish that failed", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the new capture should have started", 2, rig.audio.starts)
        assertEquals("app: the begin after a failed finish must not stop the audio again", 1, rig.audio.stops)
    }

    @Test
    fun `a capture end reported while a failing stop runs leaves no stop owed`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        rig.audio.onStop = { rig.runner.onCaptureEnded() }
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals(
            "app: a finish whose audio stop throws should answer the could-not-finish sentence",
            FinishResult.Failed(RunnerSentences.COULD_NOT_FINISH),
            rig.runner.finish(),
        )
        rig.audio.onStop = {}
        rig.audio.stopError = null
        assertEquals("app: the runner should record again", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: a failed finish already paid the stop, so the next begin owes none", 1, rig.audio.stops)
    }

    @Test
    fun `finish without a listening session answers not listening, and a silent take fails`() {
        val rig = Rig()
        assertEquals("app: finish while idle should say Breaker is not listening", FinishResult.Failed("Breaker is not listening."), rig.runner.finish())
        assertEquals("app: finish while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: finish while idle must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        rig.runner.begin()
        assertTrue("app: finish with no audio should fail", rig.runner.finish() is FinishResult.Failed)
        assertEquals("app: a silent take should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: the runner should record again after a silent take", BeginResult.Recording, rig.runner.begin())
    }

    @Test
    fun `finish passes the send-word offset on so the phrase is cut from the audio`() {
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        assertTrue(
            "app: an offset at the very start keeps no audio, so finish should fail",
            rig.runner.finish(trimBeforeMs = 0L) is FinishResult.Failed,
        )
        rig.runner.begin()
        rig.speak()
        assertTrue("app: without an offset the whole take is kept", rig.runner.finish() is FinishResult.ReadyToSend)
    }

    @Test
    fun `send commits, saves, ends the dictation and leaves the capture and the service alone`() {
        val rig = Rig()
        rig.readyToSend()
        val stopsBefore = rig.audio.stops
        val result = rig.runner.send()
        assertNotNull("app: send after a finished dictation should answer a result", result)
        assertTrue("app: the text should have been committed", result!!.isCommitted)
        assertEquals("app: send should save the dictation to history once", 1, rig.history.saved.size)
        assertEquals("app: the saved text should be the dictated one", "hello world", rig.history.saved[0].text)
        assertEquals("app: send must not stop the capture again", stopsBefore, rig.audio.stops)
        assertEquals("app: send must not halt the service", 0, rig.launcher.halts)
        assertTrue("app: send must leave the service armed", rig.controller.isArmed)
        assertEquals("app: send should end the dictation", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: a second send has nothing to send", rig.runner.send())
    }

    @Test
    fun `send over the unavailable committer reports a failed commit and still saves`() {
        val rig = Rig(committer = UnavailableTextCommitter())
        rig.readyToSend()
        val result = rig.runner.send()
        assertNotNull("app: send should answer a result even when the commit fails", result)
        assertEquals("app: the unavailable committer should give a failed outcome", CommitOutcome.FAILED, result!!.outcome.outcome)
        assertEquals("app: the failed outcome should carry the slot's sentence", COMMIT_UNAVAILABLE_DETAIL, result.outcome.detail)
        assertEquals("app: the dictation should be saved even though the commit failed", 1, rig.history.saved.size)
        assertEquals("app: a failed send should still end the dictation", DictationState.IDLE, rig.runner.sessionState)
    }

    @Test
    fun `send with nothing waiting answers null and saves nothing`() {
        val rig = Rig()
        assertNull("app: send while idle should answer null", rig.runner.send())
        rig.runner.begin()
        assertNull("app: send while still listening should answer null", rig.runner.send())
        assertTrue("app: a send with nothing waiting must not touch history", rig.history.calls.isEmpty())
    }

    @Test
    fun `finish while a text waits answers not listening and leaves the text to be sent`() {
        val rig = Rig()
        rig.readyToSend()
        val stopsBefore = rig.audio.stops
        assertEquals(
            "app: finish while a text waits to be sent should say Breaker is not listening",
            FinishResult.Failed(RunnerSentences.NOT_LISTENING),
            rig.runner.finish(),
        )
        assertEquals("app: finish while a text waits must not stop the audio again", stopsBefore, rig.audio.stops)
        assertEquals("app: finish while a text waits must leave the text waiting", DictationState.SENDING, rig.runner.sessionState)
        assertEquals("app: finish while a text waits must not format the text again", 1, rig.localFormatter.calls)
        assertTrue("app: finish while a text waits must leave the service armed", rig.controller.isArmed)
        val sent = rig.runner.send()
        assertNotNull("app: the text must still be there to send after the refused finish", sent)
        assertTrue("app: the waiting text should still be committed", sent!!.isCommitted)
    }

    @Test
    fun `a failed take whose engine gives no detail answers the nothing-heard sentence`() {
        val rig = Rig(localEngine = FakeSttEngine(SttResult.Failure(SttError.OTHER)))
        rig.runner.begin()
        rig.speak()
        assertEquals(
            "app: a failure with no detail should answer the nothing-heard sentence",
            FinishResult.Failed("Nothing was heard."),
            rig.runner.finish(),
        )
        assertEquals("app: a failure with no detail should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: a failure with no detail must leave the service armed", rig.controller.isArmed)
    }

    @Test
    fun `the nothing-heard sentence is the plain words`() {
        assertEquals("app: the nothing-heard sentence changed", "Nothing was heard.", RunnerSentences.NOTHING_HEARD)
    }

    @Test
    fun `a ready-to-send answer prints its transcription id and never the text`() {
        val text = "the private words that were dictated"
        val made = Transcription(id = "t-77", text = text, source = TranscriptionSource.LOCAL, model = "small", durationMs = 1_000L, createdAt = 5L)
        val printed = FinishResult.ReadyToSend(made).toString()
        assertTrue("app: a ready-to-send answer should print the transcription id, got $printed", printed.contains("t-77"))
        assertFalse("app: a ready-to-send answer must not print the dictated text", printed.contains(text))
        val rig = Rig()
        rig.runner.begin()
        rig.speak()
        val real = rig.runner.finish()
        assertTrue("app: finish with speech should answer ReadyToSend", real is FinishResult.ReadyToSend)
        assertTrue("app: the answer of finish should print its transcription id", real.toString().contains("id-1"))
        assertFalse("app: the answer of finish must not print the dictated text", real.toString().contains("hello world"))
    }
}
