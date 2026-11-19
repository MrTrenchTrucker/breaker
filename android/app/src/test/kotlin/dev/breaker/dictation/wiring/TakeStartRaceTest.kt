package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.CommitOutcomeResult
import dev.breaker.dictation.core.port.TextCommitter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The take-start races: another thread ends the take WHILE finish or send is running. Split out of
 * [TakeStartFunnelTest] by theme (the layout gate caps a file at 300 lines); the recorder shape is
 * shared in [TakeStartTestSupport].
 *
 * F3b: finish must not resurrect a take that onCaptureEnded or cancel already ended. F1: a cancel
 * during the commit must not take the window from under it. F5: an Error from the commit must still
 * clear. Each is driven through the REAL runner via [Rig] and the fakes' own public hooks, so
 * WiringFakes is not edited.
 *
 * UNCOMPILED: no JVM/Gradle/kotlinc in this environment. No test was run; no RED/GREEN is claimed.
 */
class TakeStartRaceTest {

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
    fun `a capture that ends while finish runs is not resurrected`() {
        // F3b (a): the capture thread reports its end WHILE stopCapture runs, before finish writes
        // its result. onStop is the fake audio source's own hook, so WiringFakes is not edited.
        val rig = Rig(takeStart = recorder)
        rig.runner.begin()
        rig.speak()
        rig.audio.onStop = { rig.runner.onCaptureEnded() }
        val finished = rig.runner.finish()
        assertTrue("app: a take ended elsewhere must answer Failed", finished is FinishResult.Failed)
        assertEquals("app: the existing sentence, no new words",
            RunnerSentences.NOT_LISTENING, (finished as FinishResult.Failed).sentence)
        assertEquals("app: the session must stay idle, not resurrected",
            DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: the anchor must be cleared", TakeStartAnchor.current())
        assertNull("app: a later send must answer null", rig.runner.send())
    }

    @Test
    fun `a cancel while finish runs is not resurrected`() {
        // F3b (b): the same ordering with cancel(), which is older than the anchor: a cancel during
        // transcription used to be overwritten by finish's unconditional session.set.
        val rig = Rig(takeStart = recorder)
        rig.runner.begin()
        rig.speak()
        // One-shot: cancel() stops the audio, which calls onStop again; an unguarded hook recurses.
        var fired = false
        rig.audio.onStop = { if (!fired) { fired = true; rig.runner.cancel() } }
        val finished = rig.runner.finish()
        assertTrue("app: a cancelled take must answer Failed", finished is FinishResult.Failed)
        assertEquals("app: the existing sentence, no new words",
            RunnerSentences.NOT_LISTENING, (finished as FinishResult.Failed).sentence)
        assertEquals("app: the session must stay idle", DictationState.IDLE, rig.runner.sessionState)
        assertNull("app: the anchor must be cleared", TakeStartAnchor.current())
        assertNull("app: a later send must answer null", rig.runner.send())
    }

    @Test
    fun `the plain finish still sends with the anchor present`() {
        // F3b (c): nobody else touches the session, so the CAS from the same snapshot succeeds and
        // the normal flow is unchanged. The commit must still see the window check.
        val rig = Rig(takeStart = recorder)
        rig.runner.begin()
        rig.speak()
        val finished = rig.runner.finish()
        assertTrue("app: the plain flow must still be ReadyToSend", finished is FinishResult.ReadyToSend)
        assertNotNull("app: the commit must still see the window check", TakeStartAnchor.current())
        assertNotNull("app: the plain send must still commit", rig.runner.send())
        assertNull("app: the take is over after the send", TakeStartAnchor.current())
    }

    @Test
    fun `a cancel during a commit cannot take the window from under it`() {
        // F1: send.send hops the commit to the main thread, and the service can end there while the
        // commit is running. The committer below IS that commit: it cancels, then reads the anchor
        // exactly as NodeFocusedField does. Before the guard, cancel() had already dropped it, so the
        // commit saw null = INACTIVE and skipped the window check entirely.
        var seen: TakeStartWindow? = null
        var lateRunner: DictationRunner? = null
        val committer = object : TextCommitter {
            override fun commit(request: CommitRequest): CommitOutcomeResult {
                lateRunner?.cancel()
                seen = TakeStartAnchor.current()
                return CommitOutcomeResult(CommitOutcome.COMMITTED)
            }
        }
        val rig = Rig(committer = committer, takeStart = recorder)
        lateRunner = rig.runner
        rig.runner.begin()
        rig.speak()
        assertTrue("app: the take must be ready to send", rig.runner.finish() is FinishResult.ReadyToSend)
        assertNotNull("app: the anchor must be present before send", TakeStartAnchor.current())
        assertNotNull("app: the send must still answer", rig.runner.send())
        assertNotNull("app: the commit must still see the window after a mid-send cancel", seen)
        assertNull("app: send clears the anchor itself once the commit is done", TakeStartAnchor.current())
    }

    @Test
    fun `an Error from the commit still clears the anchor`() {
        // F5: send.send can throw an Error. Before the finally, the clear was a plain statement after
        // it and was skipped, leaving a stale window for the NEXT take to be checked against.
        val committer = object : TextCommitter {
            override fun commit(request: CommitRequest): CommitOutcomeResult =
                throw StackOverflowError("the commit blew up")
        }
        val rig = Rig(committer = committer, takeStart = recorder)
        rig.runner.begin()
        rig.speak()
        assertTrue("app: the take must be ready to send", rig.runner.finish() is FinishResult.ReadyToSend)
        assertNotNull("app: the anchor must be present before send", TakeStartAnchor.current())
        var threw = false
        try {
            rig.runner.send()
        } catch (e: StackOverflowError) {
            threw = true  // an Error is never swallowed
        }
        assertTrue("app: the Error must propagate", threw)
        assertNull("app: the finally must clear even when the commit throws", TakeStartAnchor.current())
    }

    @Test
    fun `a throwing clear cannot leave the send guard stuck`() {
        // P1/5: the nested finally. If the flag stayed up after a clear() that threw, every later
        // cancel would skip its clear and leak the anchor. The recorder throws on its FIRST clear only,
        // so the second one (from cancel) can be observed.
        var clears = 0
        val flaky = object : TakeStartRecorder {
            override fun capture() {
                TakeStartAnchor.capture(takeStartTestWindow)
            }

            override fun clear() {
                clears += 1
                if (clears == 1) throw RuntimeException("the first clear refused")
                // Every later clear must do what the real recorder does, or the final assertNull
                // below fails on a CORRECT runner: only a recorder ever clears the anchor.
                TakeStartAnchor.clear()
            }
        }
        val rig = Rig(takeStart = flaky)
        rig.runner.begin()
        rig.speak()
        assertTrue("app: the take must be ready to send", rig.runner.finish() is FinishResult.ReadyToSend)
        var threw = false
        try {
            rig.runner.send()
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("app: the clear's failure must propagate", threw)
        TakeStartAnchor.capture(takeStartTestWindow)
        rig.runner.cancel()
        assertNull("app: the guard must be down again, so a later cancel still clears", TakeStartAnchor.current())
    }

}
