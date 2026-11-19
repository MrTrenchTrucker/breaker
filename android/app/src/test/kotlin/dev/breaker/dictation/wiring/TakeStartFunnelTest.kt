package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.LaunchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1: the take-end funnel in [DictationRunner]. Every terminal path drops the take-start anchor,
 * so a commit that runs after the take sees an INACTIVE check and can never be refused against a
 * window from an earlier take. The interleaving tests live in [TakeStartRaceTest].
 *
 * These drive the REAL runner through [Rig], not the tile coordinator: the tile tests in
 * [TakeStartAnchorClearTest] use a fake take and would pass on the coordinator's own clears alone.
 * Here the recorder writes the anchor and the runner is the only thing that can drop it, so a
 * missing clear on any one path fails its own test.
 *
 * The anchor is a process-wide singleton, so every test clears it before AND after itself: tests
 * sharing a JVM leak nothing into each other whatever order they run in.
 *
 * UNCOMPILED: no JVM/Gradle/kotlinc in this environment. No test was run; no RED/GREEN is claimed.
 */
class TakeStartFunnelTest {

    private val recorder: TakeStartRecorder = takeStartTestRecorder

    @Before
    fun clearBefore() {
        TakeStartAnchor.clear()
    }

    @After
    fun clearAfter() {
        TakeStartAnchor.clear()
    }

    @Test
    fun `an Error from the recorder rolls the begin back and propagates`() {
        // F4: the recorder rethrows an Error. Catching only Exception let begin() throw with the
        // session stuck RECORDING, the microphone running and the anchor live.
        val boom = object : TakeStartRecorder {
            override fun capture() {
                TakeStartAnchor.capture(takeStartTestWindow)
                throw Error("the hop died")
            }

            override fun clear() {
                TakeStartAnchor.clear()
            }
        }
        val rig = Rig(takeStart = boom)
        var thrown: Throwable? = null
        try {
            rig.runner.begin()
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull("app: the Error must reach the caller", thrown)
        assertTrue("app: it must still be an Error, not a wrapped failure", thrown is Error)
        assertEquals("app: the begin must roll the session back to idle", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: the rollback must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a send that returns early still clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        rig.runner.begin()
        assertNotNull("app: begin must have captured the window", TakeStartAnchor.current())
        assertNull("app: still recording, so there is nothing to send", rig.runner.send())
        assertNull("app: an early return ends the take too - the anchor must not survive it", TakeStartAnchor.current())
    }

    @Test
    fun `a start-capture failure clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        rig.audio.startError = RuntimeException("the microphone would not start")
        val begun = rig.runner.begin()
        assertTrue("app: a failed start must answer Failed", begun is BeginResult.Failed)
        assertNull("app: the begin rollback must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a taken microphone clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        rig.audio.startError = MicSourceException("taken", reason = MicSourceException.Reason.MICROPHONE_TAKEN)
        assertEquals("app: a taken microphone must answer Taken", BeginResult.Taken, rig.runner.begin())
        assertNull("app: the taken rollback must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a finish that throws clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the take must have captured a window", TakeStartAnchor.current())
        rig.audio.stopError = RuntimeException("the capture would not stop")
        val finished = rig.runner.finish()
        assertTrue("app: a dropped dictation must answer Failed", finished is FinishResult.Failed)
        assertNull("app: the finish catch must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a nothing-heard finish clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        // No audio delivered: the use case answers Failure on an empty capture.
        val finished = rig.runner.finish()
        assertTrue("app: an empty capture must answer Failed", finished is FinishResult.Failed)
        assertNull("app: the Failure branch must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `the anchor survives finish and goes only after send`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        rig.speak()
        val finished = rig.runner.finish()
        assertTrue("app: a heard take must be ready to send", finished is FinishResult.ReadyToSend)
        // The commit reads the anchor DURING send, so finish must NOT drop it.
        assertNotNull("app: finish must keep the anchor for the commit", TakeStartAnchor.current())
        rig.runner.send()
        assertNull("app: send must clear the anchor once the commit has read it", TakeStartAnchor.current())
    }

    @Test
    fun `a cancel clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the take must have captured a window", TakeStartAnchor.current())
        rig.runner.cancel()
        assertNull("app: cancel must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a self-ended capture clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the take must have captured a window", TakeStartAnchor.current())
        rig.runner.onCaptureEnded()
        assertNull("app: a microphone that stopped by itself must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a taken settle clears the anchor`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        rig.speak()
        assertTrue("app: a heard take must be ready to send", rig.runner.finish() is FinishResult.ReadyToSend)
        rig.runner.resetAfterTaken()
        assertNull("app: the taken settle must clear the anchor", TakeStartAnchor.current())
    }

    @Test
    fun `a refused begin leaves the anchor inactive`() {
        // Not armed: the platform refuses the take, so no window is ever captured for it.
        val rig = Rig(armed = false, launchResult = LaunchResult.Refused, takeStart = recorder)
        val begun = rig.runner.begin()
        assertTrue("app: an unarmed begin must be refused", begun is BeginResult.Refused)
        assertNull("app: a refused begin must leave the check inactive", TakeStartAnchor.current())
    }

    @Test
    fun `a busy begin leaves the live take's anchor alone`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: the first begin must record", BeginResult.Recording, rig.runner.begin())
        val live = TakeStartAnchor.current()
        assertNotNull("app: the live take must have captured a window", live)
        assertEquals("app: a second begin must be refused as busy", BeginResult.Refused(RunnerSentences.BUSY), rig.runner.begin())
        assertEquals("app: a refused begin must not drop the live take's window", live, TakeStartAnchor.current())
    }

    /**
     * A1's documented default: a recorder built as a SAM lambda implements only [TakeStartRecorder.capture],
     * so [TakeStartRecorder.clear] is the default no-op and the anchor SURVIVES the take end. This is why
     * the composition root must override both, which COMPOSITION_ROOT_PASSES_THE_RECORDER pins.
     */

    @Test
    fun `a SAM-built recorder does not clear, by the default`() {
        val sam = TakeStartRecorder { TakeStartAnchor.capture(takeStartTestWindow) }
        val rig = Rig(takeStart = sam)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the SAM recorder must still capture", TakeStartAnchor.current())
        rig.runner.cancel()
        assertNotNull("app: the default clear is a no-op, so the anchor survives", TakeStartAnchor.current())
    }

    /** The other half of A1: the production shape, overriding both members, does drop it. */

    @Test
    fun `the production recorder clears on the same path`() {
        val rig = Rig(takeStart = recorder)
        assertEquals("app: begin must record", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the take must have captured a window", TakeStartAnchor.current())
        rig.runner.cancel()
        assertNull("app: a recorder overriding both members must clear", TakeStartAnchor.current())
    }
}
