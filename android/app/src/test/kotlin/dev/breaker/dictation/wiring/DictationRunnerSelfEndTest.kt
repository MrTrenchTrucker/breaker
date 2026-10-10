package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runner's self-end bookkeeping and the thread it runs on. Every test drives a capture end
 * through [onCaptureEnded] on a single (test) thread in a chosen order, never by timing: an end is
 * reported once or twice and its owed stop is paid exactly once. No sleeps, no load, no fork.
 */
class DictationRunnerSelfEndTest {

    @Test
    fun `a self-end lands idle with the service armed and stops nothing on the capture thread`() {
        val rig = Rig()
        rig.runner.begin()
        assertEquals("app: must be recording before it can end by itself", DictationState.RECORDING, rig.runner.sessionState)
        // A device stops on its own (not an app request): reported from the capture thread.
        rig.runner.onCaptureEnded()
        assertEquals("app: a self-end lands idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a self-end must not stop the audio where it was reported", 0, rig.audio.stops)
        assertTrue("app: a self-end keeps the service armed", rig.controller.isArmed)
    }

    @Test
    fun `a second end while idle is ignored so nothing more is paid until the next begin`() {
        val rig = Rig()
        // Two ends reported back-to-back on one thread: only the first ever reaches RECORDING.
        assertEquals("app: the first begin records", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: a fresh recording stops nothing until it is told to end", 0, rig.audio.stops)
        // First end: leaves an owed stop (recorded), does not execute it.
        rig.runner.onCaptureEnded()
        assertEquals("app: the first self-end lands idle with audio still running", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: the first self-end must not stop the audio on the report thread", 0, rig.audio.stops)
        // A second reported end while idle is a hard no-op; nothing more is owed beyond that one.
        rig.runner.onCaptureEnded()
        assertEquals("app: the second end while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: the session stays idle after the ignored second end", DictationState.IDLE, rig.runner.sessionState)
        // The owed stop is paid exactly once by the next begin.
        assertEquals("app: the next begin pays the single owed stop", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the next begin must stop the ended capture exactly once", 1, rig.audio.stops)
    }

    @Test
    fun `a cancel of a recording stops once and leaves nothing owed to the next begin`() {
        val rig = Rig()
        rig.runner.begin()
        assertEquals("app: must be recording before cancel", DictationState.RECORDING, rig.runner.sessionState)
        rig.runner.cancel()
        assertEquals("app: cancel of a recording stops exactly once", 1, rig.audio.stops)
        assertEquals("app: cancel leaves the session idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: cancel keeps the service armed", rig.controller.isArmed)
        // The dropped capture is clean: a following begin records without an unowed stop.
        assertEquals("app: the next begin records cleanly", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: the next begin must not stop the audio again after paying once", 1, rig.audio.stops)
    }

    @Test
    fun `a cancel while idle touches nothing and leaves no stop owed`() {
        val rig = Rig()
        rig.runner.cancel()
        assertEquals("app: cancel while idle must not stop the audio", 0, rig.audio.stops)
        assertEquals("app: cancel while idle stays idle", DictationState.IDLE, rig.runner.sessionState)
        assertTrue("app: cancel while idle keeps the service armed", rig.controller.isArmed)
        assertEquals("app: a begin after an idle cancel records with nothing owed", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: a begin after an idle cancel must not stop the audio", 0, rig.audio.stops)
    }

    @Test
    fun `a self-end then a fresh begin keeps the armed service and runs no extra stop`() {
        val rig = Rig()
        rig.runner.begin()
        // A capture stops itself; onCaptureEnded returns it to idle with nothing else running.
        rig.runner.onCaptureEnded()
        assertEquals("app: an ended capture stays idle", DictationState.IDLE, rig.runner.sessionState)
        // A fresh begin after a self-end re-arms once and records cleanly without any extra stop.
        assertEquals("app: a begin-after-self-end records cleanly", BeginResult.Recording, rig.runner.begin())
        assertEquals("app: an ended capture's next begin stops exactly the owed one", 1, rig.audio.stops)
    }
}
