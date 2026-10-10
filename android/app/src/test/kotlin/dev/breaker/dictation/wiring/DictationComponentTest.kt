package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationComponentTest {
    @Test
    fun `close switches the service off once and a second close does nothing`() {
        val built = Built()
        built.component.close()
        assertEquals("app: close should halt the service exactly once", 1, built.launcher.halts)
        assertFalse("app: close should leave the service unarmed", built.controller.isArmed)
        built.component.close()
        assertEquals("app: a second close must not halt again", 1, built.launcher.halts)
    }

    @Test
    fun `a second close after the component was used again does nothing, so the service stays armed and the capture goes on`() {
        // This documents what the close guard does today: only the first close acts.
        val built = Built()
        built.component.close()
        assertEquals("app: the first close should halt the service once", 1, built.launcher.halts)
        assertFalse("app: the first close should leave the service unarmed", built.controller.isArmed)
        built.listen()
        assertEquals("app: begin after a close should switch the service on again", 1, built.launcher.launches)
        assertTrue("app: begin after a close should leave the service armed", built.controller.isArmed)
        built.component.close()
        assertEquals("app: a second close must not halt the service again", 1, built.launcher.halts)
        assertTrue("app: a second close must leave the service armed", built.controller.isArmed)
        assertEquals("app: a second close must leave the capture running", DictationState.RECORDING, built.runner.sessionState)
        assertFalse("app: a second close must not stop the microphone", built.mic.closed.isCompleted)
        built.runner.cancel()
        awaitBounded("the capture to be closed after the cancel", built.mic.closed)
        built.assertMicWasStopped()
    }

    @Test
    fun `close with the service already off halts nothing`() {
        val built = Built(armed = false)
        built.component.close()
        assertEquals("app: close of an unarmed service should not halt anything", 0, built.launcher.halts)
    }

    @Test
    fun `close drops a dictation under way and stops the microphone`() {
        val built = Built()
        built.listen()
        built.component.close()
        assertEquals("app: close should drop the dictation", DictationState.IDLE, built.runner.sessionState)
        awaitBounded("the microphone to be closed by the capture stop", built.mic.closed)
        built.assertMicWasStopped()
        assertEquals("app: close should halt the service once", 1, built.launcher.halts)
    }

    @Test
    fun `a bound scope that ends closes the component`() {
        val built = Built()
        val scope = CoroutineScope(Job())
        built.component.bindTo(scope)
        scope.cancel()
        awaitBounded("the service to be halted when the scope ended", built.launcher.halted)
        assertEquals("app: the scope ending should halt the service exactly once", 1, built.launcher.halts)
        assertFalse("app: the scope ending should leave the service unarmed", built.controller.isArmed)
    }

    @Test
    fun `a binding that was removed does not close the component`() {
        val built = Built()
        val scope = CoroutineScope(Job())
        built.component.bindTo(scope).dispose()
        scope.cancel()
        assertEquals("app: a removed binding must not halt the service", 0, built.launcher.halts)
        assertTrue("app: a removed binding must leave the service armed", built.controller.isArmed)
    }

    @Test
    fun `a scope with no job is refused by name`() {
        val jobless = object : CoroutineScope {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        }
        val thrown = assertThrows(IllegalArgumentException::class.java) { Built().component.bindTo(jobless) }
        assertTrue("app: the refusal should say the scope needs a job, got ${thrown.message}", thrown.message!!.contains("job"))
    }

    // The cut was re-pointed: the old pin asserted the session was IDLE at report time; with the cut moved
    // into the take-end hook (the dispatch thread) the same assertion now holds once the hook has run.
    @Test
    fun `a microphone that throws while reading drops the capture, owes the stop and leaves the service armed`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val built = Built(mic = mic)
        assertEquals("app: the capture should start before the microphone fails", BeginResult.Recording, built.runner.begin())
        built.assertEndedBySelfThenPayStop()
    }

    // Same as above: the wait targets the take-end hook's dispatch thread now.
    @Test
    fun `a microphone that answers a negative read drops the capture, owes the stop and leaves the service armed`() {
        val mic = ScriptedMic()
        mic.readCode = -1
        val built = Built(mic = mic)
        assertEquals("app: the capture should start before the microphone fails", BeginResult.Recording, built.runner.begin())
        built.assertEndedBySelfThenPayStop()
    }

    // The close still happens at the first take-away (the wrapper release), but "no call from the app"
    // now means: no take-end hook runs until the dispatch thread drains - the old->new mapping.
    @Test
    fun `a microphone that answers a negative read is closed by its own report with no call from the app`() {
        val mic = ScriptedMic()
        mic.readCode = -1
        assertFailedMicrophoneIsReleased(Built(mic = mic))
    }

    // Same re-point: the close happens at the first take-away; "no call from the app" now refers to
    // the take-end hook, which waits for the dispatch thread before it runs.
    @Test
    fun `a microphone that throws while reading is closed by its own report with no call from the app`() {
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        assertFailedMicrophoneIsReleased(Built(mic = mic))
    }

    private fun assertFailedMicrophoneIsReleased(built: Built) {
        assertEquals("app: the capture should start before the microphone fails", BeginResult.Recording, built.runner.begin())
        built.awaitCaptureThreadEnd()
        assertTrue("app: a failed microphone must be released without waiting for the next call", built.mic.closed.isCompleted)
        assertEquals("app: a failed microphone should leave the session idle", DictationState.IDLE, built.runner.sessionState)
        assertTrue("app: a failed microphone must leave the service armed", built.controller.isArmed)
        assertEquals("app: a failed microphone must not halt the service", 0, built.launcher.halts)
        built.component.close()
    }

    @Test
    fun `a microphone that cannot open answers failed and can be tried again`() {
        val mic = ScriptedMic()
        mic.openError = MicSourceException("the microphone is busy")
        val built = Built(mic = mic)
        assertEquals(
            "app: a microphone that cannot open should answer failed",
            BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD),
            built.runner.begin(),
        )
        assertEquals("app: a failed open should leave the session idle", DictationState.IDLE, built.runner.sessionState)
        assertTrue("app: a failed open must leave the service armed", built.controller.isArmed)
        assertEquals("app: a failed open must not halt the service", 0, built.launcher.halts)
        mic.openError = null
        built.listen()
        built.runner.cancel()
        awaitBounded("the second capture to be closed", mic.closed)
        built.assertMicWasStopped()
    }

    @Test
    fun `a stop the app asked for is not taken for the capture ending by itself`() {
        var stateAtTranscription: DictationState? = null
        var stateOf: () -> DictationState = { DictationState.IDLE }
        val spy = object : SttEngine {
            override fun transcribe(request: SttRequest): SttResult {
                stateAtTranscription = stateOf()
                return SttResult.Success("hello world")
            }
        }
        val built = Built(localEngine = spy)
        stateOf = { built.runner.sessionState }
        built.listen()
        assertTrue("app: finish should be ready to send", built.runner.finish() is FinishResult.ReadyToSend)
        assertEquals(
            "app: the stop that finish asked for must not drop the session before the engine runs",
            DictationState.RECORDING,
            stateAtTranscription,
        )
        built.assertMicWasStopped()
    }

    @Test
    fun `a dictation through the real capture reaches the engine, is sent and is saved`() {
        val built = Built()
        built.listen()
        val finished = built.runner.finish()
        assertTrue("app: finish over the real capture should be ready to send, got $finished", finished is FinishResult.ReadyToSend)
        assertEquals("app: the text should be the engine's", "hello world", (finished as FinishResult.ReadyToSend).transcription.text)
        assertTrue("app: the text should be committed", built.runner.send()!!.isCommitted)
        assertEquals("app: the dictation should be saved once", 1, built.history.saved.size)
        assertTrue("app: the service should stay armed through a whole dictation", built.controller.isArmed)
        built.assertMicWasStopped()
    }

    @Test
    fun `with the server engine out and formatting on, a server dictation fails and the server formatter is never reached`() {
        val slot = UnavailableSttEngine(SttError.OTHER, SERVER_UNAVAILABLE_DETAIL)
        val built = Built(mode = SttMode.SERVER, serverEngine = slot)
        built.listen()
        assertEquals(
            "app: a server dictation over the unavailable slot should carry the slot's sentence",
            FinishResult.Failed(SERVER_UNAVAILABLE_DETAIL),
            built.runner.finish(),
        )
        assertEquals("app: the server formatter must not be reached while the server engine fails", 0, built.serverFormatter.calls)
        assertEquals("app: the on-device formatter must not be reached either", 0, built.localFormatter.calls)
        assertTrue("app: a failed server dictation must leave the service armed", built.controller.isArmed)
        built.assertMicWasStopped()
    }

    @Test
    fun `an on-device take is formatted by the on-device formatter and never by the server formatter`() {
        val built = Built()
        built.listen()
        val finished = built.runner.finish()
        assertTrue("app: an on-device take should be ready to send, got $finished", finished is FinishResult.ReadyToSend)
        assertEquals("app: the on-device formatter should format the on-device take once", 1, built.localFormatter.calls)
        assertEquals("app: the server formatter must not see an on-device take", 0, built.serverFormatter.calls)
        built.assertMicWasStopped()
    }

    @Test
    fun `a take that ends by itself calls onTakeEnded once`() {
        var count = 0
        val mic = ScriptedMic()
        mic.readError = MicSourceException("the microphone stopped")
        val built = Built(mic = mic, onTakeEnded = { count += 1 })
        assertEquals("app: the capture should start", BeginResult.Recording, built.runner.begin())
        built.awaitCaptureThreadEnd()
        assertEquals("app: a take that ends by itself must call onTakeEnded once", 1, count)
        built.component.close()
    }

    @Test
    fun `a user stop does not call onTakeEnded`() {
        var count = 0
        val built = Built(onTakeEnded = { count += 1 })
        built.listen()
        built.runner.cancel()
        awaitBounded("the capture to be closed after the cancel", built.mic.closed)
        assertEquals("app: a user stop must not call onTakeEnded", 0, count)
        built.assertMicWasStopped()
        built.component.close()
    }

    @Test
    fun `a take that ends by itself while the text is on its way does not disturb the send`() {
        var count = 0
        val built = Built(onTakeEnded = { count += 1 })
        built.listen()
        val finished = built.runner.finish()
        assertTrue("app: finish should be ready to send", finished is FinishResult.ReadyToSend)
        assertEquals("app: a take that ends by itself after finish must not call onTakeEnded", 0, count)
        assertTrue("app: the text should be committed", built.runner.send()!!.isCommitted)
        built.assertMicWasStopped()
        built.component.close()
    }

    @Test
    fun `a server take is formatted by the server formatter and never by the on-device formatter`() {
        val built = Built(mode = SttMode.SERVER)
        built.listen()
        val finished = built.runner.finish()
        assertTrue("app: a server take should be ready to send, got $finished", finished is FinishResult.ReadyToSend)
        assertEquals("app: the text should come from the server engine", "from the server", (finished as FinishResult.ReadyToSend).transcription.text)
        assertEquals("app: the server formatter should format the server take once", 1, built.serverFormatter.calls)
        assertEquals("app: the on-device formatter must not see a server take", 0, built.localFormatter.calls)
        built.assertMicWasStopped()
    }
}
