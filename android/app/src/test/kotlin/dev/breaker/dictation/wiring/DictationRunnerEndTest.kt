package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.LaunchResult
import dev.breaker.dictation.service.ServiceSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationRunnerEndTest {
    private val localMissing = UnavailableSttEngine(SttError.LOCAL_MODEL_MISSING, LOCAL_UNAVAILABLE_DETAIL)

    @Test
    fun `cancel while listening stops the capture once and keeps the service armed`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.cancel()
        assertEquals("app: cancel should stop the capture exactly once", 1, rig.audio.stops)
        assertEquals("app: cancel should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: cancel must leave the service armed", rig.controller.isArmed)
        assertEquals("app: cancel must not halt the service", 0, rig.launcher.halts)
        assertEquals("app: the runner should listen again after a cancel", BeginResult.Recording, rig.runner.begin())
        rig.audio.stopError = IllegalStateException("stop failed")
        rig.runner.cancel()
        assertEquals("app: cancel should leave the session idle even when the stop fails", DictationState.IDLE, rig.runner.sessionState)
    }

    @Test
    fun `cancel while idle does nothing and cancel while a text waits drops the text`() {
        val rig = Rig()
        rig.runner.cancel()
        assertEquals("app: cancel while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: cancel while idle must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        rig.readyToSend()
        rig.runner.cancel()
        assertNull("app: a cancelled text must not be sent", rig.runner.send())
        assertEquals("app: cancel should end a waiting dictation", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: a cancelled text must not be saved", rig.history.saved.isEmpty())
    }

    @Test
    fun `a capture that ends by itself goes idle without stopping the audio, and the next begin pays the stop first`() {
        val rig = Rig()
        var startsWhenStopped = -1
        rig.audio.onStop = { startsWhenStopped = rig.audio.starts }
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        assertEquals("app: an ended capture must not stop the audio inside the report", 0, rig.audio.stops)
        assertEquals("app: an ended capture should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: an ended capture must leave the service armed", rig.controller.isArmed)
        assertEquals("app: an ended capture must not halt the service", 0, rig.launcher.halts)
        assertEquals("app: finish after an ended capture should say Breaker is not listening", FinishResult.Failed(RunnerSentences.NOT_LISTENING), rig.runner.finish())
        assertEquals("app: finish after an ended capture must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: the runner should listen again after an ended capture", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the next begin should stop the ended capture exactly once", 1, rig.audio.stops)
        assertEquals("app: the owed stop should come before the new capture starts", 1, startsWhenStopped)
        assertEquals("app: the next begin should start the new capture", 2, rig.audio.starts)
    }

    @Test
    fun `cancel after an ended capture pays the owed stop once and clears it`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        rig.runner.cancel()
        assertEquals("app: cancel after an ended capture should stop the audio once", 1, rig.audio.stops)
        rig.runner.cancel()
        assertEquals("app: a second cancel must not stop the audio again", 1, rig.audio.stops)
        rig.runner.begin()
        assertEquals("app: a begin after the owed stop was paid must not stop the audio again", 1, rig.audio.stops)
        assertTrue("app: cancel must leave the service armed", rig.controller.isArmed)
    }

    @Test
    fun `disarm after an ended capture pays the owed stop once and halts the service once`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        rig.runner.disarm()
        assertEquals("app: disarm after an ended capture should stop the audio once", 1, rig.audio.stops)
        assertEquals("app: disarm after an ended capture should halt the service once", 1, rig.launcher.halts)
        assertFalse("app: disarm should leave the service unarmed", rig.controller.isArmed)
    }

    @Test
    fun `a failing owed stop is swallowed and the begin goes on`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        rig.audio.stopError = IllegalStateException("stop failed")
        assertEquals("app: a failing owed stop should not stop the next begin", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the owed stop should have been tried once", 1, rig.audio.stops)
        assertEquals("app: the new capture should have started", 2, rig.audio.starts)
    }

    @Test
    fun `a begin the service refuses leaves the owed stop owed`() {
        val rig = Rig(launchResult = LaunchResult.Refused)
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        rig.controller.disarm(DisarmReason.OWNER_CLOSED)
        assertEquals(
            "app: a refused begin should answer the open-Breaker sentence",
            BeginResult.Refused(ServiceSentences.COLD_START_REFUSED),
            rig.runner.begin(),
        )
        assertEquals("app: a refused begin must not pay the owed stop", 0, rig.audio.stops)
        rig.launcher.result = LaunchResult.Launched
        assertEquals("app: the runner should record once the service starts", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the owed stop should be paid by the begin that records", 1, rig.audio.stops)
    }

    @Test
    fun `an end report while idle or while a text waits leaves no stop owed`() {
        val rig = Rig()
        rig.runner.onCaptureEnded()
        assertEquals("app: an end report while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: begin after an idle end report should record", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: an idle end report must leave nothing to pay at the next begin", 0, rig.audio.stops)
        rig.runner.cancel()
        assertEquals("app: cancel should stop the audio once", 1, rig.audio.stops)

        rig.readyToSend()
        val stopsBefore = rig.audio.stops
        rig.runner.onCaptureEnded()
        assertEquals("app: an end report while a text waits must not change the session", DictationState.SENDING, rig.runner.sessionState)
        assertEquals("app: an end report while a text waits must not stop the audio", stopsBefore, rig.audio.stops)
        rig.runner.cancel()
        assertEquals("app: cancel of a waiting text should stop the audio once", stopsBefore + 1, rig.audio.stops)
        rig.runner.begin()
        assertEquals("app: an end report while a text waits must leave nothing to pay", stopsBefore + 1, rig.audio.stops)
    }

    @Test
    fun `disarm drops the capture and halts the service exactly once`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.disarm()
        assertEquals("app: disarm should stop the capture", 1, rig.audio.stops)
        assertEquals("app: disarm should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: disarm should halt the service exactly once", 1, rig.launcher.halts)
        assertFalse("app: disarm should leave the service unarmed", rig.controller.isArmed)
        rig.runner.disarm()
        assertEquals("app: a second disarm must not halt again", 1, rig.launcher.halts)
        val idle = Rig()
        idle.runner.disarm()
        assertEquals("app: disarm of an idle armed service should halt it", 1, idle.launcher.halts)
        assertEquals("app: disarm of an idle service must not stop the audio", 0, idle.audio.stops)
    }

    @Test
    fun `an end the app asked for is not reported, from finish, a failed finish or cancel`() {
        val finished = Rig(withCapture = true)
        finished.readyToSend()
        assertEquals("app: finish must not be reported as an end by itself", 0, finished.endedReports)
        assertEquals("app: finish with a wrapper should still stop once", 1, finished.audio.stops)

        val failed = Rig(withCapture = true, localEngine = localMissing)
        failed.readyToSend()
        assertEquals("app: a failed finish must not be reported as an end by itself", 0, failed.endedReports)

        val cancelled = Rig(withCapture = true)
        cancelled.runner.begin()
        cancelled.runner.cancel()
        assertEquals("app: cancel must not be reported as an end by itself", 0, cancelled.endedReports)
    }

    @Test
    fun `a microphone that closes by itself is reported once and drops the capture`() {
        val rig = Rig(withCapture = true)
        rig.runner.begin()
        rig.capture!!.close()
        assertEquals("app: a close nobody asked for should be reported once", 1, rig.endedReports)
        assertEquals("app: a reported end should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a reported end must not stop the audio on the reporting thread", 0, rig.audio.stops)
        assertTrue("app: a reported end must leave the service armed", rig.controller.isArmed)
        rig.capture.close()
        assertEquals("app: the same take must not be reported twice", 1, rig.endedReports)
        rig.runner.begin()
        assertEquals("app: the next begin should pay the owed stop once", 1, rig.audio.stops)
        assertEquals("app: the owed stop must not be reported as an end by itself", 1, rig.endedReports)
        assertEquals("app: the new take should be recording", DictationState.RECORDING, rig.runner.sessionState)
    }

    @Test
    fun `the owed stop is not taken for a second end by itself`() {
        val rig = Rig(withCapture = true)
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        assertEquals("app: the next begin should record", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the owed stop should have been paid once", 1, rig.audio.stops)
        assertEquals("app: the owed stop must not be reported as an end by itself", 0, rig.endedReports)
        assertEquals("app: the new take must still be recording", DictationState.RECORDING, rig.runner.sessionState)
    }

    @Test
    fun `the next take is reported again after an end`() {
        val rig = Rig(withCapture = true)
        rig.runner.begin()
        rig.capture!!.close()
        rig.runner.begin()
        rig.capture.close()
        assertEquals("app: begin should re-arm the reporting so the next end is reported", 2, rig.endedReports)
    }

    @Test
    fun `a service that ends while listening drops the capture and leaves the controller alone`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.onServiceEnded()
        assertEquals("app: a service end while listening should stop the capture once", 1, rig.audio.stops)
        assertEquals("app: a service end should leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a service end must not halt the service", 0, rig.launcher.halts)
        assertEquals("app: a service end must not launch the service", 0, rig.launcher.launches)
        assertTrue("app: a service end must leave the controller as it was", rig.controller.isArmed)
        assertEquals("app: finish after a service end should say Breaker is not listening", FinishResult.Failed(RunnerSentences.NOT_LISTENING), rig.runner.finish())
        rig.runner.onServiceEnded()
        assertEquals("app: a second service end must not stop the audio again", 1, rig.audio.stops)
    }

    @Test
    fun `a service that ends while idle stops no audio and leaves nothing owed`() {
        val rig = Rig()
        rig.runner.onServiceEnded()
        assertEquals("app: a service end while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: a service end while idle must leave the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a service end while idle must not halt the service", 0, rig.launcher.halts)
        assertEquals("app: the runner should listen after a service end while idle", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: a service end while idle must leave no stop to pay", 0, rig.audio.stops)
    }

    @Test
    fun `a service that ends while a text waits drops the text`() {
        val rig = Rig()
        rig.readyToSend()
        val stopsBefore = rig.audio.stops
        rig.runner.onServiceEnded()
        assertEquals("app: a service end while a text waits should end the dictation", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: a text dropped by a service end must not be sent", rig.runner.send())
        assertTrue("app: a text dropped by a service end must not be saved", rig.history.saved.isEmpty())
        assertEquals("app: a service end should stop the audio the way cancel does", stopsBefore + 1, rig.audio.stops)
        assertEquals("app: a service end must not halt the service", 0, rig.launcher.halts)
    }

    @Test
    fun `a service that ends after the capture ended by itself pays the owed stop once`() {
        val rig = Rig()
        rig.runner.begin()
        rig.runner.onCaptureEnded()
        assertEquals("app: an ended capture must not stop the audio inside the report", 0, rig.audio.stops)
        rig.runner.onServiceEnded()
        assertEquals("app: a service end should pay the owed stop once", 1, rig.audio.stops)
        rig.runner.onServiceEnded()
        assertEquals("app: a second service end must not pay the stop again", 1, rig.audio.stops)
        rig.runner.begin()
        assertEquals("app: the next begin must find nothing owed", 1, rig.audio.stops)
        assertEquals("app: a service end must not halt the service", 0, rig.launcher.halts)
    }

}
