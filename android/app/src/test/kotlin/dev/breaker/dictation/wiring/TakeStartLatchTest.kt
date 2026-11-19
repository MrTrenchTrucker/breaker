package dev.breaker.dictation.wiring

import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A clear that starts when a microphone end is reported must not drop the window a newer take
 * captured meanwhile.
 *
 * One thread ends a capture, wins the session CAS and then waits inside the recorder's clear step.
 * A second thread begins a fresh take and captures its own window while the first is still waiting.
 * When the first thread is released it clears only if the generation it snapshotted is still the one
 * held, so the newer window survives. An unconditional clear drops it and leaves no window, which
 * skips the commit's window check.
 *
 * Exactly two named threads and two latches. Every wait is bounded so a regression fails the run
 * instead of hanging it, and a failure inside either thread is carried back to the test thread.
 */
class TakeStartLatchTest {

    @After
    fun clearTheAnchor() {
        TakeStartAnchor.clear()
    }

    @Test
    fun `a self-ended capture cleared by a racing begin keeps its own window`() {
        TakeStartAnchor.clear()

        // The window the racing begin captures.
        val A1 = TakeStartWindow(windowId = 901, packageName = "com.latch.take")

        // Recorder over the real anchor. Both clear paths wait inside the clear step so the racing
        // begin can capture its window first.
        val casWon = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val recorder: TakeStartRecorder = object : TakeStartRecorder {
            override fun capture() {
                TakeStartAnchor.capture(A1)
            }
            override fun generation(): Long = TakeStartAnchor.generation()
            override fun clearIf(generation: Long) {
                casWon.countDown()
                assertTrue("app: the clear step was released in time", releaseClear.await(10, TimeUnit.SECONDS))
                TakeStartAnchor.clearIfGeneration(generation)
            }
            override fun clear() {
                casWon.countDown()
                assertTrue("app: the clear step was released in time", releaseClear.await(10, TimeUnit.SECONDS))
                TakeStartAnchor.clear()
            }
        }
        val rig = Rig(takeStart = recorder)

        // A first take is recording and holds the anchor.
        assertEquals("app: the first begin records", BeginResult.Recording, rig.runner.begin())
        assertNotNull("app: the first take captured a window", TakeStartAnchor.current())

        val endFailure = AtomicReference<Throwable>()
        val beginFailure = AtomicReference<Throwable>()
        val beginResult = AtomicReference<BeginResult>()

        // Ends the capture, wins the CAS and waits inside the clear step.
        val tEnd = Thread({
            try {
                rig.runner.onCaptureEnded()
            } catch (t: Throwable) {
                endFailure.set(t)
            }
        }, "latch-end")
        tEnd.start()

        // The end must win the CAS before the begin, or the begin is refused as busy.
        assertTrue("app: the end won the session CAS in time", casWon.await(10, TimeUnit.SECONDS))

        // A fresh begin captures its window while the end is still inside its clear step.
        val tBegin = Thread({
            try {
                beginResult.set(rig.runner.begin())
            } catch (t: Throwable) {
                beginFailure.set(t)
            }
        }, "latch-begin")
        tBegin.start()
        tBegin.join(10_000)
        assertFalse("app: the racing begin finished in time", tBegin.isAlive)
        assertNull("app: the racing begin threw", beginFailure.get())
        assertEquals("app: the racing begin records", BeginResult.Recording, beginResult.get())

        // Release the end: its snapshot no longer matches the held generation, so the newer window stays.
        releaseClear.countDown()
        tEnd.join(10_000)
        assertFalse("app: the end finished in time", tEnd.isAlive)
        assertNull("app: the end threw", endFailure.get())

        assertEquals("app: the racing clear did not wipe the newer take's window", A1, TakeStartAnchor.current())
    }
}
